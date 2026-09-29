package com.example.sleeptrackersimulator.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorSampleTest {
    @Test
    fun `magnitude uses all three axes`() {
        val sample = SensorSample(x = 3f, y = 4f, z = 12f, timestampNanos = 1L)

        assertEquals(13f, sample.magnitude, 0.001f)
    }
}
