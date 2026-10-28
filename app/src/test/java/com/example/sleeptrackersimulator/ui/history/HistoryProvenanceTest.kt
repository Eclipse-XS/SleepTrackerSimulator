package com.example.sleeptrackersimulator.ui.history

import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HistoryProvenanceTest {
    @Test fun wearableWithoutPersistedSummaryIsNeverCalledReal() {
        assertEquals(SourceKind.BLE_UNVERIFIED, fallbackProvenance(SourceMode.WEARABLE_ONLY))
        assertEquals(SourceKind.BLE_UNVERIFIED, fallbackProvenance(SourceMode.COMBINED))
        assertNotEquals(SourceKind.BLE_REAL, fallbackProvenance(SourceMode.WEARABLE_ONLY))
    }

    @Test fun syntheticAndReplayFallbacksStayExplicit() {
        assertEquals(SourceKind.SIMULATED, fallbackProvenance(SourceMode.DEMO))
        assertEquals(SourceKind.REPLAY, fallbackProvenance(SourceMode.REPLAY))
    }
}
