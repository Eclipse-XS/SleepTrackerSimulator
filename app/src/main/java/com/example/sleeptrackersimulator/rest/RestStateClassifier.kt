package com.example.sleeptrackersimulator.rest

import com.example.sleeptrackersimulator.core.model.MovementState
import kotlin.math.sqrt

fun interface TimeSource {
    fun nowMillis(): Long
}

/** Educational activity/rest estimation. It is not a medical sleep-stage classifier. */
class RestStateClassifier(
    val config: RestStateConfig = RestStateConfig(),
    private val timeSource: TimeSource = TimeSource { System.nanoTime() / 1_000_000L },
) {
    private var stillSinceMillis: Long? = null

    fun classify(input: RestStateInput): RestStateResult = classify(input, timeSource.nowMillis())

    fun classify(input: RestStateInput, nowMillis: Long): RestStateResult {
        require(nowMillis >= 0L) { "Time cannot be negative" }

        if (!input.heartRateAvailable || input.heartRateBpm == null) {
            stillSinceMillis = null
        } else if (input.movementState == MovementState.MOVEMENT) {
            stillSinceMillis = null
        } else if (input.movementState == MovementState.STILL && stillSinceMillis == null) {
            stillSinceMillis = nowMillis
        }

        val stillDuration = stillSinceMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L
        val recent = input.heartRateHistory.takeLast(config.heartRateWindowSize)
        val average = recent.averageOrNull()
        val standardDeviation = recent.populationStandardDeviationOrNull(average)
        val range = recent.rangeOrNull()
        val enoughHeartRate = input.heartRateAvailable && input.heartRateBpm != null &&
            recent.size >= config.minimumHeartRateSamples
        val stable = enoughHeartRate && standardDeviation != null && range != null &&
            standardDeviation <= config.maximumStableStandardDeviation &&
            range <= config.maximumStableRange

        val (state, reason) = when {
            !input.heartRateAvailable || input.heartRateBpm == null ->
                RestState.MONITORING to "Connect Sleep Band for a combined estimate"
            input.movementState == MovementState.MOVEMENT ->
                RestState.ACTIVE to "Movement detected · ${input.heartRateBpm} BPM"
            input.movementState == null ->
                RestState.MONITORING to "Waiting for phone movement data"
            !enoughHeartRate ->
                RestState.MONITORING to "Collecting heart-rate baseline"
            stillDuration < config.restingAfterMillis ->
                RestState.MONITORING to "Observing stillness"
            stillDuration >= config.possibleSleepAfterMillis && stable ->
                RestState.POSSIBLE_SLEEP to "Prolonged stillness · Stable heart rate"
            stable ->
                RestState.RESTING to "Low movement · Stable heart rate"
            else ->
                RestState.RESTING to "Low movement · Heart rate still varying"
        }

        return RestStateResult(
            state = state,
            reason = reason,
            stillDurationMillis = stillDuration,
            heartRateSampleCount = recent.size,
            recentAverageHeartRate = average,
            heartRateVariation = standardDeviation,
            heartRateRange = range,
            heartRateStable = stable,
        )
    }

    fun reset() {
        stillSinceMillis = null
    }
}

internal fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()

internal fun List<Int>.populationStandardDeviationOrNull(average: Double? = averageOrNull()): Double? {
    if (isEmpty() || average == null) return null
    return sqrt(sumOf { value ->
        val difference = value - average
        difference * difference
    } / size)
}

internal fun List<Int>.rangeOrNull(): Int? =
    if (isEmpty()) null else (maxOrNull()!! - minOrNull()!!)
