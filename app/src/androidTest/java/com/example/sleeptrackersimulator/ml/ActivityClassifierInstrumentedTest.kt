package com.example.sleeptrackersimulator.ml

import androidx.test.core.app.ApplicationProvider
import android.util.Log
import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityClassifierInstrumentedTest {
    @Test
    fun modelLoadsAndProducesThreeProbabilities() {
        ActivityClassifier(ApplicationProvider.getApplicationContext()).use { classifier ->
            val samples = List(20) { index ->
                SensorSample(0.01f * (index % 2), 0f, 9.81f, index + 1L)
            }
            val prediction = classifier.classify(samples)
            assertEquals(3, prediction.probabilities.size)
            assertTrue(prediction.probabilities.all { it.isFinite() && it in 0f..1f })
            assertEquals(1f, prediction.probabilities.sum(), 0.001f)

            repeat(10) { classifier.classify(samples) }
            val latencies = List(100) { classifier.classify(samples).inferenceMillis }.sorted()
            val mean = latencies.average()
            val p95 = latencies[94]
            Log.i("Lab6MlBenchmark", "samples=100 meanMs=$mean p95Ms=$p95")
            assertTrue(p95 < 100.0)
        }
    }
}
