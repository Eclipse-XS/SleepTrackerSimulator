package com.example.sleeptrackersimulator.ml

import com.example.sleeptrackersimulator.core.model.ActivityClass
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
    ) = RawSensorSample(
        sampleId = 0L,
        sessionId = "test-session",
        sourceId = "phone_accelerometer",
        elapsedRealtimeNanos = elapsedNanos,
        utcTimestampMillis = 1_700_000_000_000L,
        x = x,
        y = y,
        z = z,
        sensorAccuracy = 3,
        sourceKind = SourceKind.PHONE_REAL,
    )

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
}
