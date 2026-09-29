package com.example.sleeptrackersimulator.core.model

import kotlin.math.sqrt

data class SensorSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampNanos: Long,
) {
    val magnitude: Float
        get() = sqrt(x * x + y * y + z * z)
}

enum class MovementState {
    STILL,
    MOVEMENT,
}

data class MovementAnalysis(
    val sample: SensorSample,
    val motionIntensity: Float,
    val state: MovementState,
)
