package com.example.sleeptrackersimulator.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SensorPayloadTest {
    @Test
    fun `payload retains methodical and sleep-domain fields`() {
        val payload = SensorPayload(
            deviceId = CLOUD_DEVICE_ID,
            sensorType = "ACCELEROMETER",
            x = 1f,
            y = 2f,
            z = 3f,
            value = 0.4f,
            timestamp = 123L,
            movementState = "MOVEMENT",
            movementIntensity = 0.4f,
            heartRateBpm = null,
            bleConnected = false,
            restState = "MONITORING",
        )

        assertEquals(CLOUD_DEVICE_ID, payload.deviceId)
        assertEquals("ACCELEROMETER", payload.sensorType)
        assertEquals(3f, payload.z)
        assertEquals(payload.movementIntensity, payload.value)
        assertNull(payload.heartRateBpm)
        assertFalse(payload.bleConnected)
        assertEquals("MONITORING", payload.restState)
    }
}
