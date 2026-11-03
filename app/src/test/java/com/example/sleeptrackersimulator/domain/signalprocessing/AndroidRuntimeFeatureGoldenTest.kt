package com.example.sleeptrackersimulator.domain.signalprocessing

import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Produces the golden vectors for `android_runtime_features_v1`. The Python suite
 * (`tests/data/test_android_runtime_features_parity.py`) recomputes the same scenarios and must
 * match this file within the documented tolerance (rtol 1e-4, atol 1e-5).
 */
class AndroidRuntimeFeatureGoldenTest {
    private data class Scenario(val name: String, val elapsedNs: List<Long>, val xyz: List<Triple<Float, Float, Float>>)

    private val g = 9.80665f
    private fun grid(n: Int) = List(n) { 1_000_000_000L + it * 50_000_000L }

    private fun scenarios(): List<Scenario> {
        val stationary = List(20) { Triple(0f, 0f, g) }
        val inverted = List(20) { Triple(0f, 0f, -g) }
        val pulse = List(20) { i -> if (i in 8..11) Triple(2.0f, 0f, g + 3.0f) else Triple(0f, 0f, g) }
        val varying = List(20) { i ->
            Triple((2.0 * sin(0.5 * i)).toFloat(), (1.5 * cos(0.3 * i)).toFloat(), (9.5 + 0.8 * sin(0.9 * i)).toFloat())
        }
        val jitterTimes = List(20) { i -> 1_000_000_000L + i * 50_000_000L + ((i * 37 % 11) - 5) * 2_000_000L }
        val missing = varying.filterIndexed { i, _ -> i != 7 }
        val missingTimes = grid(20).filterIndexed { i, _ -> i != 7 }
        return listOf(
            Scenario("stationary_gravity", grid(20), stationary),
            Scenario("inverted_device", grid(20), inverted),
            Scenario("movement_pulse", grid(20), pulse),
            Scenario("varying_axis_motion", grid(20), varying),
            Scenario("timestamp_jitter", jitterTimes, varying),
            Scenario("missing_sample", missingTimes, missing),
        )
    }

    private fun compute(s: Scenario) = FeatureExtractor.extractFeatures(
        s.elapsedNs.indices.map { i ->
            val (x, y, z) = s.xyz[i]
            RawSensorSample(
                sessionId = "golden", sourceId = "golden", elapsedRealtimeNanos = s.elapsedNs[i],
                utcTimestampMillis = 1_700_000_000_000L + s.elapsedNs[i] / 1_000_000L, x = x, y = y, z = z, sensorAccuracy = 3,
            )
        },
    )

    @Test
    fun `kotlin features match committed golden vectors`() {
        val scenariosOut = scenarios().map { s ->
            val w = compute(s)
            mapOf(
                "name" to s.name,
                "expected_sample_count" to 20,
                "samples" to s.elapsedNs.indices.map { i ->
                    mapOf("elapsed_ns" to s.elapsedNs[i], "x" to s.xyz[i].first, "y" to s.xyz[i].second, "z" to s.xyz[i].third, "accuracy" to 3)
                },
                "expected" to mapOf(
                    "sample_count" to w.sampleCount,
                    "coverage_fraction" to w.coverageFraction,
                    "motion_rms" to w.motionRms,
                    "variance" to w.variance,
                    "jerk" to w.jerk,
                    "peak_to_peak" to w.peakToPeak,
                    "quality_score" to w.qualityScore,
                ),
            )
        }
        val json = GsonBuilder().setPrettyPrinting().create().toJson(
            mapOf("feature_contract" to "android_runtime_features_v1", "gravity" to g, "scenarios" to scenariosOut),
        )
        val file = File("../tests/data/golden/android_runtime_features_v1.json")
        if (!file.exists() || System.getenv("UPDATE_FIXTURES") == "1") {
            file.parentFile.mkdirs()
            file.writeText(json.replace("\r\n", "\n"), Charsets.UTF_8)
        }
        val golden = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("scenarios")
        assertEquals(scenariosOut.size, golden.size())
        scenarios().forEachIndexed { i, s ->
            val exp = golden[i].asJsonObject.getAsJsonObject("expected")
            val w = compute(s)
            assertEquals(s.name, exp["sample_count"].asInt, w.sampleCount)
            assertEquals(s.name, exp["motion_rms"].asDouble, w.motionRms.toDouble(), 1e-6)
            assertEquals(s.name, exp["variance"].asDouble, w.variance.toDouble(), 1e-6)
            assertEquals(s.name, exp["jerk"].asDouble, w.jerk.toDouble(), 1e-4)
            assertEquals(s.name, exp["peak_to_peak"].asDouble, w.peakToPeak.toDouble(), 1e-6)
            assertEquals(s.name, exp["coverage_fraction"].asDouble, w.coverageFraction.toDouble(), 1e-6)
        }
    }
}
