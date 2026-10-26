package com.example.sleeptrackersimulator.core.model

/**
 * Aggregated summary computed upon completion of an observation session.
 */
data class SessionSummary(
    val sessionId: String,
    val observationDurationMs: Long,
    val validDurationMs: Long,
    val dataCoverageFraction: Float,
    val lowMotionFraction: Float,
    val movementEpisodeCount: Int,
    val movementRatePerHour: Float,
    val averageHeartRateBpm: Int? = null,
    val sourceKind: SourceKind = SourceKind.PHONE_REAL,
    val devicePlacement: DevicePlacement = DevicePlacement.MATTRESS,
    val modelVersion: String = "v2.1-tflite",
    val calculatedAtUtcMillis: Long = System.currentTimeMillis(),
) {
    val isComparableWithBaseline: Boolean
        get() = sourceKind.isRealData && dataCoverageFraction >= 0.85f && observationDurationMs >= 30 * 60 * 1000L // >= 30 mins
}

/**
 * Robust personal baseline calculated across multiple comparable sessions.
 */
data class PersonalBaseline(
    val baselineId: String,
    val sourceMode: SourceMode,
    val placement: DevicePlacement,
    val compatibleSessionCount: Int,
    val medianLowMotionFraction: Float,
    val madLowMotionFraction: Float, // Median Absolute Deviation
    val medianMovementRatePerHour: Float,
    val validFromUtcMillis: Long,
    val validUntilUtcMillis: Long? = null,
    val baselineVersion: Int = 1,
) {
    val isReliable: Boolean
        get() = compatibleSessionCount >= 5

    fun compare(sessionSummary: SessionSummary): BaselineComparison? {
        if (!sessionSummary.isComparableWithBaseline || sessionSummary.devicePlacement != placement) {
            return null
        }
        val diffFraction = sessionSummary.lowMotionFraction - medianLowMotionFraction
        val relativeChangePercent = if (medianLowMotionFraction > 0f) {
            (diffFraction / medianLowMotionFraction) * 100f
        } else 0f

        return BaselineComparison(
            baselineId = baselineId,
            sessionId = sessionSummary.sessionId,
            baselineLowMotionFraction = medianLowMotionFraction,
            currentLowMotionFraction = sessionSummary.lowMotionFraction,
            differencePercentagePoints = diffFraction * 100f,
            relativeChangePercent = relativeChangePercent,
            isWithinNormalVariation = kotlin.math.abs(diffFraction) <= (madLowMotionFraction * 2.0f),
            sampleCount = compatibleSessionCount,
        )
    }
}

data class BaselineComparison(
    val baselineId: String,
    val sessionId: String,
    val baselineLowMotionFraction: Float,
    val currentLowMotionFraction: Float,
    val differencePercentagePoints: Float,
    val relativeChangePercent: Float,
    val isWithinNormalVariation: Boolean,
    val sampleCount: Int,
)
