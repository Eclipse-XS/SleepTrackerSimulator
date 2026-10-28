package com.example.virtualsleepband.gatt

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic simulated heart-rate source, never a physical measurement.
 * Variation is deliberately slow, with a shorter respiratory component and
 * occasional mild movement-related peaks. No medical sleep-stage inference.
 */
enum class SimulationScenario(val label: String, val baseline: Double, val movementBoost: Double) {
    NORMAL_REST("Normal Rest", 64.0, 13.0),
    DEEPLY_RELAXED("Deeply Relaxed", 54.0, 5.0),
    RESTLESS_NIGHT("Restless Night", 70.0, 18.0),
    FREQUENT_MOVEMENT("Frequent Movement", 74.0, 24.0),
    RECOVERY_AFTER_ACTIVITY("Recovery After Activity", 68.0, 30.0),
    TEMPORARY_SIGNAL_LOSS("Temporary Signal Loss", 64.0, 12.0),
}

class SimulatedHeartRate(
    private val seed: Int = 17,
    private val scenario: SimulationScenario = SimulationScenario.NORMAL_REST,
) {
    fun bpm(elapsedSeconds: Long): Int {
        val t = elapsedSeconds.toDouble().coerceAtLeast(0.0)
        val circadian = 6.0 * sin(2.0 * PI * t / 720.0)
        val respiration = 2.3 * sin(2.0 * PI * t / 17.0)
        val microMotion = 3.2 * sin(2.0 * PI * t / 91.0)
        val reproducibleNoise = (((elapsedSeconds * 1103515245L + seed) ushr 16) % 7L).toDouble() - 3.0
        val frequentPeriod = if (scenario == SimulationScenario.FREQUENT_MOVEMENT) 120L else 420L
        val activityPulse = if ((elapsedSeconds % frequentPeriod) in (frequentPeriod - 60L)..(frequentPeriod - 35L)) scenario.movementBoost else 0.0
        val recovery = if (scenario == SimulationScenario.RECOVERY_AFTER_ACTIVITY) 28.0 * kotlin.math.exp(-t / 180.0) else 0.0
        return (scenario.baseline + circadian + respiration + microMotion + reproducibleNoise + activityPulse + recovery)
            .roundToInt().coerceIn(45, 115)
    }

    fun hasSignal(elapsedSeconds: Long): Boolean =
        scenario != SimulationScenario.TEMPORARY_SIGNAL_LOSS || elapsedSeconds % 180L !in 120L..149L

    fun batteryPercent(elapsedSeconds: Long): Int =
        (96L - elapsedSeconds.coerceAtLeast(0L) / 2400L).coerceIn(15L, 96L).toInt()
}
