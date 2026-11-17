package com.tvbridge.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** T05 验收：落盘恢复、URL 一致性、损坏回退、地址空只用 assets。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YangshipinCatalogOnlineTest {
    private lateinit var context: Context
    private val onlinePid = "999999999"
    private val version = 20260116

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // attach() 是 first-wins：清掉前一个测试类遗留的静态 appContext，避免跨类串扰
        SignalUpdateSettings::class.java.getDeclaredField("appContext").apply {
            isAccessible = true
            set(SignalUpdateSettings, null)
        }
        SignalUpdateSettings.attach(context)
        context.getSharedPreferences("signal-update-settings", 0).edit().clear().commit()
        context.filesDir.resolve("yangshipin-channels.online.json").delete()
        YangshipinCatalog.invalidate()
    }

    @org.junit.After
    fun tearDown() {
        // 清理在线表内存/磁盘/ pref，避免污染同 JVM 内其他测试类
        YangshipinCatalog.invalidate()
        context.getSharedPreferences("signal-update-settings", 0).edit().clear().commit()
        context.filesDir.resolve("yangshipin-channels.online.json").delete()
    }

    private fun onlineJson(pid: String = onlinePid): String {
        val channels = listOf(LiveChannel("yangshipin-$pid", "在线测试频道", "在线组", listOf("one-plugin://live/$pid")))
        val array = JSONArray().put(
            JSONObject().put("pid", pid).put("name", "在线测试频道").put("group", "在线组")
        )
        return JSONObject()
            .put("schema", 1)
            .put("version", version)
            .put("updatedAt", "2026-01-16T10:00:00Z")
            .put("channels", array)
            .put("checksum", OnlineCatalogParser.checksum(version, "2026-01-16T10:00:00Z", channels))
            .toString()
    }

    private fun writeOnlineFile(text: String) {
        val file = AtomicFile(java.io.File(context.filesDir, "yangshipin-channels.online.json"))
        val out = file.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun configureUrl(url: String, atCache: String = url) {
        context.getSharedPreferences("signal-update-settings", 0).edit()
            .putString("catalog_url", url)
            .putString("catalog_url_at_cache", atCache)
            .putInt("catalog_version", if (url.isBlank()) 0 else version)
            .commit()
    }

    @Test
    fun loadFromDiskRestoresOnlineTableForFirstFrame() {
        writeOnlineFile(onlineJson())
        configureUrl("https://example.test/catalog.json")

        YangshipinCatalog.loadFromDisk(context)

        assertTrue("在线频道应进入当前生效表", YangshipinCatalog.current(context.assets)
            .any { it.id == "yangshipin-$onlinePid" })
        assertEquals(version, SignalUpdateSettings.catalogVersion)
    }

    @Test
    fun changedUrlDoesNotResurrectStaleCache() {
        writeOnlineFile(onlineJson())
        configureUrl("https://example.test/new.json", atCache = "https://example.test/old.json")

        YangshipinCatalog.loadFromDisk(context)

        assertFalse("来源 URL 不一致时不得载入旧表", YangshipinCatalog.current(context.assets)
            .any { it.id == "yangshipin-$onlinePid" })
    }

    @Test
    fun blankUrlKeepsAssetsOnlyAndSkipsDiskEntirely() {
        writeOnlineFile(onlineJson())
        configureUrl("")

        YangshipinCatalog.loadFromDisk(context)

        assertFalse(YangshipinCatalog.current(context.assets).any { it.id == "yangshipin-$onlinePid" })
        assertTrue("assets 兜底必须非空", YangshipinCatalog.channels(context.assets).isNotEmpty())
    }

    @Test
    fun corruptDiskCacheFallsBackSilently() {
        writeOnlineFile("{broken")
        configureUrl("https://example.test/catalog.json")

        YangshipinCatalog.loadFromDisk(context)

        assertFalse(YangshipinCatalog.current(context.assets).any { it.id == "yangshipin-$onlinePid" })
        assertTrue(YangshipinCatalog.channels(context.assets).isNotEmpty())
    }

    @Test
    fun refreshOnlineWithoutUrlMakesNoNetworkCall() {
        configureUrl("")
        val result = YangshipinCatalog.refreshOnline(context)
        assertFalse(result.success)
        assertEquals("未配置在线地址", result.reason)
    }
}
