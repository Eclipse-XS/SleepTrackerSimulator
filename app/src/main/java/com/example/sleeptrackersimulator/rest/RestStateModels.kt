package com.example.sleeptrackersimulator.rest

import com.example.sleeptrackersimulator.core.model.MovementState

enum class RestState {
    MONITORING,
    ACTIVE,
    RESTING,
    POSSIBLE_SLEEP,
}

data class RestStateInput(
    val movementState: MovementState?,
    val movementIntensity: Float,
    val heartRateBpm: Int?,
    val heartRateHistory: List<Int>,
    val heartRateAvailable: Boolean,
)

data class RestStateResult(
    val state: RestState,
    val reason: String,
    val stillDurationMillis: Long,
    val heartRateSampleCount: Int,
    val recentAverageHeartRate: Double?,
    val heartRateVariation: Double?,
    val heartRateRange: Int?,
    val heartRateStable: Boolean,
) {
    companion object {
        val INITIAL = RestStateResult(
            state = RestState.MONITORING,
            reason = "Waiting for sensor data",
            stillDurationMillis = 0L,
            heartRateSampleCount = 0,
            recentAverageHeartRate = null,
            heartRateVariation = null,
            heartRateRange = null,
            heartRateStable = false,
        )
    }
}

data class RestStateConfig(
    val restingAfterMillis: Long = 3_000L,
    val possibleSleepAfterMillis: Long = 12_000L,
    val minimumHeartRateSamples: Int = 5,
    val heartRateWindowSize: Int = 10,
    val maximumStableStandardDeviation: Double = 2.5,
    val maximumStableRange: Int = 6,
) {
    init {
        require(restingAfterMillis >= 0L)
        require(possibleSleepAfterMillis > restingAfterMillis)
        require(minimumHeartRateSamples >= 2)
        require(heartRateWindowSize >= minimumHeartRateSamples)
        require(maximumStableStandardDeviation >= 0.0)
        require(maximumStableRange >= 0)
    }
}
