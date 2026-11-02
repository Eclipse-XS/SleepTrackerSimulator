package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionEngineTest {

    private fun makeSample(
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 9.81f,
        elapsedNanos: Long = 1_000_000_000L,
        utcTimestampMillis: Long = 1_700_000_000_000L,
    ) = RawSensorSample(
        sampleId = 0L,
        sessionId = "test-session",
        sourceId = "phone_accelerometer",
        elapsedRealtimeNanos = elapsedNanos,
        utcTimestampMillis = utcTimestampMillis,
        x = x,
        y = y,
        z = z,
        sensorAccuracy = 3,
        sourceKind = SourceKind.PHONE_REAL,
    )

    private fun makeHeartRate(
        bpm: Int = 62,
        utcTimestampMillis: Long = 1_700_000_000_000L,
        sourceKind: SourceKind = SourceKind.BLE_REAL,
    ) = HeartRateSample(
        sessionId = "test-session",
        deviceId = "ble-band-001",
        bpm = bpm,
        utcTimestampMillis = utcTimestampMillis,
        sourceKind = sourceKind,
    )

    private val fakeAccelClassifier = object : ActivityClassifier {
        override fun classify(window: Array<FloatArray>, windowEndTimestampNanos: Long): ActivityPrediction {
            return ActivityPrediction(
                predictionId = "fake-accel-pred",
                windowEndTimestampNanos = windowEndTimestampNanos,
                activity = com.example.sleeptrackersimulator.ml.ActivityClass.REST,
                confidence = 0.95f,
                probabilities = mapOf(
                    com.example.sleeptrackersimulator.ml.ActivityClass.REST to 0.95f,
                    com.example.sleeptrackersimulator.ml.ActivityClass.RESTLESS to 0.03f,
                    com.example.sleeptrackersimulator.ml.ActivityClass.ACTIVE to 0.02f,
                ),
                inferenceNanos = 120_000L,
            )
        }
        override fun close() {}
    }

    private val fakeMultimodalClassifier = object : MultimodalClassifier {
        override fun classify(
            accelerometerWindow: Array<FloatArray>,
            heartRateBpm: Float?,
            isHeartRateFresh: Boolean,
            windowEndTimestampNanos: Long,
        ): ActivityPrediction {
            return ActivityPrediction(
                predictionId = "fake-multi-pred",
                windowEndTimestampNanos = windowEndTimestampNanos,
                activity = com.example.sleeptrackersimulator.ml.ActivityClass.REST,
                confidence = 0.98f,
                probabilities = mapOf(
                    com.example.sleeptrackersimulator.ml.ActivityClass.REST to 0.98f,
                    com.example.sleeptrackersimulator.ml.ActivityClass.RESTLESS to 0.01f,
                    com.example.sleeptrackersimulator.ml.ActivityClass.ACTIVE to 0.01f,
                ),
                inferenceNanos = 200_000L,
            )
        }
        override fun close() {}
    }

    @Test
    fun testIncompleteWindowReturnsInsufficientData() {
        val engine = DefaultModelSelectionEngine(activityClassifier = null)
        val shortWindow = List(10) { makeSample() }

        val result = engine.selectAndClassify(shortWindow)

        assertEquals(InferenceMode.INSUFFICIENT_DATA, result.inferenceMode)
        assertEquals(ActivityClass.UNCERTAIN, result.activity)
        assertEquals(0f, result.confidence, 0.001f)
        assertTrue(result.qualityFlags.contains("INCOMPLETE_WINDOW"))
        assertTrue(result.fallbackReason?.contains("insufficient_samples") == true)
    }

    @Test
    fun testCorruptedSampleWithNanReturnsInsufficientData() {
        val engine = DefaultModelSelectionEngine(activityClassifier = null)
        val samples = MutableList(20) { makeSample() }
        samples[5] = makeSample(x = Float.NaN)

        val result = engine.selectAndClassify(samples)

        assertEquals(InferenceMode.INSUFFICIENT_DATA, result.inferenceMode)
        assertEquals(ActivityClass.UNCERTAIN, result.activity)
        assertTrue(result.qualityFlags.contains("NON_FINITE_SAMPLE"))
        assertTrue(result.fallbackReason?.contains("non-finite") == true)
    }

    @Test
    fun testTfliteClassifierUsedWhenAvailable() {
        val fakeClassifier = object : ActivityClassifier {
            override fun classify(window: Array<FloatArray>, windowEndTimestampNanos: Long): ActivityPrediction {
                return ActivityPrediction(
                    predictionId = "fake-pred",
                    windowEndTimestampNanos = windowEndTimestampNanos,
                    activity = com.example.sleeptrackersimulator.ml.ActivityClass.REST,
                    confidence = 0.95f,
                    probabilities = mapOf(
                        com.example.sleeptrackersimulator.ml.ActivityClass.REST to 0.95f,
                        com.example.sleeptrackersimulator.ml.ActivityClass.RESTLESS to 0.03f,
                        com.example.sleeptrackersimulator.ml.ActivityClass.ACTIVE to 0.02f,
                    ),
                    inferenceNanos = 120_000L,
                )
            }
            override fun close() {}
        }

        val engine = DefaultModelSelectionEngine(activityClassifier = fakeClassifier)
        val window = List(20) { makeSample() }

        val result = engine.selectAndClassify(window)

        assertEquals(InferenceMode.ACCELEROMETER_TFLITE, result.inferenceMode)
        assertEquals(ActivityClass.REST, result.activity)
        assertEquals(0.95f, result.confidence, 0.001f)
        assertEquals("activity-v2.1", result.modelId)
    }

    @Test
    fun testFallbackToRuleBasedWhenTfliteFails() {
        val throwingClassifier = object : ActivityClassifier {
            override fun classify(window: Array<FloatArray>, windowEndTimestampNanos: Long): ActivityPrediction {
                throw IllegalStateException("Native TFLite runtime crash simulation")
            }
            override fun close() {}
        }

        val engine = DefaultModelSelectionEngine(activityClassifier = throwingClassifier)
        // Gentle still window: x=0, y=0, z=9.81
        val window = List(20) { makeSample() }

        val result = engine.selectAndClassify(window)

        assertEquals(InferenceMode.RULE_BASED_FALLBACK, result.inferenceMode)
        assertEquals(ActivityClass.REST, result.activity)
        assertEquals("rule-based-fallback", result.modelId)
        assertTrue(result.qualityFlags.contains("FALLBACK_ENGAGED"))
        assertTrue(result.fallbackReason?.contains("tflite_inference_failed") == true)
    }

    @Test
    fun testFallbackHeuristicActiveDetection() {
        val engine = DefaultModelSelectionEngine(activityClassifier = null)
        // High motion window alternating violently between -5 and +5
        val window = List(20) { index ->
            val sign = if (index % 2 == 0) 1f else -1f
            makeSample(x = sign * 4.5f, y = sign * 3.0f, z = 9.81f + sign * 2.0f)
        }

        val result = engine.selectAndClassify(window)

        assertEquals(InferenceMode.RULE_BASED_FALLBACK, result.inferenceMode)
        assertEquals(ActivityClass.ACTIVE, result.activity)
        assertTrue(result.confidence >= 0.80f)
        assertEquals("rule-based-fallback", result.modelId)
    }

    @Test
    fun testMultimodalClassifierUsedWhenFreshHeartRateAvailable() {
        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = fakeMultimodalClassifier,
        )
        val window = List(20) { index -> makeSample(utcTimestampMillis = 1_700_000_000_000L + index * 50L) }
        val freshHr = makeHeartRate(bpm = 65, utcTimestampMillis = 1_700_000_000_500L)

        val result = engine.selectAndClassify(window, recentHeartRate = freshHr)

        assertEquals(InferenceMode.MULTIMODAL_TFLITE, result.inferenceMode)
        assertEquals(ActivityClass.REST, result.activity)
        assertEquals(0.98f, result.confidence, 0.001f)
        assertEquals("multimodal-tflite-v1.0", result.modelId)
    }

    @Test
    fun testMultimodalRejectsFutureHeartRate() {
        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = fakeMultimodalClassifier,
        )
        val window = List(20) { index -> makeSample(utcTimestampMillis = 1_700_000_000_000L + index * 50L) }
        // Future HR: 5 seconds in the future relative to the window's last sample
        val futureHr = makeHeartRate(bpm = 65, utcTimestampMillis = 1_700_000_006_000L)

        val result = engine.selectAndClassify(window, recentHeartRate = futureHr)

        assertEquals(InferenceMode.ACCELEROMETER_TFLITE, result.inferenceMode)
        assertEquals(ActivityClass.REST, result.activity)
        assertTrue(result.qualityFlags.contains("FUTURE_HEART_RATE"))
        assertTrue(result.fallbackReason?.contains("future_heart_rate") == true)
    }

    @Test
    fun testMultimodalFallsBackToAccelerometerWhenHeartRateStale() {
        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = fakeMultimodalClassifier,
        )
        val window = List(20) { makeSample(utcTimestampMillis = 1_700_000_020_000L) }
        // 20 seconds old -> exceeds 10s max staleness
        val staleHr = makeHeartRate(bpm = 65, utcTimestampMillis = 1_700_000_000_000L)

        val result = engine.selectAndClassify(window, recentHeartRate = staleHr)

        assertEquals(InferenceMode.ACCELEROMETER_TFLITE, result.inferenceMode)
        assertEquals(ActivityClass.REST, result.activity)
        assertEquals("activity-v2.1", result.modelId)
        assertTrue(result.qualityFlags.contains("STALE_HEART_RATE"))
    }

    @Test
    fun testMultimodalFallsBackToAccelerometerWhenHeartRateNull() {
        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = fakeMultimodalClassifier,
        )
        val window = List(20) { makeSample() }

        val result = engine.selectAndClassify(window, recentHeartRate = null)

        assertEquals(InferenceMode.ACCELEROMETER_TFLITE, result.inferenceMode)
        assertEquals(ActivityClass.REST, result.activity)
        assertEquals("activity-v2.1", result.modelId)
        assertTrue(result.qualityFlags.contains("NO_HEART_RATE_AVAILABLE"))
    }

    @Test
    fun testMultimodalFallsBackToAccelerometerWhenHeartRateOutOfRange() {
        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = fakeMultimodalClassifier,
        )
        val window = List(20) { makeSample() }
        val invalidHr = makeHeartRate(bpm = 15) // Physically impossible sleep HR

        val result = engine.selectAndClassify(window, recentHeartRate = invalidHr)

        assertEquals(InferenceMode.ACCELEROMETER_TFLITE, result.inferenceMode)
        assertTrue(result.qualityFlags.contains("INVALID_HEART_RATE"))
    }

    @Test
    fun testMultimodalSetsSimulatedHeartRateQualityFlag() {
        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = fakeMultimodalClassifier,
        )
        val window = List(20) { makeSample(utcTimestampMillis = 1_700_000_000_000L) }
        val simHr = makeHeartRate(
            bpm = 62,
            utcTimestampMillis = 1_700_000_000_000L,
            sourceKind = SourceKind.SIMULATED,
        )

        val result = engine.selectAndClassify(window, recentHeartRate = simHr)

        assertEquals(InferenceMode.MULTIMODAL_TFLITE, result.inferenceMode)
        assertTrue(result.qualityFlags.contains("SIMULATED_HEART_RATE"))
    }

    @Test
    fun testMultimodalThrowsFallsBackToAccelerometer() {
        val throwingMulti = object : MultimodalClassifier {
            override fun classify(
                accelerometerWindow: Array<FloatArray>,
                heartRateBpm: Float?,
                isHeartRateFresh: Boolean,
                windowEndTimestampNanos: Long,
            ): ActivityPrediction {
                throw IllegalStateException("Multimodal native crash")
            }
            override fun close() {}
        }

        val engine = DefaultModelSelectionEngine(
            activityClassifier = fakeAccelClassifier,
            multimodalClassifier = throwingMulti,
        )
        val window = List(20) { makeSample() }
        val freshHr = makeHeartRate(bpm = 65)

        val result = engine.selectAndClassify(window, recentHeartRate = freshHr)

        assertEquals(InferenceMode.ACCELEROMETER_TFLITE, result.inferenceMode)
        assertEquals("activity-v2.1", result.modelId)
    }
}
