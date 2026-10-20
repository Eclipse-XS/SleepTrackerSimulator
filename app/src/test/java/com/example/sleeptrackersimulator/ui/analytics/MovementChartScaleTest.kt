package com.example.sleeptrackersimulator.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementChartScaleTest {
    @Test fun `empty data has no chart scale`() = assertNull(scale())

    @Test fun `zero values use base scale`() = assertScale(3.0, 0f, 0f, 0f)
    @Test fun `tiny values use base scale`() = assertScale(3.0, .01f, .02f, .04f)
    @Test fun `normal values use base scale`() = assertScale(3.0, .5f, 1.2f, 2.6f)
    @Test fun `base boundary stays at three`() = assertScale(3.0, 0f, 3f)
    @Test fun `value above base expands to readable boundary`() = assertScale(4.0, 0f, 3.1f)
    @Test fun `value 4 point 8 is covered`() = assertScale(5.0, 0f, 4.8f)
    @Test fun `value 8 point 7 is covered`() = assertScale(10.0, 0f, 8.7f)
    @Test fun `large peak is never clipped`() {
        val scale = requireNotNull(scale(0f, 27.3f))
        assertTrue(scale.maxY >= 27.3)
    }

    @Test fun `returning current window to normal values restores base scale`() {
        assertEquals(10.0, requireNotNull(scale(8.7f)).maxY, 0.0)
        assertEquals(3.0, requireNotNull(scale(.4f, 1.2f)).maxY, 0.0)
    }

    @Test fun `labels are even unique and include both boundaries`() {
        val scale = requireNotNull(scale(0f, 3f))
        assertEquals(listOf(0.0, .5, 1.0, 1.5, 2.0, 2.5, 3.0), scale.labels)
        assertEquals(scale.labels.size, scale.labels.distinct().size)
        assertEquals(scale.labels.size, scale.labels.map(::formatAxisValue).distinct().size)
        assertTrue(scale.labels.zipWithNext().all { (a, b) -> b - a == scale.tickStep })
    }

    @Test fun `single measurement and coordinates remain finite`() {
        val scale = requireNotNull(scale(0f))
        assertTrue(scale.normalizedY(0.0).isFinite())
        assertTrue(scale.normalizedY(Double.POSITIVE_INFINITY).isFinite())
        assertFalse(scale.normalizedY(0.0).isNaN())
    }

    private fun assertScale(expectedMax: Double, vararg values: Float) {
        val scale = requireNotNull(scale(*values))
        assertEquals(0.0, scale.minY, 0.0)
        assertEquals(expectedMax, scale.maxY, 0.0)
        assertTrue(scale.maxY >= values.maxOrNull()!!)
    }

    private fun scale(vararg values: Float) = MovementChartScaleCalculator.calculate(values.toList())
}
