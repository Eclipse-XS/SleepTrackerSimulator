package com.example.sleeptrackersimulator.cloud

import org.junit.Assert.*
import org.junit.Test

class SensorPayloadValidatorTest {
    private val validator = SensorPayloadValidator()
    private val now = 1_000_000L
    private fun valid() = SensorPayload("m", CLOUD_DEVICE_ID, "ACCELEROMETER", 1f, 2f, 3f, .2f, now, "STILL", .2f, null, false, "MONITORING")
    private fun rejected(payload: SensorPayload) = assertFalse(validator.validate(payload, now).valid)

    @Test fun `valid payload and nullable heart rate are accepted`() = assertTrue(validator.validate(valid(), now).valid)
    @Test fun `NaN x is rejected`() = rejected(valid().copy(x = Float.NaN))
    @Test fun `infinite z is rejected`() = rejected(valid().copy(z = Float.POSITIVE_INFINITY))
    @Test fun `negative intensity is rejected`() = rejected(valid().copy(movementIntensity = -1f))
    @Test fun `low heart rate is rejected`() = rejected(valid().copy(heartRateBpm = 19))
    @Test fun `high heart rate is rejected`() = rejected(valid().copy(heartRateBpm = 251))
    @Test fun `future timestamp is rejected`() = rejected(valid().copy(timestamp = now + 60_001))
    @Test fun `unsupported sensor type is rejected`() = rejected(valid().copy(sensorType = "GYROSCOPE"))
    @Test fun `invalid movement state is rejected`() = rejected(valid().copy(movementState = "RUNNING"))
    @Test fun `invalid rest state is rejected`() = rejected(valid().copy(restState = "SLEEP"))
}
