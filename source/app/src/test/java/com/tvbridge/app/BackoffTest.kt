package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test

/** T03 验收：退避序列 1200 → 2400 → 4800 → 8000 封顶，且带随机抖动。 */
class BackoffTest {
    @Test
    fun backoffGrowsExponentiallyAndCapsAtEightSeconds() {
        val values = (1..10).map { PlaybackAttempts.backoffDelayMs(it) }
        assertTrue("第 1 次约 1200ms，实际=${values[0]}", values[0] in 1200L..1399L)
        assertTrue("第 2 次约 2400ms，实际=${values[1]}", values[1] in 2400L..2599L)
        assertTrue("第 3 次约 4800ms，实际=${values[2]}", values[2] in 4800L..4999L)
        assertEquals("第 4 次起封顶 8000ms", 8000L, values[3])
        assertTrue(values.drop(3).all { it == 8000L })
        assertTrue(values.all { it <= 8000L })
        assertTrue(values[0] < values[1] && values[1] < values[2] && values[2] < values[3])
    }

    @Test
    fun backoffHasJitterWithinBound() {
        val samples = (0..40).map { PlaybackAttempts.backoffDelayMs(1) }
        assertTrue("应观察到抖动，实际=${samples.toSet()}", samples.toSet().size > 1)
        assertTrue(samples.all { it in 1200L..1399L })
        assertTrue("retry<=0 归一为首档", PlaybackAttempts.backoffDelayMs(0) in 1200L..1399L)
    }
}
