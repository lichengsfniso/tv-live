package com.tvbridge.app

import android.content.Context
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 唯一后台业务入口。
 *
 * 冷启动、周期、手动三种触发都汇入 [refresh]；配置变化汇入 [reconfigure]。
 * 业务层再做一层 30min 节流（WorkManager 唯一任务名是 scheduler 层去重）。
 *
 * T01 阶段先搭好“节流 + 状态 + 广播”框架，A（订阅源刷新）由 T02 填充，
 * B（在线频道表）由 T05 填充。文件内以 `A 步骤` / `B 步骤` 注释标出接入点。
 */
object UpdateCoordinator {
    @Volatile var status: UpdateStatus = UpdateStatus()
        private set

    private val listeners = CopyOnWriteArrayList<(UpdateStatus) -> Unit>()
    private val refreshLock = Any()
    @Volatile private var refreshing = false

    /** LiveActivity 把它的 repo 实例绑定进来，避免后台再 new 一个导致内存状态分叉。 */
    @Volatile private var boundRepo: LiveRepository? = null

    fun bindRepository(repo: LiveRepository) { boundRepo = repo }

    fun repository(context: Context): LiveRepository =
        boundRepo ?: LiveRepository(context.applicationContext).also { boundRepo = it }

    fun addListener(fn: (UpdateStatus) -> Unit) { listeners.addIfAbsent(fn) }
    fun removeListener(fn: (UpdateStatus) -> Unit) { listeners.remove(fn) }

    /**
     * 执行一次完整更新。
     *
     * @param ignoreAutoRefresh 手动触发时忽略“自动刷新开关”，但仍受 30min 节流。
     */
    fun refresh(context: Context, ignoreAutoRefresh: Boolean = false): UpdateStatus {
        SignalUpdateSettings.attach(context.applicationContext)
        synchronized(refreshLock) {
            if (refreshing) return status
            refreshing = true
        }
        val started = System.currentTimeMillis()
        try {
            if ((!ignoreAutoRefresh && !SignalUpdateSettings.autoRefresh) ||
                SignalUpdateSettings.throttled(started)
            ) {
                val skipped = UpdateStatus(
                    state = State.SKIPPED,
                    startedAt = started,
                    finishedAt = System.currentTimeMillis(),
                    message = "已跳过：未启用自动刷新，或距上次成功不足 30 分钟"
                )
                publish(skipped)
                return skipped
            }
            if (!networkAllowed(context)) {
                val skipped = UpdateStatus(
                    state = State.SKIPPED,
                    startedAt = started,
                    finishedAt = System.currentTimeMillis(),
                    message = "当前网络不满足刷新条件（仅 WiFi 或离线）"
                )
                publish(skipped)
                return skipped
            }
            SignalUpdateSettings.lastAttemptAt = started
            publish(UpdateStatus(state = State.RUNNING, startedAt = started, message = "正在更新信号源"))

            // ===== A 步骤：订阅源自动刷新 =====
            val repo = repository(context)
            var sources = SourceResult()
            var rolledBack = false
            // L1：快照必须在 try 外声明，保证 catch 分支也能拿到刷新前的 store 做回滚
            var before = ""
            try {
                before = repo.snapshotStore()
                sources = repo.refreshAllSubscriptions()

                // 频道改名会改变 digest(名称) 型 ID，收藏需按 流摘要 → 规范化名称 继承
                val newChannels = repo.sourceList().flatMap { it.channels }.distinctBy { it.id }
                repo.replaceFavorites(
                    ChannelIdMigrator.migrate(newChannels, decodeFavorites(before), decodeChannels(before))
                )

                // 全部源失败 / 总频道归零：回滚到刷新前快照
                val total = repo.sourceList().sumOf { it.channels.size }
                if (total == 0 || (sources.fail > 0 && sources.ok == 0)) {
                    repo.restoreSnapshot(before)
                    rolledBack = true
                    sources = sources.copy(details = sources.details + "刷新异常，已回滚到刷新前状态")
                }
            } catch (t: Throwable) {
                if (before.isNotEmpty()) {
                    try { repo.restoreSnapshot(before) } catch (_: Throwable) {}
                }
                sources = SourceResult(
                    fail = 1,
                    details = listOf("订阅源刷新异常: ${t.message ?: t.javaClass.simpleName}，已回滚")
                )
                rolledBack = true
            }

            // ===== B 步骤：在线频道表刷新（地址为空则零网络）=====
            val catalogEnabled = SignalUpdateSettings.catalogUrl.isNotBlank()
            var catalog = CatalogResult(
                enabled = catalogEnabled,
                reason = if (catalogEnabled) "待刷新" else "未配置在线地址"
            )
            if (catalogEnabled) {
                try {
                    val online = YangshipinCatalog.refreshOnline(context)
                    catalog = CatalogResult(
                        enabled = true,
                        updated = online.updated,
                        version = online.version,
                        count = online.count,
                        reason = online.reason
                    )
                    if (online.updated) {
                        // 只更新 DEFAULT_SOURCE 元数据；正在播放的实例不重建
                        repo.refreshPlugin()
                    }
                } catch (t: Throwable) {
                    catalog = CatalogResult(enabled = true, reason = t.message ?: "在线表刷新异常")
                }
            }

            val state = when {
                rolledBack -> State.FAILED
                sources.fail > 0 && sources.ok > 0 -> State.PARTIAL
                sources.fail > 0 -> State.FAILED
                catalogEnabled && !catalog.updated && catalog.reason != "未配置在线地址" -> State.PARTIAL
                else -> State.SUCCESS
            }
            val finished = System.currentTimeMillis()
            val result = UpdateStatus(state, started, finished, sources, catalog, "")
            if (state == State.SUCCESS || state == State.PARTIAL) {
                SignalUpdateSettings.lastSuccessAt = finished
                SignalUpdateSettings.consecutiveFailures = 0
            } else {
                SignalUpdateSettings.consecutiveFailures += 1
            }
            SignalUpdateSettings.writeResult(result)
            publish(result)
            return result
        } catch (t: Throwable) {
            val finished = System.currentTimeMillis()
            val failed = UpdateStatus(
                state = State.FAILED,
                startedAt = started,
                finishedAt = finished,
                message = t.message ?: "更新失败"
            )
            SignalUpdateSettings.consecutiveFailures += 1
            SignalUpdateSettings.writeResult(failed)
            publish(failed)
            return failed
        } finally {
            synchronized(refreshLock) { refreshing = false }
        }
    }

    /**
     * 仅跑 B 的专用一次性路径，绕过 30min 节流（用户显式提交地址应立即生效）。
     * 由 SignalUpdateSettings.setCatalogUrl 在 IO 线程调用。
     */
    fun refreshCatalogNow(context: Context): UpdateStatus {
        SignalUpdateSettings.attach(context.applicationContext)
        val started = System.currentTimeMillis()
        val url = SignalUpdateSettings.catalogUrl
        val catalog: CatalogResult
        if (url.isBlank()) {
            YangshipinCatalog.invalidate()
            repository(context).refreshPlugin()
            catalog = CatalogResult(enabled = false, reason = "已清空地址，使用内置频道表")
        } else {
            val online = YangshipinCatalog.refreshOnline(context)
            catalog = CatalogResult(
                enabled = true,
                updated = online.updated,
                version = online.version,
                count = online.count,
                reason = online.reason
            )
            if (online.updated) repository(context).refreshPlugin()
        }
        val failed = url.isNotBlank() && !catalog.updated
        val result = UpdateStatus(
            state = if (failed) State.FAILED else State.SUCCESS,
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
            catalog = catalog,
            message = catalog.reason
        )
        if (failed) SignalUpdateSettings.consecutiveFailures += 1 else SignalUpdateSettings.consecutiveFailures = 0
        SignalUpdateSettings.writeResult(result)
        publish(result)
        return result
    }

    /** 配置变化后幂等重排 Worker；“仅手动”时取消周期任务。 */
    fun reconfigure(context: Context) {
        val app = context.applicationContext
        SignalUpdateSettings.attach(app)
        if (!SignalUpdateSettings.autoRefresh || SignalUpdateSettings.intervalHours <= 0) {
            WorkManager.getInstance(app).cancelUniqueWork(SignalRefreshWorker.UNIQUE_PERIODIC)
        } else {
            SignalRefreshWorker.enqueuePeriodic(
                app, SignalUpdateSettings.intervalHours, SignalUpdateSettings.wifiOnly
            )
        }
    }

    fun enable() { SignalUpdateSettings.autoRefresh = true }
    fun disable() { SignalUpdateSettings.autoRefresh = false }

    fun enqueueNow(context: Context) {
        SignalUpdateSettings.attach(context.applicationContext)
        SignalRefreshWorker.enqueueNow(context)
    }

    /**
     * 网络条件判定。WorkManager Constraints 是第一道门；这里再判一次以覆盖冷启动一次性任务。
     * 无法判定（无权限 / 无网络连接对象）时放行，避免误判导致漏刷。
     */
    private fun networkAllowed(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return true
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = manager.activeNetwork ?: return true
                val caps = manager.getNetworkCapabilities(network) ?: return true
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
                if (!SignalUpdateSettings.wifiOnly) return true
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            } else {
                @Suppress("DEPRECATION")
                val info = manager.activeNetworkInfo ?: return true
                if (!info.isConnected) return false
                if (!SignalUpdateSettings.wifiOnly) return true
                @Suppress("DEPRECATION")
                info.type == android.net.ConnectivityManager.TYPE_WIFI ||
                    @Suppress("DEPRECATION") info.type == android.net.ConnectivityManager.TYPE_ETHERNET
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun decodeFavorites(json: String): Set<String> = try {
        val arr = JSONObject(json).optJSONArray("favorites") ?: return emptySet()
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun decodeChannels(json: String): List<LiveChannel> = try {
        val sources = JSONObject(json).optJSONArray("sources") ?: return emptyList()
        val result = mutableListOf<LiveChannel>()
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            val channels = source.optJSONArray("channels") ?: continue
            for (j in 0 until channels.length()) {
                val channel = channels.optJSONObject(j) ?: continue
                val streams = channel.optJSONArray("streams") ?: continue
                val urls = (0 until streams.length()).map { streams.optString(it) }.filter { it.isNotEmpty() }
                if (urls.isNotEmpty()) {
                    result.add(
                        LiveChannel(
                            channel.optString("id"),
                            channel.optString("name"),
                            channel.optString("group"),
                            urls
                        )
                    )
                }
            }
        }
        result
    } catch (_: Exception) {
        emptyList()
    }

    private fun publish(s: UpdateStatus) {
        status = s
        SignalUpdateSettings.notifyStatus(s)
        listeners.forEach { fn -> try { fn(s) } catch (_: Throwable) { } }
    }
}
