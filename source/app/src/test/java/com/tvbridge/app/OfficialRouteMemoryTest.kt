package com.tvbridge.app

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** T03 验收：线路记忆的读写、哈希 key、200 条上限裁剪。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfficialRouteMemoryTest {
    private val pid = "600001859"
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences("official-routes", 0)
        prefs.edit().clear().commit()
        // attach() 是 first-wins：清掉前一个测试类遗留的静态 appContext，避免跨类串扰
        OfficialRouteMemory::class.java.getDeclaredField("appContext").apply {
            isAccessible = true
            set(OfficialRouteMemory, null)
        }
        OfficialRouteMemory.attach(app)
    }

    @Test
    fun rememberAndReadPreferCctvWithHashedKey() {
        assertFalse(OfficialRouteMemory.preferred(pid))
        OfficialRouteMemory.remember(pid, true)
        assertTrue(OfficialRouteMemory.preferred(pid))
        val hashed = PlaylistParser.digest(pid)
        assertTrue("key 必须是 pid 摘要", prefs.contains(hashed))
        assertFalse("原始 pid 不得直接作为 key", prefs.contains(pid))
        assertEquals(1, JSONArray(prefs.getString("_order", "[]") ?: "[]").length())

        OfficialRouteMemory.remember(pid, false)
        assertFalse(OfficialRouteMemory.preferred(pid))
        assertEquals("重复 remember 不应增长 order", 1, JSONArray(prefs.getString("_order", "[]") ?: "[]").length())

        OfficialRouteMemory.clear(pid)
        assertFalse(OfficialRouteMemory.preferred(pid))
        assertFalse(prefs.contains(hashed))
    }

    @Test
    fun orderIsTrimmedToTwoHundredEntries() {
        repeat(205) { index -> OfficialRouteMemory.remember("test-pid-$index", index % 2 == 0) }
        val order = JSONArray(prefs.getString("_order", "[]") ?: "[]")
        assertEquals(200, order.length())
        assertTrue("最新条目必须保留", OfficialRouteMemory.preferred("test-pid-204"))
        assertFalse("最早条目必须被裁剪", prefs.contains(PlaylistParser.digest("test-pid-0")))
    }
}
