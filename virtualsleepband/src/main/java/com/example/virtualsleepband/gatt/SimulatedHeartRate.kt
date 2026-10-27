package com.example.virtualsleepband.gatt

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic simulated heart-rate source, never a physical measurement.
 * Variation is deliberately slow, with a shorter respiratory component and
 * occasional mild movement-related peaks. No medical sleep-stage inference.
 */
class SimulatedHeartRate(private val seed: Int = 17) {
    fun bpm(elapsedSeconds: Long): Int {
        val t = elapsedSeconds.toDouble().coerceAtLeast(0.0)
        val circadian = 6.0 * sin(2.0 * PI * t / 720.0)
        val respiration = 2.3 * sin(2.0 * PI * t / 17.0)
        val microMotion = 3.2 * sin(2.0 * PI * t / 91.0)
        val reproducibleNoise = (((elapsedSeconds * 1103515245L + seed) ushr 16) % 7L).toDouble() - 3.0
        val activityPulse = if ((elapsedSeconds % 420L) in 360L..385L) 13.0 else 0.0
        return (64.0 + circadian + respiration + microMotion + reproducibleNoise + activityPulse)
            .roundToInt().coerceIn(45, 115)
    }

    fun batteryPercent(elapsedSeconds: Long): Int =
        (96L - elapsedSeconds.coerceAtLeast(0L) / 2400L).coerceIn(15L, 96L).toInt()
}
