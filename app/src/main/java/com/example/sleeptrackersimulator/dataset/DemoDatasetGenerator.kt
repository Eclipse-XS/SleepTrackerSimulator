package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionQualityStatus
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.Prediction
import kotlin.math.roundToInt
import kotlin.random.Random
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class DemoNight(
    val session: RecordingSession,
    val summary: SessionSummary,
    val featureWindows: List<FeatureWindow> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
)

class DemoDatasetGenerator(
    private val seed: Int = 20261027,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun generate(count: Int = 90, endDate: LocalDate = LocalDate.now(clock)): List<DemoNight> {
        require(count in 1..3650)
        require(!endDate.isAfter(LocalDate.now(clock))) { "Demo end date cannot be in the future" }
        val random = Random(seed)
        val plannedEnd = endDate.atTime(LocalTime.of(6, 0)).atZone(clock.zone)
        val anchorEnd = if (endDate == LocalDate.now(clock) && plannedEnd.toInstant().isAfter(clock.instant())) {
            ZonedDateTime.ofInstant(clock.instant(), clock.zone)
        } else plannedEnd
        return (0 until count).map { index ->
            val nightNumber = index + 1
            val durationMinutes = 390 + random.nextInt(151)
            val durationMs = durationMinutes * 60_000L
            val end = anchorEnd.minusDays((count - nightNumber).toLong()).toInstant().toEpochMilli()
            val start = end - durationMs
            val coverage = (0.82f + random.nextFloat() * 0.18f).coerceIn(0f, 1f)
            val anomalous = nightNumber % 19 == 0
            val lowMotion = if (anomalous) 0.48f + random.nextFloat() * 0.12f else 0.70f + random.nextFloat() * 0.22f
            val id = "demo-v1-%03d".format(nightNumber)
            val windowCount = 48
            val restCount = (lowMotion.coerceIn(0f, 1f) * windowCount).roundToInt().coerceIn(0, windowCount)
            val movementCount = windowCount - restCount
            val activeCount = (movementCount / 4).coerceAtLeast(if (movementCount > 0) 1 else 0)
            val classes = buildList {
                repeat(restCount) { add(ActivityClass.REST) }
                repeat(movementCount - activeCount) { add(ActivityClass.RESTLESS) }
                repeat(activeCount) { add(ActivityClass.ACTIVE) }
            }.shuffled(random)
            val episodes = classes.fold(Pair(0, false)) { (count, moving), activity ->
                val nowMoving = activity.isMovement
                Pair(count + if (nowMoving && !moving) 1 else 0, nowMoving)
            }.first
            val rate = episodes / (durationMs / 3_600_000f)
            val quality = SessionQualityStatus.fromCoverage(coverage)
            val session = RecordingSession(
                sessionId = id,
                startUtcMillis = start,
                endUtcMillis = end,
                startElapsedRealtimeNanos = 0L,
                status = SessionStatus.COMPLETED,
                sourceMode = SourceMode.DEMO,
                devicePlacement = DevicePlacement.MATTRESS,
                requestedSamplingHz = 20,
                modelVersion = "demo-generator-v1",
                dataCoverageFraction = coverage,
                qualityStatus = quality,
                interruptionReason = "Synthetic demonstration record",
                createdAt = start,
                updatedAt = end,
            )
            val summary = SessionSummary(
                sessionId = id,
                observationDurationMs = durationMs,
                validDurationMs = (durationMs * coverage).toLong(),
                dataCoverageFraction = coverage,
                lowMotionFraction = lowMotion.coerceIn(0f, 1f),
                movementEpisodeCount = episodes,
                movementRatePerHour = rate,
                averageHeartRateBpm = (61 + random.nextGaussianLike() * 5 + if (anomalous) 9 else 0).roundToInt().coerceIn(45, 110),
                sourceKind = SourceKind.SIMULATED,
                devicePlacement = DevicePlacement.MATTRESS,
                modelVersion = "demo-generator-v1",
                calculatedAtUtcMillis = end,
            )
            val intervalMs = durationMs / windowCount
            val windows = classes.mapIndexed { windowIndex, activity ->
                val windowId = "$id-window-%03d".format(windowIndex)
                val windowStart = start + intervalMs * windowIndex
                val windowEnd = if (windowIndex == windowCount - 1) end else windowStart + intervalMs
                FeatureWindow(
                    windowId = windowId,
                    sessionId = id,
                    sourceId = "demo-generator",
                    startElapsedNanos = windowIndex * 1_000_000_000L,
                    endElapsedNanos = (windowIndex + 1L) * 1_000_000_000L,
                    startUtcMillis = windowStart,
                    endUtcMillis = windowEnd,
                    sampleCount = 20,
                    expectedSampleCount = 20,
                    coverageFraction = coverage,
                    motionRms = when (activity) { ActivityClass.REST -> 0.04f; ActivityClass.RESTLESS -> 0.35f; else -> 1.2f },
                    variance = when (activity) { ActivityClass.REST -> 0.005f; ActivityClass.RESTLESS -> 0.08f; else -> 0.4f },
                    jerk = when (activity) { ActivityClass.REST -> 0.1f; ActivityClass.RESTLESS -> 1.0f; else -> 3.0f },
                    peakToPeak = when (activity) { ActivityClass.REST -> 0.08f; ActivityClass.RESTLESS -> 0.9f; else -> 2.8f },
                    qualityScore = coverage,
                    preprocessingVersion = 1,
                )
            }
            val predictions = windows.zip(classes).map { (window, activity) ->
                val confidence = when (activity) { ActivityClass.REST -> 0.86f; ActivityClass.RESTLESS -> 0.78f; ActivityClass.ACTIVE -> 0.82f; ActivityClass.UNCERTAIN -> 0f }
                Prediction(
                    predictionId = "${window.windowId}-prediction",
                    windowId = window.windowId,
                    sessionId = id,
                    modelId = "demo-generator-v1",
                    predictedClass = activity,
                    probabilities = ActivityClass.entries.associateWith { if (it == activity) confidence else if (it == ActivityClass.UNCERTAIN) 0f else (1f - confidence) / 2f },
                    confidence = confidence,
                    inferenceDurationNs = 0L,
                    timestampMillis = window.endUtcMillis,
                )
            }
            DemoNight(session, summary, windows, predictions)
        }
    }

    fun validate(nights: List<DemoNight>): List<String> = buildList {
        if (nights.map { it.session.sessionId }.distinct().size != nights.size) add("Duplicate session IDs")
        nights.forEach { night ->
            if (night.session.sourceMode != SourceMode.DEMO || night.summary.sourceKind != SourceKind.SIMULATED) add("Invalid provenance: ${night.session.sessionId}")
            if (night.summary.dataCoverageFraction !in 0f..1f) add("Invalid coverage: ${night.session.sessionId}")
            if (night.summary.validDurationMs !in 0..night.summary.observationDurationMs) add("Invalid duration: ${night.session.sessionId}")
            val end = night.session.endUtcMillis
            if (end == null || end < night.session.startUtcMillis) add("Invalid time range: ${night.session.sessionId}")
            val expectedRate = night.summary.movementEpisodeCount / (night.summary.observationDurationMs / 3_600_000f)
            if (kotlin.math.abs(expectedRate - night.summary.movementRatePerHour) > 0.001f) add("Inconsistent movement rate: ${night.session.sessionId}")
            if (night.featureWindows.size != 48) add("Expected 48 feature windows: ${night.session.sessionId}")
            if (night.predictions.size != night.featureWindows.size) add("Prediction/window mismatch: ${night.session.sessionId}")
            if (night.predictions.any { prediction -> kotlin.math.abs(prediction.probabilities.values.sum() - 1f) > 0.001f }) {
                add("Invalid prediction probabilities: ${night.session.sessionId}")
            }
            if (night.predictions.any { it.modelId != "demo-generator-v1" }) add("Invalid demo model provenance: ${night.session.sessionId}")
        }
    }

    fun toCsv(nights: List<DemoNight>): String = buildString {
        appendLine("sessionId,startUtcMillis,durationMs,coverage,lowMotion,episodes,movementRatePerHour,averageBpm,provenance")
        nights.forEach { n ->
            appendLine(listOf(n.session.sessionId, n.session.startUtcMillis, n.summary.observationDurationMs, n.summary.dataCoverageFraction, n.summary.lowMotionFraction, n.summary.movementEpisodeCount, n.summary.movementRatePerHour, n.summary.averageHeartRateBpm, n.summary.sourceKind.name).joinToString(","))
        }
    }

    private fun Random.nextGaussianLike(): Float = (nextFloat() + nextFloat() + nextFloat() + nextFloat() - 2f)

    companion object {
        const val DEFAULT_SEED = 20261027
    }
}
