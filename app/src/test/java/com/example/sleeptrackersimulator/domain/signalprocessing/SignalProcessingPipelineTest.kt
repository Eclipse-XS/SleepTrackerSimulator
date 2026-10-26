package com.example.sleeptrackersimulator.domain.signalprocessing

import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalProcessingPipelineTest {

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
