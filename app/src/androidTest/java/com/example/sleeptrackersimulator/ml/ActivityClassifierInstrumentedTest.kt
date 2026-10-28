package com.example.sleeptrackersimulator.ml

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sin

class ActivityClassifierInstrumentedTest {
    @Test fun sharedClassifierSerializesConcurrentNativeInference() {
        val classifier = ActivityClassifier(ApplicationProvider.getApplicationContext())
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(2) { worker ->
            executor.execute {
                try {
                    start.await()
                    repeat(100) { iteration ->
                        val phase = worker * 100 + iteration
                        val window = Array(20) { index ->
                            val wave = sin((index + phase) * 0.31).toFloat()
                            floatArrayOf(wave, -wave, 9.81f + wave)
                        }
                        classifier.classify(window)
                    }
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    completed.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("Concurrent inference timed out", completed.await(30, TimeUnit.SECONDS))
        executor.shutdownNow()
        classifier.close()
        assertTrue("Concurrent inference failed: $failures", failures.isEmpty())
    }

    @Test fun closeWaitsForNativeInferenceAndPreventsUseAfterClose() {
        val classifier = ActivityClassifier(ApplicationProvider.getApplicationContext())
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val unexpected = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val window = Array(20) { floatArrayOf(0f, 0f, 9.81f) }

        executor.execute {
            try {
                started.countDown()
                repeat(100) { classifier.classify(window) }
            } catch (closed: IllegalStateException) {
                // Closing between serialized calls is an expected terminal outcome.
            } catch (error: Throwable) {
                unexpected.add(error)
            } finally {
                completed.countDown()
            }
        }
        executor.execute {
            try {
                started.await()
                classifier.close()
            } catch (error: Throwable) {
                unexpected.add(error)
            } finally {
                completed.countDown()
            }
        }

        assertTrue("Concurrent close timed out", completed.await(30, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertTrue("Native inference/close failed: $unexpected", unexpected.isEmpty())
        val afterClose = runCatching { classifier.classify(window) }.exceptionOrNull()
        assertTrue(afterClose is IllegalStateException)
    }

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

    @Test fun androidInferenceMatchesPythonTfliteGoldenVector() {
        // Generated with ml/.venv and the checked-in TFLite asset, not the Keras model.
        val pythonGolden = floatArrayOf(1.6910271e-5f, 1.0084729e-5f, 0.99997294f)
        ActivityClassifier(ApplicationProvider.getApplicationContext()).use { classifier ->
            val window = Array(20) { index ->
                val wave = sin(index * 1.7).toFloat() * 2.8f
                floatArrayOf(wave, -wave * .6f, 9.81f + wave * .4f)
            }
            val actual = classifier.classify(window).probabilities.values.toFloatArray()
            actual.indices.forEach { index ->
                assertTrue("probability[$index] differs from Python TFLite", abs(actual[index] - pythonGolden[index]) < 1e-5f)
            }
        }
    }

    @Test fun androidInferenceMatchesAllTwelveGoldenVectors() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jsonContent = context.assets.open("ml_golden_vectors.json").bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(jsonContent)
        val tolerance = root.getDouble("tolerance")
        val vectorsArray = root.getJSONArray("vectors")

        ActivityClassifier(context).use { classifier ->
            for (i in 0 until vectorsArray.length()) {
                val item = vectorsArray.getJSONObject(i)
                val name = item.getString("name")
                val expectedClass = item.getString("expected_predicted_class")
                val expectedProbsObj = item.getJSONObject("expected_probabilities")
                val rawInput = item.getJSONArray("input_window")

                val window = Array(20) { r ->
                    val sample = rawInput.getJSONArray(r)
                    floatArrayOf(
                        sample.getDouble(0).toFloat(),
                        sample.getDouble(1).toFloat(),
                        sample.getDouble(2).toFloat(),
                    )
                }

                val result = classifier.classify(window)
                assertEquals("Mismatch in predicted class for $name", expectedClass, result.activity.name)

                for (activity in ActivityClass.entries) {
                    val expectedP = expectedProbsObj.getDouble(activity.name).toFloat()
                    val actualP = result.probabilities[activity] ?: 0f
                    assertTrue(
                        "Discrepancy in probability for $name on class ${activity.name}: expected $expectedP, actual $actualP",
                        abs(actualP - expectedP) < tolerance,
                    )
                }
            }
        }
    }
}
