package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** T03 验收：写两类 pid 的缓存后 invalidatePid 只清目标 pid，其余保留。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamCacheInvalidationTest {
    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        prefs = app.getSharedPreferences("official_stream_cache_v3", 0)
        prefs.edit().clear().commit()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("url_600001859_fhd", "https://ysp.test/a.m3u8")
            .putLong("time_600001859_fhd", now)
            .putString("url_600001859_fhd_cctv", "https://cdn.test/a.m3u8")
            .putLong("time_600001859_fhd_cctv", now)
            .putString("url_600001800_fhd", "https://ysp.test/b.m3u8")
            .putLong("time_600001800_fhd", now)
            .commit()
    }

    @Test
    fun invalidatePidClearsAllQualitiesAndRoutesForThatPidOnly() {
        val resolver = YangshipinStreamResolver(RuntimeEnvironment.getApplication())
        try {
            resolver.invalidatePid("600001859")
            assertNull(prefs.getString("url_600001859_fhd", null))
            assertNull(prefs.getString("time_600001859_fhd", null))
            assertNull(prefs.getString("url_600001859_fhd_cctv", null))
            assertNull(prefs.getString("time_600001859_fhd_cctv", null))
            assertEquals("https://ysp.test/b.m3u8", prefs.getString("url_600001800_fhd", null))
            assertTrue("其余 pid 的时间戳必须保留", prefs.getLong("time_600001800_fhd", 0L) > 0L)
        } finally {
            resolver.release()
        }
    }

    @Test
    fun obsoletePidAliasIsCanonicalizedBeforeInvalidation() {
        val resolver = YangshipinStreamResolver(RuntimeEnvironment.getApplication())
        try {
            prefs.edit()
                .putString("url_600004092_fhd", "https://ysp.test/c7.m3u8")
                .putLong("time_600004092_fhd", System.currentTimeMillis())
                .commit()
            resolver.invalidatePid("600000492")
            assertNull(prefs.getString("url_600004092_fhd", null))
        } finally {
            resolver.release()
        }
    }

    @Test
    fun invalidateSingleKeyAndInvalidateAllAreScopedCorrectly() {
        val resolver = YangshipinStreamResolver(RuntimeEnvironment.getApplication())
        try {
            resolver.invalidate("600001800_fhd")
            assertNull(prefs.getString("url_600001800_fhd", null))
            assertNotNull(prefs.getString("url_600001859_fhd", null))

            resolver.invalidateAll()
            assertTrue(prefs.all.isEmpty())
        } finally {
            resolver.release()
        }
    }
}
