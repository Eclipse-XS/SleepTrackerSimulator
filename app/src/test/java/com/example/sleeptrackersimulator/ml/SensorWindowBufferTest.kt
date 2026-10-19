package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SensorWindowBufferTest {
    @Test fun `returns chronological rolling window`() {
        val buffer = SensorWindowBuffer(3)
        assertNull(buffer.add(sample(1f)))
        assertNull(buffer.add(sample(2f)))
        assertEquals(listOf(1f, 2f, 3f), buffer.add(sample(3f))!!.map { it[0] })
        assertEquals(listOf(2f, 3f, 4f), buffer.add(sample(4f))!!.map { it[0] })
    }

    @Test fun `clear requires a complete new window`() {
        val buffer = SensorWindowBuffer(2)
        buffer.add(sample(1f))
        buffer.add(sample(2f))
        buffer.clear()
        assertNull(buffer.add(sample(3f)))
    }

    @Test fun `large timestamp gap resets temporal window`() {
        val buffer = SensorWindowBuffer(2)
        assertNull(buffer.add(sample(1f, 50_000_000L)))
        assertNull(buffer.add(sample(2f, 250_000_000L)))
        assertEquals(listOf(2f, 3f), buffer.add(sample(3f, 300_000_000L))!!.map { it[0] })
    }

    @Test fun `out of order timestamp resets temporal window`() {
        val buffer = SensorWindowBuffer(2)
        assertNull(buffer.add(sample(1f, 100_000_000L)))
        assertNull(buffer.add(sample(2f, 90_000_000L)))
        assertEquals(listOf(2f, 3f), buffer.add(sample(3f, 140_000_000L))!!.map { it[0] })
    }

    @Test fun `invalid floating point sample is rejected`() {
        val buffer = SensorWindowBuffer(2)
        assertThrows(IllegalArgumentException::class.java) {
            buffer.add(SensorSample(Float.NaN, 0f, 0f, 1L))
        }
    }

    private fun sample(value: Float, timestamp: Long = value.toLong() * 50_000_000L) =
        SensorSample(value, value, value, timestamp)
}
