package com.example.sleeptrackersimulator.ui.analytics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class MovementChartScale(
    val minY: Double,
    val maxY: Double,
    val tickStep: Double,
) {
    init {
        require(minY.isFinite() && maxY.isFinite() && tickStep.isFinite())
        require(minY < maxY && tickStep > 0.0)
    }

    val labels: List<Double>
        get() = generateSequence(minY) { previous ->
            (previous + tickStep).takeIf { it <= maxY + EPSILON }
        }.toList()

    fun normalizedY(value: Double): Double =
        ((value.coerceIn(minY, maxY) - minY) / (maxY - minY)).coerceIn(0.0, 1.0)

    private companion object {
        const val EPSILON = 1e-9
    }
}

object MovementChartScaleCalculator {
    const val BASE_MAX_Y = 3.0
    const val BASE_TICK_STEP = 0.5

    fun calculate(values: List<Float>): MovementChartScale? {
        if (values.isEmpty()) return null
        val maximum = values.asSequence()
            .filter { it.isFinite() }
            .map { it.coerceAtLeast(0f).toDouble() }
            .maxOrNull() ?: 0.0
        return when {
            maximum <= 3.0 -> MovementChartScale(0.0, 3.0, 0.5)
            maximum <= 4.0 -> MovementChartScale(0.0, 4.0, 0.5)
            maximum <= 5.0 -> MovementChartScale(0.0, 5.0, 1.0)
            maximum <= 6.0 -> MovementChartScale(0.0, 6.0, 1.0)
            maximum <= 8.0 -> MovementChartScale(0.0, 8.0, 2.0)
            maximum <= 10.0 -> MovementChartScale(0.0, 10.0, 2.0)
            else -> {
                val step = niceCeiling(maximum / 5.0)
                MovementChartScale(0.0, ceil(maximum / step) * step, step)
            }
        }
    }

    private fun niceCeiling(value: Double): Double {
        val magnitude = 10.0.pow(floor(log10(value)))
        val normalized = value / magnitude
        val nice = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }
}
