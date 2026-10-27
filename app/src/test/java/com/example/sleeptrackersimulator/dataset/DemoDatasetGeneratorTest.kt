package com.example.sleeptrackersimulator.dataset

import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDatasetGeneratorTest {
    @Test fun generatesNinetyValidDeterministicNights() {
        val first = DemoDatasetGenerator(7).generate()
        val second = DemoDatasetGenerator(7).generate()
        assertEquals(90, first.size)
        assertEquals(first, second)
        assertTrue(DemoDatasetGenerator(7).validate(first).isEmpty())
    }

    @Test fun syntheticProvenanceAndStableIdsAreMandatory() {
        val nights = DemoDatasetGenerator().generate()
        assertEquals(90, nights.map { it.session.sessionId }.distinct().size)
        assertTrue(nights.all { it.session.sourceMode == SourceMode.DEMO })
        assertTrue(nights.all { it.summary.sourceKind == SourceKind.SIMULATED })
        assertTrue(nights.all { it.session.sessionId.startsWith("demo-v1-") })
    }

    @Test fun csvContainsAllRecordsAndProvenance() {
        val csv = DemoDatasetGenerator().toCsv(DemoDatasetGenerator().generate())
        assertEquals(91, csv.lineSequence().filter { it.isNotBlank() }.count())
        assertTrue(csv.contains("SIMULATED"))
    }
}
