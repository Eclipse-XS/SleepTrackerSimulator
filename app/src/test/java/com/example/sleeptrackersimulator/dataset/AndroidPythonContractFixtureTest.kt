package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.ClockAnchor
import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generates (or verifies) the deterministic Android export fixtures that the Python test-suite
 * ingests (`tests/data/test_android_python_contract.py`). Both sides read the same committed files,
 * so the Kotlin serializer and the Python reader are tied together.
 *
 * Fixture timeline (50 ms cadence, UTC = 1_700_000_000_000 + t):
 *  - samples 0..59  at t = i*50 ms                     (RESTING for 0..29, READING for 30..59)
 *  - sample 10 is NaN, sample 45 is out of range      (rejected, kept for audit)
 *  - samples 60..79 resume at t = 4500 + (i-60)*50 ms  (a 1.55 s gap with no observations)
 *  - HR (WEBSOCKET_SIMULATED): t=1900 (62 bpm), t=1990 (99 bpm, after the window-1 cutoff 1950), t=2100 (70 bpm)
 *  - predicted_motion_class is deliberately different from the annotation.
 */
class AndroidPythonContractFixtureTest {
    private val baseElapsed = 1_000_000_000L
    private val baseUtc = 1_700_000_000_000L
    private val fixtureDir = File("../tests/data/fixtures/android_contract")

    private fun buildSession(): DatasetSession {
        val controller = DatasetRecordingController(clockAnchorProvider = { ClockAnchor(baseElapsed, baseUtc) })
        controller.start(ExperimentType.READING_IN_BED, DevicePlacement.NIGHTSTAND, GroundTruthActivity.RESTING)
        for (i in 0 until 80) {
            if (i == 30) controller.updateGroundTruth(GroundTruthActivity.READING)
            val tMs = if (i < 60) i * 50L else 4_500L + (i - 60) * 50L
            val pulse = if (i in 30..39) 1.2f * (i % 2) else 0f
            var x = 0.01f + 0.002f * (i % 3) + pulse
            val y = -0.015f
            val z = 9.805f + 0.003f * (i % 4)
            if (i == 10) x = Float.NaN
            if (i == 45) x = 500f
            controller.processSample(
                SensorSample(x, y, z, timestampNanos = baseElapsed + tMs * 1_000_000L),
                predictedClass = if (i < 30) "ACTIVE" else "REST",
            )
        }
        return controller.stop().copy(sessionId = "fixture-contract-session-001", deviceModel = "Fixture Device")
    }

    private fun hr(utcOffsetMs: Long, bpm: Int) = mapOf(
        "deviceId" to "virtual-band-01",
        "bpm" to bpm,
        "utcTimestampMillis" to baseUtc + utcOffsetMs,
        "sourceKind" to "WEBSOCKET_SIMULATED",
    )

    private fun verifyOrWrite(name: String, content: String) {
        fixtureDir.mkdirs()
        val file = File(fixtureDir, name)
        val normalized = content.replace("\r\n", "\n")
        if (!file.exists() || System.getenv("UPDATE_FIXTURES") == "1") {
            file.writeText(normalized, Charsets.UTF_8)
        } else {
            assertEquals("fixture $name drifted from the Kotlin serializer", file.readText().replace("\r\n", "\n"), normalized)
        }
    }

    @Test
    fun `fixture reflects controller semantics and matches committed files`() {
        val session = buildSession()
        assertEquals(80, session.totalSamples)
        assertEquals(78, session.validCount)
        assertEquals(2, session.rejectedCount)
        assertEquals("NON_FINITE_VALUE", session.samples[10].rejectionReason)
        assertEquals("OUT_OF_RANGE_EXCESSIVE_G", session.samples[45].rejectionReason)
        assertEquals("RESTING", session.samples[29].groundTruthLabel)
        assertEquals("READING", session.samples[30].groundTruthLabel)
        assertTrue(session.samples.all { it.predictedMotionClass != it.groundTruthLabel })
        assertNotEquals("labels and predictions must stay separate", session.samples[0].groundTruthLabel, session.samples[0].predictedMotionClass)

        val hrSamples = listOf(hr(1_900, 62), hr(1_990, 99), hr(2_100, 70))
        verifyOrWrite("android_contract_fixture.csv", DatasetCsvExporter.generateCsv(session))
        verifyOrWrite("android_contract_fixture.json", DatasetJsonExporter.generateJson(session, hrSamples))
    }
}
