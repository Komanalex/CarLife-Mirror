package com.carlifemirror.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalThrottleTest {
    @Test
    fun levelsFollowStatusAndHeadroom() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.NORMAL, t.levelFor(0, 0.80f))
        assertEquals(ThermalLevel.NORMAL, t.levelFor(1, Float.NaN))
        assertEquals(ThermalLevel.WARM, t.levelFor(0, 0.85f))
        assertEquals(ThermalLevel.WARM, t.levelFor(2, 0.0f))
        assertEquals(ThermalLevel.HOT, t.levelFor(3, 0.0f))
    }

    @Test
    fun capsMatchHeadswap() {
        val t = ThermalThrottle()
        assertEquals(30, t.capFps(30))
        assertEquals(8_000_000, t.capBitrate(8_000_000))

        t.update(0L, 0, 0.85f)
        assertEquals(20, t.capFps(30))
        assertEquals(6_000_000, t.capBitrate(8_000_000))

        t.update(1_000L, 3, 0.0f)
        assertEquals(15, t.capFps(30))
        assertEquals(4_000_000, t.capBitrate(8_000_000))
    }

    @Test
    fun recoveryNeedsSixtySecondsOfCalm() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.WARM, t.update(0L, 0, 0.85f))
        // Ещё тепло: попытка вернуться в NORMAL откладывается.
        assertNull(t.update(1L, 0, 0.70f))
        assertNull(t.update(60_000L, 0, 0.70f))
        assertEquals(ThermalLevel.NORMAL, t.update(61_000L, 0, 0.70f))
        assertEquals(30, t.capFps(30))
    }

    @Test
    fun hotRecoversOneStepAtATime() {
        val t = ThermalThrottle()
        assertEquals(ThermalLevel.HOT, t.update(0L, 3, 0.0f))
        assertNull(t.update(60_000L, 0, 0.70f))
        assertEquals(ThermalLevel.WARM, t.update(120_000L, 0, 0.70f))
        assertNull(t.update(120_001L, 0, 0.70f))
        assertEquals(ThermalLevel.NORMAL, t.update(180_000L, 0, 0.70f))
    }
}
