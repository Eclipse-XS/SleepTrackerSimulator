package com.example.sleeptrackersimulator.sensor

import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementClassifierTest {
    @Test
    fun `gravity-only samples remain still`() {
        val classifier = MovementClassifier(smoothingFactor = 1f)

        classifier.analyze(sample(z = 9.80665f))
        val result = classifier.analyze(sample(z = 9.80665f))

        assertEquals(MovementState.STILL, result.state)
        assertEquals(0f, result.motionIntensity, 0.001f)
    }

    @Test
    fun `intensity above movement threshold changes state to movement`() {
        val classifier = MovementClassifier(smoothingFactor = 1f)
        classifier.analyze(sample(z = 9.8f))

        val result = classifier.analyze(sample(x = 0.6f, z = 9.8f))

        assertEquals(MovementState.MOVEMENT, result.state)
        assertTrue(result.motionIntensity >= MovementClassifier.DEFAULT_MOVEMENT_THRESHOLD)
    }

    @Test
    fun `hysteresis prevents flicker until intensity falls below still threshold`() {
        val classifier = MovementClassifier(smoothingFactor = 1f)
        classifier.analyze(sample(z = 9.8f))
        classifier.analyze(sample(x = 0.6f, z = 9.8f))

        val betweenThresholds = classifier.analyze(sample(x = 0.9f, z = 9.8f))
        val belowStillThreshold = classifier.analyze(sample(x = 1.0f, z = 9.8f))

        assertEquals(MovementState.MOVEMENT, betweenThresholds.state)
        assertEquals(MovementState.STILL, belowStillThreshold.state)
    }

    @Test
    fun `reset returns classifier to still state`() {
        val classifier = MovementClassifier(smoothingFactor = 1f)
        classifier.analyze(sample(z = 9.8f))
        classifier.analyze(sample(x = 0.6f, z = 9.8f))

        classifier.reset()
        val result = classifier.analyze(sample(x = 5f, z = 8f))

        assertEquals(MovementState.STILL, result.state)
    }

    private fun sample(x: Float = 0f, z: Float) = SensorSample(
        x = x,
        y = 0f,
        z = z,
        timestampNanos = 0L,
    )
}
