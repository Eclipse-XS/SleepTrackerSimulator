package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorWindowBufferTest {
    @Test
    fun `ring buffer emits chronological rolling window`() {
        val buffer = SensorWindowBuffer(3)
        assertNull(buffer.add(sample(1)))
        assertNull(buffer.add(sample(2)))
        assertEquals(listOf(1L, 2L, 3L), buffer.add(sample(3))!!.map { it.timestampNanos })
        assertEquals(listOf(2L, 3L, 4L), buffer.add(sample(4))!!.map { it.timestampNanos })
    }

    @Test
    fun `clear discards stale samples`() {
        val buffer = SensorWindowBuffer(2)
        buffer.add(sample(1))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertNull(buffer.add(sample(2)))
    }

    private fun sample(value: Long) = SensorSample(value.toFloat(), 0f, 9.81f, value)
}
