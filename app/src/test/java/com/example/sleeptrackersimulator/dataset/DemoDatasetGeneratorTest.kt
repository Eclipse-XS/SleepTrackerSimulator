package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DemoDatasetGeneratorTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-27T17:00:00Z"), ZoneId.of("Europe/Kyiv"))

    @Test fun generatesNinetyValidDeterministicNights() {
        val first = DemoDatasetGenerator(7, clock).generate()
        val second = DemoDatasetGenerator(7, clock).generate()
        assertEquals(90, first.size)
        assertEquals(first, second)
        assertTrue(DemoDatasetGenerator(7, clock).validate(first).isEmpty())
        assertTrue(first.all { requireNotNull(it.session.endUtcMillis) <= clock.millis() })
        assertTrue(first.zipWithNext().all { (a, b) -> a.session.startUtcMillis < b.session.startUtcMillis })
    }

    @Test fun syntheticProvenanceAndStableIdsAreMandatory() {
        val nights = DemoDatasetGenerator(clock = clock).generate()
        assertEquals(90, nights.map { it.session.sessionId }.distinct().size)
        assertTrue(nights.all { it.session.sourceMode == SourceMode.DEMO })
        assertTrue(nights.all { it.summary.sourceKind == SourceKind.SIMULATED })
        assertTrue(nights.all { it.session.sessionId.startsWith("demo-v1-") })
    }

    @Test fun csvContainsAllRecordsAndProvenance() {
        val generator = DemoDatasetGenerator(clock = clock)
        val csv = generator.toCsv(generator.generate())
        assertEquals(91, csv.lineSequence().filter { it.isNotBlank() }.count())
        assertTrue(csv.contains("SIMULATED"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFutureEndDate() {
        DemoDatasetGenerator(clock = clock).generate(endDate = LocalDate.of(2026, 10, 28))
    }

    @Test fun calendarSpacingSurvivesDaylightSavingChange() {
        val nights = DemoDatasetGenerator(clock = clock).generate(count = 5, endDate = LocalDate.of(2026, 10, 27))
        val localDates = nights.map { Instant.ofEpochMilli(requireNotNull(it.session.endUtcMillis)).atZone(clock.zone).toLocalDate() }
        assertEquals((23..27).map { LocalDate.of(2026, 10, it) }, localDates)
    }
}
