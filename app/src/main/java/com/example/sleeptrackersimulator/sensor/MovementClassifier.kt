package com.example.sleeptrackersimulator.sensor

import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import kotlin.math.sqrt

/**
 * Educational motion classifier, not a medical sleep-stage detector.
 *
 * It smooths the 3D distance between consecutive acceleration vectors. This detects a
 * position change even when the total magnitude remains close to Earth's gravity.
 * Movement starts at [movementThreshold] and returns to stillness below [stillThreshold].
 * Separate thresholds prevent state flicker near the boundary.
 */
class MovementClassifier(
    val movementThreshold: Float = DEFAULT_MOVEMENT_THRESHOLD,
    val stillThreshold: Float = DEFAULT_STILL_THRESHOLD,
    private val smoothingFactor: Float = DEFAULT_SMOOTHING_FACTOR,
) {
    init {
        require(movementThreshold > stillThreshold) {
            "Movement threshold must be greater than still threshold"
        }
        require(stillThreshold >= 0f) { "Still threshold cannot be negative" }
        require(smoothingFactor in 0f..1f) { "Smoothing factor must be between 0 and 1" }
    }

    private var state = MovementState.STILL
    private var smoothedIntensity = 0f
    private var previousSample: SensorSample? = null

    fun analyze(sample: SensorSample): MovementAnalysis {
        val previous = previousSample
        val rawIntensity = if (previous == null) {
            0f
        } else {
            val deltaX = sample.x - previous.x
            val deltaY = sample.y - previous.y
            val deltaZ = sample.z - previous.z
            sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
        }
        previousSample = sample
        smoothedIntensity += smoothingFactor * (rawIntensity - smoothedIntensity)

        state = when (state) {
            MovementState.STILL -> {
                if (smoothedIntensity >= movementThreshold) MovementState.MOVEMENT else MovementState.STILL
            }

            MovementState.MOVEMENT -> {
                if (smoothedIntensity <= stillThreshold) MovementState.STILL else MovementState.MOVEMENT
            }
        }

        return MovementAnalysis(
            sample = sample,
            motionIntensity = smoothedIntensity,
            state = state,
        )
    }

    fun reset() {
        state = MovementState.STILL
        smoothedIntensity = 0f
        previousSample = null
    }

    companion object {
        const val DEFAULT_MOVEMENT_THRESHOLD = 0.45f
        const val DEFAULT_STILL_THRESHOLD = 0.20f
        const val DEFAULT_SMOOTHING_FACTOR = 0.25f
    }
}
