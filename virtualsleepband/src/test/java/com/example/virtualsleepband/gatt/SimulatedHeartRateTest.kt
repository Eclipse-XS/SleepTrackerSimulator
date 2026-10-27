package com.example.virtualsleepband.gatt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedHeartRateTest {
    @Test fun deterministicAcrossInstances() {
        val first = SimulatedHeartRate(seed = 17)
        val second = SimulatedHeartRate(seed = 17)
        for (secondIndex in 0L..10000L step 37L) {
            assertEquals(first.bpm(secondIndex), second.bpm(secondIndex))
            assertEquals(first.batteryPercent(secondIndex), second.batteryPercent(secondIndex))
        }
    }

    @Test fun boundedAndVariableValues() {
        val generator = SimulatedHeartRate()
        val observations = (0L until 3600L step 10L).map(generator::bpm)
        assertTrue(observations.all { it in 45..115 })
        assertTrue(observations.toSet().size > 10)
        assertTrue(generator.batteryPercent(0L) >= generator.batteryPercent(86400L))
        assertTrue(generator.batteryPercent(-5L) in 15..96)
    }
}
