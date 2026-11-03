package com.example.sleeptrackersimulator.ui.insights

import com.example.sleeptrackersimulator.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LowMotionTrendTest {
    private fun s(id: String, durMin: Long, cov: Float, at: Long) = SessionSummary(
        sessionId = id,
        observationDurationMs = durMin * 60_000L,
        validDurationMs = durMin * 60_000L,
        dataCoverageFraction = cov,
        lowMotionFraction = 0.8f,
        movementEpisodeCount = 1,
        movementRatePerHour = 1f,
        calculatedAtUtcMillis = at,
    )

    @Test fun shortOrLowCoverageSessionsAreExcludedAndOrderIsChronological() {
        val result = LowMotionTrend.trendPoints(
            listOf(s("b", 480, 0.95f, 2), s("short", 2, 1f, 3), s("lowcov", 480, 0.5f, 4), s("a", 420, 0.9f, 1)),
        )
        assertEquals(listOf("a", "b"), result.map { it.sessionId })
    }

    @Test fun movingAverageUsesTrailingWindow() {
        val avg = LowMotionTrend.movingAverage(listOf(1f, 0f, 1f, 0f), window = 2)
        assertEquals(listOf(1f, 0.5f, 0.5f, 0.5f), avg)
        assertEquals(90, LowMotionTrend.movingAverage(List(90) { 0.5f }).size)
    }

    @Test fun labelIndicesAreBoundedUniqueAndIncludeEnds() {
        val idx = LowMotionTrend.labelIndices(90, 4)
        assertEquals(4, idx.size)
        assertEquals(0, idx.first())
        assertEquals(89, idx.last())
        assertTrue(LowMotionTrend.labelIndices(2, 6) == listOf(0, 1))
        assertEquals(listOf(0), LowMotionTrend.labelIndices(1, 4).map { 0 })
    }
}
