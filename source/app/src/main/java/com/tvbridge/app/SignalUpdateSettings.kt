package com.tvbridge.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 信号源自动更新的唯一配置 / 状态源。
 *
 * 存储于 SharedPreferences("signal-update-settings")。所有配置项写入后都会触发
 * [UpdateCoordinator.reconfigure] 幂等重排 Worker；状态类字段（lastSuccessAt 等）
 * 仅写入，不重排，避免无意义的 WorkManager 操作。
 *
 * 使用前提：任何访问前需由 LiveActivity.onCreate / SignalRefreshWorker.doWork 调用
 * [attach] 注入 Application Context。
 */
object SignalUpdateSettings {
    const val PREF_NAME = "signal-update-settings"

    /** 默认空字符串 = 不启用在线频道表，只用 assets；代码中不出现任何具体 URL 字面量。 */
    const val DEFAULT_CATALOG_URL = ""
    const val THROTTLE_WINDOW_MS = 30 * 60_000L

    private const val KEY_AUTO_REFRESH = "auto_refresh"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_CATALOG_URL = "catalog_url"
    private const val KEY_CATALOG_URL_AT_CACHE = "catalog_url_at_cache"
    private const val KEY_CATALOG_VERSION = "catalog_version"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
    private const val KEY_LAST_RESULT_JSON = "last_result_json"

    @Volatile private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<(UpdateStatus) -> Unit>()

    /** 注入应用上下文；可重复调用，仅第一次生效。 */
    fun attach(context: Context) {
        val application = context.applicationContext
        if (appContext == null) appContext = application
    }

    private fun store(context: Context? = null): SharedPreferences {
        val ctx = context?.applicationContext ?: appContext
            ?: throw IllegalStateException("SignalUpdateSettings.attach(context) 尚未调用")
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun reconfigure() {
        appContext?.let { UpdateCoordinator.reconfigure(it) }
    }

    // ---------------- 配置项 ----------------

    /** 是否启用自动刷新，默认 true。 */
    var autoRefresh: Boolean
        get() = store().getBoolean(KEY_AUTO_REFRESH, true)
        set(value) {
            store().edit().putBoolean(KEY_AUTO_REFRESH, value).apply()
            reconfigure()
        }

    /** 6 / 12 / 24 小时；0 = 仅手动。非法值归一为 0。 */
    var intervalHours: Int
        get() = store().getInt(KEY_INTERVAL_HOURS, 6)
        set(value) {
            val normalized = if (value == 6 || value == 12 || value == 24) value else 0
            store().edit().putInt(KEY_INTERVAL_HOURS, normalized).apply()
            reconfigure()
        }

    /** 仅 WiFi（含以太网）自动刷新，默认 true。 */
    var wifiOnly: Boolean
        get() = store().getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            store().edit().putBoolean(KEY_WIFI_ONLY, value).apply()
            reconfigure()
        }

    /** 在线频道表地址；默认空字符串。唯一写入口为 [setCatalogUrl]。 */
    val catalogUrl: String
        get() = store().getString(KEY_CATALOG_URL, null) ?: runCatching { BuildConfig.CATALOG_URL }.getOrDefault(DEFAULT_CATALOG_URL)

    /** 上次成功写盘的在线表所用地址，用于 URL 变更判定缓存是否过期。 */
    var catalogUrlAtCache: String
        get() = store().getString(KEY_CATALOG_URL_AT_CACHE, "") ?: ""
        set(value) { store().edit().putString(KEY_CATALOG_URL_AT_CACHE, value).apply() }

    /** 当前在线表版本号，防降级。 */
    var catalogVersion: Int
        get() = store().getInt(KEY_CATALOG_VERSION, 0)
        set(value) { store().edit().putInt(KEY_CATALOG_VERSION, value).apply() }

    // ---------------- 状态字段 ----------------

    var lastSuccessAt: Long
        get() = store().getLong(KEY_LAST_SUCCESS_AT, 0L)
        set(value) { store().edit().putLong(KEY_LAST_SUCCESS_AT, value).apply() }

    var lastAttemptAt: Long
        get() = store().getLong(KEY_LAST_ATTEMPT_AT, 0L)
        set(value) { store().edit().putLong(KEY_LAST_ATTEMPT_AT, value).apply() }

    var consecutiveFailures: Int
        get() = store().getInt(KEY_CONSECUTIVE_FAILURES, 0)
        set(value) { store().edit().putInt(KEY_CONSECUTIVE_FAILURES, value).apply() }

    var lastResultJson: String
        get() = store().getString(KEY_LAST_RESULT_JSON, "") ?: ""
        set(value) { store().edit().putString(KEY_LAST_RESULT_JSON, value).apply() }

    /**
     * 在线地址唯一写入口（PRD §4.3.1）。
     *
     * 变化后：清在线缓存 + 清版本与来源标记 → 幂等重排周期任务 →
     * 立即（IO 线程）跑一次仅 B 的刷新。留空则完全跳过网络。
     */
    fun setCatalogUrl(value: String) {
        val ctx = appContext ?: throw IllegalStateException("SignalUpdateSettings.attach(context) 尚未调用")
        val next = value.trim()
        if (next == catalogUrl) return
        store().edit().putString(KEY_CATALOG_URL, next).apply()

        // 旧缓存属于上一个地址，绝不允许换地址后继续用旧表
        YangshipinCatalog.invalidate()
        store().edit()
            .putInt(KEY_CATALOG_VERSION, 0)
            .putString(KEY_CATALOG_URL_AT_CACHE, "")
            .apply()

        UpdateCoordinator.reconfigure(ctx)
        // L2：只有填入了地址才入队 / 立即拉取；清空地址仅保留 invalidate + reconfigure，
        // Coordinator 的 B 步骤会因 catalogUrl 空而直接跳过，全程零 B 网络请求
        if (next.isNotBlank()) {
            SignalRefreshWorker.enqueueNow(ctx)
            // 用户显式提交应立即生效，绕过 30min 节流；网络统一在 IO 线程
            AppThreads.onIo { UpdateCoordinator.refreshCatalogNow(ctx) }
        }
    }

    /** 距上次成功不足 [windowMs] 时返回 true；从未成功过则不节流。 */
    fun throttled(now: Long = System.currentTimeMillis(), windowMs: Long = THROTTLE_WINDOW_MS): Boolean {
        val last = lastSuccessAt
        val elapsed = now - last
        return last > 0L && elapsed in 0L until windowMs
    }

    /** 读取设置页状态；JSON 损坏时安全返回 IDLE。 */
    fun status(): UpdateStatus = try {
        val json = JSONObject(lastResultJson.ifBlank { "{}" })
        UpdateStatus(
            state = runCatching { State.valueOf(json.optString("state", State.IDLE.name)) }
                .getOrDefault(State.IDLE),
            startedAt = json.optLong("startedAt", 0L),
            finishedAt = json.optLong("finishedAt", 0L),
            subscriptions = json.optJSONObject("subscriptions")?.let { o ->
                SourceResult(
                    ok = o.optInt("ok", 0),
                    fail = o.optInt("fail", 0),
                    added = o.optInt("added", 0),
                    removed = o.optInt("removed", 0),
                    details = o.optJSONArray("details")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    } ?: emptyList()
                )
            } ?: SourceResult(),
            catalog = json.optJSONObject("catalog")?.let { o ->
                CatalogResult(
                    enabled = o.optBoolean("enabled", false),
                    updated = o.optBoolean("updated", false),
                    version = o.optInt("version", 0),
                    count = o.optInt("count", 0),
                    reason = o.optString("reason", "")
                )
            } ?: CatalogResult(),
            message = json.optString("message", "")
        )
    } catch (_: Exception) {
        UpdateStatus()
    }

    /** 按架构 §4.5 的 JSON 形态落盘，供设置页展示。 */
    fun writeResult(s: UpdateStatus) {
        val subs = JSONObject()
            .put("ok", s.subscriptions.ok)
            .put("fail", s.subscriptions.fail)
            .put("added", s.subscriptions.added)
            .put("removed", s.subscriptions.removed)
            .put("details", JSONArray(s.subscriptions.details))
        val cat = JSONObject()
            .put("enabled", s.catalog.enabled)
            .put("updated", s.catalog.updated)
            .put("version", s.catalog.version)
            .put("count", s.catalog.count)
            .put("reason", s.catalog.reason)
        lastResultJson = JSONObject()
            .put("state", s.state.name)
            .put("startedAt", s.startedAt)
            .put("finishedAt", s.finishedAt)
            .put("subscriptions", subs)
            .put("catalog", cat)
            .put("message", s.message)
            .toString()
    }

    fun addListener(fn: (UpdateStatus) -> Unit) { listeners.addIfAbsent(fn) }
    fun removeListener(fn: (UpdateStatus) -> Unit) { listeners.remove(fn) }
    fun notifyStatus(s: UpdateStatus) {
        listeners.forEach { fn -> try { fn(s) } catch (_: Throwable) { } }
    }
}
