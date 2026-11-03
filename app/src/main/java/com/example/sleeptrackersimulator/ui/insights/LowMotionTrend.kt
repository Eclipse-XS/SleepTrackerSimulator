package com.example.sleeptrackersimulator.ui.insights

import com.example.sleeptrackersimulator.core.model.SessionSummary

/** Pure helpers for the Nightly Low-Motion Trend chart (no stored data is modified). */
object LowMotionTrend {
    const val MIN_TREND_DURATION_MS = 30 * 60 * 1000L
    const val MIN_TREND_COVERAGE = 0.85f
    const val MOVING_AVERAGE_WINDOW = 7
    const val TARGET_LOW = 0.70f
    const val TARGET_HIGH = 0.90f

    /**
     * Only full-night recordings (same criteria as baseline comparability: >= 30 min, >= 85% coverage)
     * are plotted, so short test sessions are not mixed with full nights. Averages are unaffected.
     */
    fun trendPoints(summaries: List<SessionSummary>): List<SessionSummary> =
        summaries.filter {
            it.observationDurationMs >= MIN_TREND_DURATION_MS && it.dataCoverageFraction >= MIN_TREND_COVERAGE
        }.sortedBy { it.calculatedAtUtcMillis }

    /** Trailing moving average; early points use the available (shorter) window. */
    fun movingAverage(values: List<Float>, window: Int = MOVING_AVERAGE_WINDOW): List<Float> =
        values.indices.map { i ->
            val from = maxOf(0, i - window + 1)
            values.subList(from, i + 1).average().toFloat()
        }

    /** Evenly spread, de-duplicated label indices (always includes first and last). */
    fun labelIndices(count: Int, maxLabels: Int): List<Int> {
        if (count <= 0) return emptyList()
        if (count == 1 || maxLabels <= 1) return listOf(count - 1)
        val labels = minOf(count, maxLabels)
        return (0 until labels).map { (it * (count - 1).toDouble() / (labels - 1)).toInt().coerceAtMost(count - 1) }.distinct()
    }
}
