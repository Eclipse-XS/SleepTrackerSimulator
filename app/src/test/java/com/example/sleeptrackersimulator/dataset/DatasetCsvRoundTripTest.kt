package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetCsvRoundTripTest {

    @Test
    fun `dataset recording, csv export and parse round-trip preserves all measurements and metadata`() {
        val controller = DatasetRecordingController()

        controller.start(
            experiment = ExperimentType.CONTROLLED_REST,
            devicePlacement = DevicePlacement.MATTRESS,
            groundTruth = GroundTruthActivity.RESTING,
        )

        // Feed 50 valid rest samples
        var baseNanos = 1_000_000_000L
        for (i in 0 until 50) {
            val s = SensorSample(
                x = 0.05f + (i * 0.001f),
                y = 0.12f,
                z = 9.81f,
                timestampNanos = baseNanos,
            )
            controller.processSample(s, predictedClass = "REST")
            baseNanos += 50_000_000L // 20 Hz (50 ms)
        }

        // Feed 1 non-finite sample (rejected)
        val nanSample = SensorSample(
            x = Float.NaN,
            y = 0.0f,
            z = 9.8f,
            timestampNanos = baseNanos,
        )
        val rejectedEntry = controller.processSample(nanSample, predictedClass = "UNKNOWN")
        assertFalse(rejectedEntry.isValid)
        assertEquals("NON_FINITE_VALUE", rejectedEntry.rejectionReason)
        baseNanos += 50_000_000L

        // Feed 1 out-of-range sample (> 50 m/s^2, rejected)
        val extremeSample = SensorSample(
            x = 95.0f,
            y = 0.0f,
            z = 0.0f,
            timestampNanos = baseNanos,
        )
        val extremeEntry = controller.processSample(extremeSample, predictedClass = "ACTIVE")
        assertFalse(extremeEntry.isValid)
        assertEquals("OUT_OF_RANGE_EXCESSIVE_G", extremeEntry.rejectionReason)
        baseNanos += 50_000_000L

        // Change ground truth to READING
        controller.updateGroundTruth(GroundTruthActivity.READING)
        for (i in 0 until 20) {
            val s = SensorSample(
                x = 1.2f,
                y = 2.1f,
                z = 9.5f,
                timestampNanos = baseNanos,
            )
            controller.processSample(s, predictedClass = "RESTLESS")
            baseNanos += 50_000_000L
        }

        val session = controller.stop()

        assertEquals(72, session.totalSamples)
        assertEquals(70, session.validCount)
        assertEquals(2, session.rejectedCount)

        // 1. Export to CSV string
        val csv = DatasetCsvExporter.generateCsv(session)

        assertTrue(csv.contains("# ExperimentType: CONTROLLED_REST"))
        assertTrue(csv.contains("# Placement: MATTRESS"))
        assertTrue(csv.contains("# ValidSamples: 70"))
        assertTrue(csv.contains("# RejectedSamples: 2"))
        assertTrue(csv.contains("timestamp_utc_ms,elapsed_realtime_ns,accel_x,accel_y,accel_z,dynamic_magnitude,is_valid,ground_truth_label,predicted_motion_class"))

        // 2. Parse back from CSV
        val parsed = DatasetCsvExporter.parseCsv(csv)

        assertEquals(session.sessionId, parsed.sessionId)
        assertEquals(ExperimentType.CONTROLLED_REST, parsed.experimentType)
        assertEquals(DevicePlacement.MATTRESS, parsed.placement)
        assertEquals(72, parsed.samples.size)
        assertEquals(70, parsed.validCount)
        assertEquals(2, parsed.rejectedCount)

        // Check first sample values
        val firstOrig = session.samples.first()
        val firstParsed = parsed.samples.first()
        assertEquals(firstOrig.timestampUtcMs, firstParsed.timestampUtcMs)
        assertEquals(firstOrig.x, firstParsed.x, 0.0001f)
        assertEquals(firstOrig.y, firstParsed.y, 0.0001f)
        assertEquals(firstOrig.z, firstParsed.z, 0.0001f)
        assertEquals("RESTING", firstParsed.groundTruthLabel)
        assertEquals("REST", firstParsed.predictedMotionClass)

        // Check ground-truth label transition
        val lastParsed = parsed.samples.last()
        assertEquals("READING", lastParsed.groundTruthLabel)
        assertEquals("RESTLESS", lastParsed.predictedMotionClass)
    }
}
