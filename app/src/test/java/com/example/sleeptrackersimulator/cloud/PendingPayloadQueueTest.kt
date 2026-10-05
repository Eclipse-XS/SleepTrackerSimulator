package com.example.sleeptrackersimulator.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPayloadQueueTest {
    @Test
    fun `queue preserves FIFO order`() {
        val queue = PendingPayloadQueue(capacity = 3)
        queue.add(payload(1L))
        queue.add(payload(2L))

        assertEquals(1L, queue.removeFirst().timestamp)
        assertEquals(2L, queue.removeFirst().timestamp)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `queue drops oldest payload at capacity`() {
        val queue = PendingPayloadQueue(capacity = 2)
        queue.add(payload(1L))
        queue.add(payload(2L))
        queue.add(payload(3L))

        assertEquals(2, queue.size)
        assertEquals(2L, queue.removeFirst().timestamp)
        assertEquals(3L, queue.removeFirst().timestamp)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `queue rejects zero capacity`() {
        PendingPayloadQueue(capacity = 0)
    }

    private fun payload(timestamp: Long) = SensorPayload(
        deviceId = CLOUD_DEVICE_ID,
        sensorType = "ACCELEROMETER",
        timestamp = timestamp,
    )
}
