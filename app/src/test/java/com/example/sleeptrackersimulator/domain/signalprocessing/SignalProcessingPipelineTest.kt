package com.example.sleeptrackersimulator.domain.signalprocessing

import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalProcessingPipelineTest {

    private fun sample(index: Int, baseNanos: Long = 0L) = RawSensorSample(
        sampleId = index.toLong(), sessionId = "s1", sourceId = "phone",
        elapsedRealtimeNanos = baseNanos + index * 50_000_000L,
        utcTimestampMillis = 1_000L + index * 50L, x = 0f, y = 0f, z = 9.81f,
    )

    @Test
    fun `sample validator accepts normal samples and rejects non-finite and extremes`() {
        val validator = SampleValidator()

        val valid = validator.validate(
            sessionId = "s1",
            sourceId = "phone",
            elapsedNanos = 1000L,
            utcMillis = 2000L,
            x = 0.0f,
            y = 0.0f,
            z = 9.81f,
            accuracy = 3,
            sourceKind = SourceKind.PHONE_REAL,
        )
        assertTrue(valid is SampleValidationResult.Valid)

        val nanSample = validator.validate(
            sessionId = "s1",
            sourceId = "phone",
            elapsedNanos = 1000L,
            utcMillis = 2000L,
            x = Float.NaN,
            y = 0.0f,
            z = 9.81f,
            accuracy = 3,
            sourceKind = SourceKind.PHONE_REAL,
        )
        assertTrue(nanSample is SampleValidationResult.Invalid)

        val extremeSample = validator.validate(
            sessionId = "s1",
            sourceId = "phone",
            elapsedNanos = 1000L,
            utcMillis = 2000L,
            x = 300.0f, // > 196 m/s^2 limit
            y = 0.0f,
            z = 9.81f,
            accuracy = 3,
            sourceKind = SourceKind.PHONE_REAL,
        )
        assertTrue(extremeSample is SampleValidationResult.Invalid)
    }

    @Test
    fun `sliding window segmenter segments exactly at window size`() {
        val segmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)

        val readyWindows = mutableListOf<List<RawSensorSample>>()
        for (i in 1..45) {
            val sample = RawSensorSample(
                sampleId = i.toLong(),
                sessionId = "s1",
                sourceId = "phone",
                elapsedRealtimeNanos = i * 50_000_000L,
                utcTimestampMillis = i * 50L,
                x = 0f,
                y = 0f,
                z = 9.81f,
            )
            readyWindows.addAll(segmenter.addSample(sample))
        }

        // 45 samples with windowSize=20, stride=20 produces exactly 2 full windows (40 samples consumed, 5 remain in buffer)
        assertEquals(2, readyWindows.size)
        assertEquals(20, readyWindows[0].size)
        assertEquals(20, readyWindows[1].size)
    }

    @Test
    fun `pause boundary discards partial window and does not make cross-boundary prediction`() {
        val segmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)
        repeat(15) { assertTrue(segmenter.addSample(sample(it + 1)).isEmpty()) }
        segmenter.clear() // pause transition

        val afterFive = (1..5).flatMap { segmenter.addSample(sample(it, baseNanos = 5_000_000_000L)) }

        assertTrue(afterFive.isEmpty())
        assertEquals(5, segmenter.pendingSampleCount)
    }

    @Test
    fun `twenty fresh samples after resume produce exactly one valid window`() {
        val segmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)
        repeat(15) { segmenter.addSample(sample(it + 1)) }
        segmenter.clear()

        val windows = (1..20).flatMap { segmenter.addSample(sample(it, baseNanos = 5_000_000_000L)) }

        assertEquals(1, windows.size)
        assertEquals(20, windows.single().size)
        assertTrue(windows.single().all { it.elapsedRealtimeNanos > 5_000_000_000L })
    }

    @Test
    fun `timestamp discontinuity discards partial window`() {
        val segmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)
        repeat(15) { segmenter.addSample(sample(it + 1)) }
        val windows = (1..5).flatMap { segmenter.addSample(sample(it, baseNanos = 10_000_000_000L)) }
        assertTrue(windows.isEmpty())
        assertEquals(5, segmenter.pendingSampleCount)
    }

    @Test
    fun `feature extractor extracts motion RMS, jerk, and variance correctly`() {
        val samples = (1..20).map { i ->
            RawSensorSample(
                sampleId = i.toLong(),
                sessionId = "s1",
                sourceId = "phone",
                elapsedRealtimeNanos = i * 50_000_000L, // 50ms intervals = 20Hz
                utcTimestampMillis = i * 50L,
                x = (i * 0.1f),
                y = 0f,
                z = 9.81f,
                sensorAccuracy = 3,
            )
        }

        val features = FeatureExtractor.extractFeatures(samples, expectedSampleCount = 20)

        assertEquals("s1", features.sessionId)
        assertEquals(20, features.sampleCount)
        assertEquals(1.0f, features.coverageFraction, 0.001f)
        assertTrue(features.motionRms > 0.0f)
        assertTrue(features.variance >= 0.0f)
        assertTrue(features.jerk > 0.0f)
        assertTrue(features.qualityScore > 0.8f)
    }
}
