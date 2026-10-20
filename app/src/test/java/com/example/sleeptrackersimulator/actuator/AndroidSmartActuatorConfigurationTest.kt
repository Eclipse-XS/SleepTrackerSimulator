package com.example.sleeptrackersimulator.actuator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSmartActuatorConfigurationTest {
    @Test
    fun `persistent waveform starts immediately and repeats from vibrating segment`() {
        val timings = AndroidSmartActuator.PERSISTENT_TIMINGS_MS
        val repeatIndex = AndroidSmartActuator.PERSISTENT_REPEAT_INDEX

        assertArrayEquals(longArrayOf(0L, 300L, 700L), timings)
        assertEquals(1, repeatIndex)
        assertTrue(repeatIndex in timings.indices)
        assertTrue(repeatIndex % 2 == 1)
    }

    @Test
    fun `one shot duration is positive`() {
        assertTrue(AndroidSmartActuator.PULSE_DURATION_MS > 0L)
    }
}
