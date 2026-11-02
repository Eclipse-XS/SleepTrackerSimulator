package com.example.sleeptrackersimulator.actuator

import android.os.TestVibrator
import android.os.VibrationEffect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartActuatorSafetyTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `persistent vibration automatically cuts off after exactly 30 seconds`() = testScope.runTest {
        val fakeVibrator = TestVibrator()
        val actuator = AndroidSmartActuator(
            context = null,
            scope = testScope,
            injectedVibrator = fakeVibrator,
        )

        val result = actuator.setPersistentVibration(true)
        assertEquals(ActuatorOperationStatus.SUCCESS, result.status)
        assertTrue(actuator.testIsPersistentVibrating)

        // Advance 29.9 seconds (should still be vibrating)
        advanceTimeBy(29_900L)
        runCurrent()
        assertTrue(actuator.testIsPersistentVibrating)

        // Advance past 30.0 seconds (must cut off)
        advanceTimeBy(200L)
        runCurrent()
        assertFalse(actuator.testIsPersistentVibrating)
        assertTrue(fakeVibrator.cancelCallCount >= 1)
    }

    @Test
    fun `repeated ON commands are idempotent and cannot indefinitely extend safety limit`() = testScope.runTest {
        val fakeVibrator = TestVibrator()
        val actuator = AndroidSmartActuator(
            context = null,
            scope = testScope,
            injectedVibrator = fakeVibrator,
        )

        // Start persistent vibration
        actuator.setPersistentVibration(true)
        assertTrue(actuator.testIsPersistentVibrating)

        // Advance 20 seconds
        advanceTimeBy(20_000L)
        runCurrent()

        // Send repeated ON command at t=20s
        val secondResult = actuator.setPersistentVibration(true)
        assertEquals(ActuatorOperationStatus.SUCCESS, secondResult.status)
        assertEquals("Vibration already active", secondResult.message)

        // Advance another 10.1 seconds (t=30.1s from original start)
        // Must cut off because timer was NOT restarted
        advanceTimeBy(10_100L)
        runCurrent()
        assertFalse(actuator.testIsPersistentVibrating)
    }

    @Test
    fun `stopAll immediately halts vibration and cancels pending auto-cutoff`() = testScope.runTest {
        val fakeVibrator = TestVibrator()
        val actuator = AndroidSmartActuator(
            context = null,
            scope = testScope,
            injectedVibrator = fakeVibrator,
        )

        actuator.setPersistentVibration(true)
        assertTrue(actuator.testIsPersistentVibrating)

        // Trigger emergency stopAll
        actuator.stopAll()
        assertFalse(actuator.testIsPersistentVibrating)
        assertTrue(fakeVibrator.cancelCallCount >= 1)

        // Advance past 30 seconds to ensure no resurrected job crashes or changes state
        advanceTimeBy(35_000L)
        runCurrent()
        assertFalse(actuator.testIsPersistentVibrating)
    }

    @Test
    fun `torch state callback reconciles observed flashlight state`() {
        val actuator = AndroidSmartActuator(
            context = null,
            scope = testScope,
            injectedFlashlightCameraId = "camera-0",
        )

        assertNull(actuator.observedFlashlightEnabled.value)

        // Torch turns ON
        actuator.testTorchCallback.onTorchModeChanged("camera-0", true)
        assertEquals(true, actuator.observedFlashlightEnabled.value)

        // Torch turns OFF
        actuator.testTorchCallback.onTorchModeChanged("camera-0", false)
        assertEquals(false, actuator.observedFlashlightEnabled.value)

        // Torch becomes unavailable
        actuator.testTorchCallback.onTorchModeUnavailable("camera-0")
        assertNull(actuator.observedFlashlightEnabled.value)
    }

    @Test
    fun `operations on unavailable hardware return UNAVAILABLE status`() {
        val fakeVibrator = TestVibrator().apply { hasVibratorValue = false }
        val actuator = AndroidSmartActuator(
            context = null,
            scope = testScope,
            injectedVibrator = fakeVibrator,
        )

        assertFalse(actuator.vibratorAvailable)
        assertFalse(actuator.flashlightAvailable)

        val pulseResult = actuator.triggerVibrationPulse()
        assertEquals(ActuatorOperationStatus.UNAVAILABLE, pulseResult.status)

        val persistResult = actuator.setPersistentVibration(true)
        assertEquals(ActuatorOperationStatus.UNAVAILABLE, persistResult.status)

        val flashResult = actuator.setFlashlightEnabled(true)
        assertEquals(ActuatorOperationStatus.UNAVAILABLE, flashResult.status)
    }

    @Test
    fun `flashlight returns ERROR when camera permission is absent`() {
        val actuator = AndroidSmartActuator(
            context = null,
            scope = testScope,
            injectedFlashlightCameraId = "cam-0",
        )

        // When context is null or camera permission missing, must return ERROR or UNAVAILABLE
        val result = actuator.setFlashlightEnabled(true)
        assertTrue(result.status in setOf(ActuatorOperationStatus.ERROR, ActuatorOperationStatus.UNAVAILABLE))
    }
}
