package com.example.sleeptrackersimulator.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataProvenanceTest {

    @Test
    fun `sourceKind distinguishes real hardware from synthetic signals`() {
        assertTrue(SourceKind.PHONE_REAL.isRealData)
        assertTrue(SourceKind.BLE_REAL.isRealData)

        assertFalse(SourceKind.SIMULATED.isRealData)
        assertTrue(SourceKind.SIMULATED.isSimulated)

        assertFalse(SourceKind.REPLAY.isRealData)
        assertTrue(SourceKind.REPLAY.isReplay)
    }

    @Test
    fun `devicePlacement from string handles case and unknown`() {
        assertEquals(DevicePlacement.MATTRESS, DevicePlacement.fromString("mattress"))
        assertEquals(DevicePlacement.NIGHTSTAND, DevicePlacement.fromString("NIGHTSTAND"))
        assertEquals(DevicePlacement.BODY_WRIST, DevicePlacement.fromString("BODY_WRIST"))
        assertEquals(DevicePlacement.UNKNOWN, DevicePlacement.fromString("non_existent"))
        assertEquals(DevicePlacement.UNKNOWN, DevicePlacement.fromString(null))
    }
}

class SummaryAndBaselineTest {

    @Test
    fun `session summary detects comparability with baseline`() {
        val comparableSummary = SessionSummary(
            sessionId = "s1",
            observationDurationMs = 60 * 60 * 1000L, // 1 hour
            validDurationMs = 58 * 60 * 1000L,
            dataCoverageFraction = 0.96f,
            lowMotionFraction = 0.82f,
            movementEpisodeCount = 5,
            movementRatePerHour = 5.0f,
            sourceKind = SourceKind.PHONE_REAL,
            devicePlacement = DevicePlacement.MATTRESS,
        )
        assertTrue(comparableSummary.isComparableWithBaseline)

        val shortSummary = comparableSummary.copy(observationDurationMs = 15 * 60 * 1000L) // 15 mins
        assertFalse(shortSummary.isComparableWithBaseline)

        val simulatedSummary = comparableSummary.copy(sourceKind = SourceKind.SIMULATED)
        assertFalse(simulatedSummary.isComparableWithBaseline)

        val lowCoverageSummary = comparableSummary.copy(dataCoverageFraction = 0.70f)
        assertFalse(lowCoverageSummary.isComparableWithBaseline)
    }

    @Test
    fun `personal baseline calculates comparison metrics when compatible`() {
        val baseline = PersonalBaseline(
            baselineId = "b1",
            sourceMode = SourceMode.PHONE_ONLY,
            placement = DevicePlacement.MATTRESS,
            compatibleSessionCount = 7,
            medianLowMotionFraction = 0.80f,
            madLowMotionFraction = 0.04f,
            medianMovementRatePerHour = 6.0f,
            validFromUtcMillis = 1000L,
        )
        assertTrue(baseline.isReliable)

        val session = SessionSummary(
            sessionId = "s-new",
            observationDurationMs = 7 * 60 * 60 * 1000L,
            validDurationMs = 7 * 60 * 60 * 1000L,
            dataCoverageFraction = 0.98f,
            lowMotionFraction = 0.85f,
            movementEpisodeCount = 7,
            movementRatePerHour = 1.0f,
            sourceKind = SourceKind.PHONE_REAL,
            devicePlacement = DevicePlacement.MATTRESS,
        )

        val comparison = baseline.compare(session)
        assertNotNull(comparison)
        assertEquals(5.0f, comparison!!.differencePercentagePoints, 0.01f)
        assertTrue(comparison.isWithinNormalVariation) // diff 0.05 <= 2 * 0.04 (0.08)
    }

    @Test
    fun `personal baseline rejects comparison when placements differ`() {
        val baseline = PersonalBaseline(
            baselineId = "b1",
            sourceMode = SourceMode.PHONE_ONLY,
            placement = DevicePlacement.MATTRESS,
            compatibleSessionCount = 7,
            medianLowMotionFraction = 0.80f,
            madLowMotionFraction = 0.04f,
            medianMovementRatePerHour = 6.0f,
            validFromUtcMillis = 1000L,
        )

        val nightstandSession = SessionSummary(
            sessionId = "s-diff",
            observationDurationMs = 7 * 60 * 60 * 1000L,
            validDurationMs = 7 * 60 * 60 * 1000L,
            dataCoverageFraction = 0.98f,
            lowMotionFraction = 0.99f,
            movementEpisodeCount = 0,
            movementRatePerHour = 0.0f,
            sourceKind = SourceKind.PHONE_REAL,
            devicePlacement = DevicePlacement.NIGHTSTAND,
        )

        val comparison = baseline.compare(nightstandSession)
        assertNull(comparison)
    }
}
