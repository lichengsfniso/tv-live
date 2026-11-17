package com.tvbridge.app

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置央视频道表门面。
 *
 * assets 表作为永远可用的兜底；在线表（地址可配置，默认空）经严格校验后原子落盘，
 * 冷启动由 loadFromDisk 同步恢复。任何校验失败都保留 lastGood 或回退 assets，
 * 绝不把空列表写进缓存。网络刷新只由 UpdateCoordinator 调用，read() 永不联网。
 */
object YangshipinCatalog {
    const val SOURCE = "https://www.yangshipin.cn/tv/home"

    fun resolve(url: String): String = if (LiveCatalog.isPage(url)) {
        SOURCE + "?pid=" + Uri.parse(url).lastPathSegment
    } else url

    fun isPage(url: String): Boolean = try {
        val uri = Uri.parse(url)
        uri.scheme == "https" && uri.host == "www.yangshipin.cn" && uri.userInfo == null &&
            uri.port in listOf(-1, 443) && uri.path == "/tv/home" &&
            uri.getQueryParameter("pid")?.matches(Regex("[0-9]{6,12}")) == true
    } catch (_: Exception) {
        false
    }

    /** 在线刷新结果（避免与 LiveCatalog.Result 命名冲突）。 */
    data class OnlineResult(
        val success: Boolean = false,
        val updated: Boolean = false,
        val version: Int = 0,
        val count: Int = 0,
        val reason: String = ""
    )

    private const val ONLINE_FILE = "yangshipin-channels.online.json"
    private const val MAX_ONLINE_BYTES = 2 * 1024 * 1024

    /** 前 20 台固定顺序；在线表新增频道按原顺序追加到末尾。 */
    private val ORDER = listOf(
        "600001859", "600001800", "600001801", "600001814", "600001818",
        "600001817", "600108442", "600000492", "600001803", "600004078",
        "600001805", "600001806", "600001807", "600001811", "600001809",
        "600001815", "600098637", "600001810", "600002264", "600156816"
    )

    @Volatile private var assetCached: List<LiveChannel>? = null
    @Volatile private var onlineCached: List<LiveChannel>? = null
    @Volatile private var onlineVersion: Int = 0

    /** 同时清空 assets / 在线内存缓存；磁盘 lastGood 保留以便同地址秒级恢复。 */
    fun invalidate() {
        assetCached = null
        onlineCached = null
        onlineVersion = 0
    }

    /** assets 表解析（逻辑与改造前逐字节一致）。 */
    fun channels(assets: AssetManager): List<LiveChannel> {
        assetCached?.let { return it }
        synchronized(this) {
            assetCached?.let { return it }
            val parsed = parseAssets(assets)
            assetCached = parsed
            return parsed
        }
    }

    /** 当前生效表：在线缓存优先，未加载 / 已失效时用 assets。 */
    fun current(assets: AssetManager): List<LiveChannel> = onlineCached ?: channels(assets)

    private fun parseAssets(assets: AssetManager): List<LiveChannel> {
        val groups = JSONArray(assets.open("yangshipin-channels.json").bufferedReader().use { it.readText() })
        val result = buildList {
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val list = group.getJSONArray("channels")
                for (j in 0 until list.length()) {
                    val item = list.getJSONObject(j)
                    val pid = item.getString("pid")
                    if (pid.matches(Regex("[0-9]{6,12}"))) {
                        add(LiveChannel("yangshipin-$pid", item.getString("name"),
                            group.getString("group_name"), listOf("one-plugin://live/$pid")))
                    }
                }
            }
        }
        return sortChannels(result)
    }

    private fun sortChannels(source: List<LiveChannel>): List<LiveChannel> = source.sortedBy { channel ->
        val pid = channel.id.removePrefix("yangshipin-")
        val index = ORDER.indexOf(pid)
        if (index >= 0) index else 100 + source.indexOf(channel)
    }

    /**
     * 冷启动同步恢复上次在线表（无网络）。
     * 三层保险：地址为空 → no-op；来源 URL 与缓存不一致 → no-op；报文损坏 → 静默忽略。
     */
    fun loadFromDisk(context: Context) {
        val url = SignalUpdateSettings.catalogUrl
        if (url.isBlank()) return
        if (SignalUpdateSettings.catalogUrlAtCache != url) return
        try {
            val file = AtomicFile(java.io.File(context.filesDir, ONLINE_FILE))
            if (!file.baseFile.exists()) return
            val parsed = OnlineCatalogParser.parse(String(file.readFully(), Charsets.UTF_8))
            require(parsed.version > 0 && parsed.channels.isNotEmpty())
            synchronized(this) {
                onlineCached = sortChannels(parsed.channels)
                onlineVersion = parsed.version
            }
            SignalUpdateSettings.catalogVersion = parsed.version
        } catch (_: Exception) {
            // 校验失败：保持 onlineCached 为 null，current() 自动回退 assets
        }
    }

    /**
     * 联网拉取在线表。地址为空时零网络请求。
     * 校验 / 版本单调通过后原子写盘；失败一律不写空缓存。
     * 调用方负责把本方法投递到 IO 线程。
     */
    fun refreshOnline(context: Context): OnlineResult {
        val url = SignalUpdateSettings.catalogUrl
        if (url.isBlank()) return OnlineResult(reason = "未配置在线地址")

        // §4.3.5：缓存属于上一个地址时不继承版本、不载入旧表，等新地址拉取成功再写盘
        val atCache = SignalUpdateSettings.catalogUrlAtCache
        if (atCache.isNotBlank() && atCache != url) {
            synchronized(this) {
                onlineCached = null
                onlineVersion = 0
            }
        }

        var activeConnection: HttpURLConnection? = null
        try {
            var next = url
            var body: String? = null
            for (attempt in 0..3) {
                val connection = URL(next).openConnection() as HttpURLConnection
                activeConnection = connection
                try {
                    LegacyTls.configure(connection)
                    connection.connectTimeout = 8000
                    connection.readTimeout = 10000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "TVBridge/0.2 AndroidTV")
                    if (connection.responseCode in 300..399) {
                        require(attempt < 3) { "重定向过多" }
                        val location = connection.getHeaderField("Location") ?: error("重定向地址缺失")
                        next = PlaylistParser.httpUrl(URL(URL(next), location).toString())
                            ?: error("频道表跳转到不支持的地址")
                        continue
                    }
                    require(connection.responseCode in 200..299) { "频道表更新失败 (${connection.responseCode})" }
                    require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= MAX_ONLINE_BYTES) {
                        "频道表超过 2 MB"
                    }
                    body = connection.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= MAX_ONLINE_BYTES) { "频道表超过 2 MB" }
                            out.write(buffer, 0, n)
                        }
                        out.toString("UTF-8").removePrefix("\uFEFF")
                    }
                    break
                } finally {
                    connection.disconnect()
                    activeConnection = null
                }
            }
            val text = body ?: return OnlineResult(reason = "频道表读取失败")
            val parsed = OnlineCatalogParser.parse(text)
            if (parsed.version <= onlineVersion || parsed.version <= SignalUpdateSettings.catalogVersion) {
                return OnlineResult(reason = "在线版本未高于当前版本，已保留原表")
            }

            val sorted = sortChannels(parsed.channels)
            val file = AtomicFile(java.io.File(context.filesDir, ONLINE_FILE))
            val output = file.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }

            synchronized(this) {
                onlineCached = sorted
                onlineVersion = parsed.version
            }
            SignalUpdateSettings.catalogVersion = parsed.version
            SignalUpdateSettings.catalogUrlAtCache = url
            return OnlineResult(true, true, parsed.version, sorted.size, "")
        } catch (e: Exception) {
            return OnlineResult(reason = LegacyTls.message(e))
        } finally {
            try { activeConnection?.disconnect() } catch (_: Throwable) { }
        }
    }
}
