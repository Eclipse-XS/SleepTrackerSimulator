package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionQualityStatus
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import kotlin.math.roundToInt
import kotlin.random.Random

data class DemoNight(val session: RecordingSession, val summary: SessionSummary)

class DemoDatasetGenerator(private val seed: Int = 20261027) {
    fun generate(count: Int = 90, anchorUtcMillis: Long = DEFAULT_ANCHOR_UTC): List<DemoNight> {
        require(count in 1..3650)
        val random = Random(seed)
        return (0 until count).map { index ->
            val nightNumber = index + 1
            val start = anchorUtcMillis - (count - nightNumber).toLong() * DAY_MS
            val durationMinutes = 390 + random.nextInt(151)
            val durationMs = durationMinutes * 60_000L
            val coverage = (0.82f + random.nextFloat() * 0.18f).coerceIn(0f, 1f)
            val anomalous = nightNumber % 19 == 0
            val lowMotion = if (anomalous) 0.48f + random.nextFloat() * 0.12f else 0.70f + random.nextFloat() * 0.22f
            val episodes = if (anomalous) random.nextInt(28, 46) else random.nextInt(6, 25)
            val rate = episodes / (durationMs / 3_600_000f)
            val id = "demo-v1-%03d".format(nightNumber)
            val quality = SessionQualityStatus.fromCoverage(coverage)
            val session = RecordingSession(
                sessionId = id,
                startUtcMillis = start,
                endUtcMillis = start + durationMs,
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
                updatedAt = start + durationMs,
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
                calculatedAtUtcMillis = start + durationMs,
            )
            DemoNight(session, summary)
        }
    }

    fun validate(nights: List<DemoNight>): List<String> = buildList {
        if (nights.map { it.session.sessionId }.distinct().size != nights.size) add("Duplicate session IDs")
        nights.forEach { night ->
            if (night.session.sourceMode != SourceMode.DEMO || night.summary.sourceKind != SourceKind.SIMULATED) add("Invalid provenance: ${night.session.sessionId}")
            if (night.summary.dataCoverageFraction !in 0f..1f) add("Invalid coverage: ${night.session.sessionId}")
            if (night.summary.validDurationMs !in 0..night.summary.observationDurationMs) add("Invalid duration: ${night.session.sessionId}")
            val expectedRate = night.summary.movementEpisodeCount / (night.summary.observationDurationMs / 3_600_000f)
            if (kotlin.math.abs(expectedRate - night.summary.movementRatePerHour) > 0.001f) add("Inconsistent movement rate: ${night.session.sessionId}")
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
        const val DEFAULT_ANCHOR_UTC = 1_798_156_800_000L // 2026-12-25T00:00:00Z; stable fixture anchor.
        private const val DAY_MS = 86_400_000L
    }
}
