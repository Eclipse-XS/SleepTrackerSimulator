package com.example.sleeptrackersimulator.cloud

data class ValidationResult(val valid: Boolean, val reason: String? = null)

class SensorPayloadValidator(
    private val maxFutureSkewMillis: Long = 60_000L,
    private val maxAcceleration: Float = 200f,
) {
    fun validate(payload: SensorPayload, now: Long): ValidationResult {
        fun invalid(reason: String) = ValidationResult(false, reason)
        if (payload.measurementId.isBlank()) return invalid("measurementId is blank")
        if (payload.deviceId != CLOUD_DEVICE_ID) return invalid("unsupported deviceId")
        if (payload.sensorType != "ACCELEROMETER") return invalid("unsupported sensorType")
        if (!payload.x.isFinite() || !payload.y.isFinite() || !payload.z.isFinite()) return invalid("non-finite acceleration")
        if (kotlin.math.abs(payload.x) > maxAcceleration || kotlin.math.abs(payload.y) > maxAcceleration || kotlin.math.abs(payload.z) > maxAcceleration) return invalid("acceleration out of range")
        if (!payload.value.isFinite() || payload.value < 0f) return invalid("invalid value")
        if (!payload.movementIntensity.isFinite() || payload.movementIntensity < 0f) return invalid("invalid movementIntensity")
        if (payload.timestamp <= 0L || payload.timestamp > now + maxFutureSkewMillis) return invalid("invalid timestamp")
        if (payload.heartRateBpm != null && payload.heartRateBpm !in 20..250) return invalid("invalid heartRateBpm")
        if (payload.movementState !in setOf("STILL", "MOVEMENT", "UNAVAILABLE")) return invalid("invalid movementState")
        if (payload.restState !in setOf("MONITORING", "ACTIVE", "RESTING", "POSSIBLE_SLEEP")) return invalid("invalid restState")
        return ValidationResult(true)
    }
}
