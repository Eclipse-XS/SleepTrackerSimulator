package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementPreprocessorTest {
    @Test
    fun `preprocessing removes constant orientation and preserves contract`() {
        val samples = List(20) { SensorSample(1f, -2f, 9.81f, (it + 1).toLong()) }
        val result = MovementPreprocessor.transform(samples)
        assertEquals(1, result.size)
        assertEquals(20, result[0].size)
        assertEquals(3, result[0][0].size)
        assertTrue(result[0].flatMap { it.asList() }.all { kotlin.math.abs(it) < 0.0001f })
    }

    @Test
    fun `preprocessing clips centered outliers`() {
        val samples = MutableList(20) { SensorSample(0f, 0f, 9.81f, (it + 1).toLong()) }
        samples[19] = SensorSample(100f, -100f, 9.81f, 20L)
        val output = MovementPreprocessor.transform(samples)[0][19]
        assertEquals(1f, output[0], 0f)
        assertEquals(-1f, output[1], 0f)
    }
}
