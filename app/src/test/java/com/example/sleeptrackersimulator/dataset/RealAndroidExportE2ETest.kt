package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RealAndroidExportE2ETest {

    @Test
    fun `export actual android session to csv and json files for python pipeline verification`() {
        val controller = DatasetRecordingController()

        controller.start(
            experiment = ExperimentType.CONTROLLED_REST,
            devicePlacement = DevicePlacement.MATTRESS,
            groundTruth = GroundTruthActivity.RESTING,
        )

        var baseUtc = 1700000000000L
        var baseNanos = 1_000_000_000L

        // Generate 80 valid resting samples (4 windows of 20 samples at 20 Hz)
        for (i in 0 until 80) {
            val sample = SensorSample(
                x = 0.012f + (0.001f * (i % 3)),
                y = -0.015f + (0.001f * (i % 2)),
                z = 9.805f + (0.002f * (i % 4)),
                timestampNanos = baseNanos,
            )
            controller.processSample(sample, predictedClass = "REST")
            baseNanos += 50_000_000L // 50 ms
            baseUtc += 50L
        }

        val session = controller.stop()
        requireNotNull(session)
        assertEquals(80, session.totalSamples)
        assertEquals(80, session.validCount)

        // 1. Generate real CSV using DatasetCsvExporter
        val csvContent = DatasetCsvExporter.generateCsv(session)
        assertTrue(csvContent.contains("# SleepSense Ground-Truth Accelerometry Dataset v2.0"))
        assertTrue(csvContent.contains("# SessionId: ${session.sessionId}"))
        assertTrue(csvContent.contains("timestamp_utc_ms,elapsed_realtime_ns,accel_x,accel_y,accel_z"))

        // 2. Generate real JSON using DatasetJsonExporter
        val hrSamples = (0 until 4).map { i ->
            mapOf(
                "deviceId" to "virtual-band-01",
                "bpm" to (65 + i),
                "utcTimestampMillis" to (1700000000000L + (i * 1000L)),
                "sourceKind" to "BLE_REAL",
            )
        }
        val jsonContent = DatasetJsonExporter.generateJson(session, hrSamples)
        assertTrue(jsonContent.contains("\"sessionId\":\"${session.sessionId}\""))
        assertTrue(jsonContent.contains("\"heartRateSamples\""))

        // 3. Write actual files to build directory for Python E2E verification
        val outputDir = File("../build/real_exports").apply { mkdirs() }
        val csvFile = File(outputDir, "android_actual_export.csv")
        csvFile.writeText(csvContent, Charsets.UTF_8)
        assertTrue(csvFile.exists() && csvFile.length() > 0)

        val jsonFile = File(outputDir, "android_actual_export.json")
        jsonFile.writeText(jsonContent, Charsets.UTF_8)
        assertTrue(jsonFile.exists() && jsonFile.length() > 0)
    }
}
