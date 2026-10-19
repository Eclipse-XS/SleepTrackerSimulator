package com.example.sleeptrackersimulator.ml

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ActivityClassifierInstrumentedTest {
    @Test fun realTfliteModelClassifiesRestWindow() {
        ActivityClassifier(ApplicationProvider.getApplicationContext()).use { classifier ->
            val window = Array(20) { floatArrayOf(0f, 0f, 9.81f) }
            val result = classifier.classify(window)
            assertEquals(ActivityClass.REST, result.activity)
            assertTrue(result.confidence > 0.5f)
            assertTrue(abs(result.probabilities.values.sum() - 1f) < 0.001f)
            assertTrue(result.inferenceNanos > 0L)
        }
    }

    @Test fun realTfliteModelReturnsThreeFiniteProbabilities() {
        ActivityClassifier(ApplicationProvider.getApplicationContext()).use { classifier ->
            val window = Array(20) { index ->
                val wave = sin(index * 1.7).toFloat() * 2.8f
                floatArrayOf(wave, -wave * .6f, 9.81f + wave * .4f)
            }
            val result = classifier.classify(window)
            assertEquals(ActivityClass.entries.toSet(), result.probabilities.keys)
            assertTrue(result.probabilities.values.all { it.isFinite() && it in 0f..1f })
        }
    }
}
