package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** T04（可选）：默认配置、地址留空语义与状态区数据解析。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateSettingsUiTest {
    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        SignalUpdateSettings.attach(app)
        app.getSharedPreferences(SignalUpdateSettings.PREF_NAME, 0).edit().clear().commit()
    }

    @Test
    fun defaultsAreAutoSixHoursWifiOnlyAndEmptyCatalogUrl() {
        assertTrue(SignalUpdateSettings.autoRefresh)
        assertEquals(6, SignalUpdateSettings.intervalHours)
        assertTrue(SignalUpdateSettings.wifiOnly)
        assertEquals("", SignalUpdateSettings.catalogUrl)
        assertEquals(0, SignalUpdateSettings.catalogVersion)
        assertFalse(SignalUpdateSettings.throttled(System.currentTimeMillis()))
    }

    @Test
    fun statusJsonRoundTripFeedsTheStatusArea() {
        val result = UpdateStatus(
            state = State.PARTIAL,
            startedAt = 1000L,
            finishedAt = 9000L,
            subscriptions = SourceResult(ok = 3, fail = 1, added = 2, details = listOf("一条失败")),
            catalog = CatalogResult(enabled = false, reason = "未配置在线地址"),
            message = ""
        )
        SignalUpdateSettings.writeResult(result)
        val restored = SignalUpdateSettings.status()
        assertEquals(State.PARTIAL, restored.state)
        assertEquals(1000L, restored.startedAt)
        assertEquals(3, restored.subscriptions.ok)
        assertEquals(1, restored.subscriptions.fail)
        assertEquals(2, restored.subscriptions.added)
        assertEquals(listOf("一条失败"), restored.subscriptions.details)
        assertFalse(restored.catalog.enabled)
        assertEquals("未配置在线地址", restored.catalog.reason)
    }

    @Test
    fun throttleSkipsOnlyWithinThirtyMinutesAfterLastSuccess() {
        val now = 1_700_000_000_000L
        SignalUpdateSettings.lastSuccessAt = now - 29 * 60_000L
        assertTrue(SignalUpdateSettings.throttled(now))
        SignalUpdateSettings.lastSuccessAt = now - 31 * 60_000L
        assertFalse(SignalUpdateSettings.throttled(now))
    }

    @Test
    fun corruptStatusJsonFallsBackToIdle() {
        SignalUpdateSettings.lastResultJson = "{not-json"
        assertEquals(State.IDLE, SignalUpdateSettings.status().state)
    }
}
