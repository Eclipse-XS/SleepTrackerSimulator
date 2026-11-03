package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.ClockAnchor
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DE-001: UTC timestamps are derived from sensor event time through one session ClockAnchor. */
class DatasetTimestampContractTest {
    private val anchor = ClockAnchor(elapsedRealtimeNanos = 5_000_000_000L, utcTimestampMillis = 1_700_000_000_000L)

    private fun record(count: Int = 20): DatasetSession {
        var providerCalls = 0
        val controller = DatasetRecordingController(clockAnchorProvider = { providerCalls++; anchor })
        controller.start(ExperimentType.CONTROLLED_REST, DevicePlacement.MATTRESS, GroundTruthActivity.RESTING)
        // No sleeping: all samples are fed back-to-back, processing speed must not matter.
        repeat(count) { i ->
            controller.processSample(
                SensorSample(0.01f, -0.02f, 9.8f, timestampNanos = 5_000_000_000L + i * 50_000_000L),
            )
        }
        assertEquals("one anchor per session", 1, providerCalls)
        return controller.stop()
    }

    @Test
    fun `utc and elapsed advance by exactly 50 ms regardless of processing speed`() {
        val session = record()
        assertEquals(20, session.samples.size)
        session.samples.zipWithNext().forEach { (a, b) ->
            assertEquals(50_000_000L, b.elapsedRealtimeNanos - a.elapsedRealtimeNanos)
            assertEquals(50L, b.timestampUtcMs - a.timestampUtcMs)
        }
        assertEquals(950L, session.samples.last().timestampUtcMs - session.samples.first().timestampUtcMs)
        assertEquals(1_700_000_000_000L, session.samples.first().timestampUtcMs)
        assertEquals(5_000_000_000L, session.samples.first().elapsedRealtimeNanos)
    }

    @Test
    fun `csv and json carry identical consistent timestamps`() {
        val session = record()
        val csvRows = DatasetCsvExporter.generateCsv(session).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("timestamp_utc_ms") }
            .map { it.split(',') }
        val json = JsonParser.parseString(DatasetJsonExporter.generateJson(session)).asJsonObject
        val jsonSamples = json.getAsJsonArray("samples")
        assertEquals(20, csvRows.size)
        assertEquals(20, jsonSamples.size())
        csvRows.forEachIndexed { i, row ->
            val js = jsonSamples[i].asJsonObject
            assertEquals(row[0].toLong(), js["utcTimestampMillis"].asLong)
            assertEquals(row[1].toLong(), js["elapsedRealtimeNanos"].asLong)
        }
        val utc = csvRows.map { it[0].toLong() }
        assertTrue(utc.zipWithNext().all { (a, b) -> b - a == 50L })
    }
}
