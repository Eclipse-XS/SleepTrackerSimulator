package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.sleeptrackersimulator.actuator.ActuatorAction
import com.example.sleeptrackersimulator.actuator.ActuatorOperationStatus
import com.example.sleeptrackersimulator.actuator.ActuatorResult
import com.example.sleeptrackersimulator.actuator.DecisionEngine
import com.example.sleeptrackersimulator.actuator.RemoteControlEvent
import com.example.sleeptrackersimulator.actuator.RemoteControlRepository
import com.example.sleeptrackersimulator.actuator.RemoteControlState
import com.example.sleeptrackersimulator.actuator.SmartActuator
import com.example.sleeptrackersimulator.actuator.TriggerSource
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActuatorViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects hardware availability and automatic control enabled`() = actuatorTest { fixture ->
        val state = fixture.viewModel.state.value
        assertTrue(state.vibratorAvailable)
        assertTrue(state.flashlightAvailable)
        assertTrue(state.automaticControlEnabled)
        assertFalse(state.vibrationEnabled)
        assertFalse(state.flashlightEnabled)
        assertNull(state.lastError)
    }

    @Test
    fun `automatic threshold crossing triggers exactly one pulse while above`() = actuatorTest { fixture ->
        fixture.sensor.value = sensor(.7f, MovementState.MOVEMENT)
        runCurrent()
        fixture.sensor.value = sensor(.9f, MovementState.MOVEMENT)
        runCurrent()

        assertEquals(1, fixture.actuator.pulseCount)
        assertEquals(ActuatorAction.VIBRATION_PULSE, fixture.viewModel.state.value.lastActionTriggered)
        assertEquals(TriggerSource.LOCAL_AUTOMATIC, fixture.viewModel.state.value.lastTriggerSource)
    }

    @Test
    fun `dropping below and crossing again triggers second pulse`() = actuatorTest { fixture ->
        fixture.sensor.value = sensor(.7f, MovementState.MOVEMENT)
        runCurrent()
        fixture.sensor.value = sensor(.1f, MovementState.STILL)
        runCurrent()
        fixture.sensor.value = sensor(.7f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(2, fixture.actuator.pulseCount)
    }

    @Test
    fun `automatic control disabled suppresses local pulse`() = actuatorTest { fixture ->
        fixture.viewModel.setAutomaticControlEnabled(false)
        fixture.sensor.value = sensor(.8f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(0, fixture.actuator.pulseCount)
        assertFalse(fixture.viewModel.state.value.automaticControlEnabled)
    }

    @Test
    fun `sensor loss rearms automatic decision after recovery`() = actuatorTest { fixture ->
        fixture.sensor.value = sensor(.8f, MovementState.MOVEMENT)
        runCurrent()
        fixture.sensor.value = SensorUiState.Unavailable
        runCurrent()
        fixture.sensor.value = sensor(.9f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(2, fixture.actuator.pulseCount)
    }

    @Test
    fun `enabling automatic control while movement stays high rearms next sample`() = actuatorTest { fixture ->
        fixture.viewModel.setAutomaticControlEnabled(false)
        fixture.sensor.value = sensor(.8f, MovementState.MOVEMENT)
        runCurrent()
        fixture.viewModel.setAutomaticControlEnabled(true)
        fixture.sensor.value = sensor(.9f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(1, fixture.actuator.pulseCount)
    }

    @Test
    fun `manual vibration on and off calls actuator and updates state`() = actuatorTest { fixture ->
        fixture.viewModel.setManualVibration(true)
        fixture.viewModel.setManualVibration(false)
        assertEquals(listOf(true, false), fixture.actuator.vibrationStates)
        assertFalse(fixture.viewModel.state.value.vibrationEnabled)
        assertEquals(TriggerSource.MANUAL, fixture.viewModel.state.value.lastTriggerSource)
    }

    @Test
    fun `manual flashlight on and off calls actuator and updates state`() = actuatorTest { fixture ->
        fixture.viewModel.setManualFlashlight(true)
        fixture.viewModel.setManualFlashlight(false)
        assertEquals(listOf(true, false), fixture.actuator.flashlightStates)
        assertFalse(fixture.viewModel.state.value.flashlightEnabled)
    }


    @Test
    fun `explicit local flashlight OFF reaches hardware even when UI already says off`() = actuatorTest { fixture ->
        fixture.viewModel.setManualFlashlight(false)
        assertEquals(listOf(false), fixture.actuator.flashlightStates)
        assertFalse(fixture.viewModel.state.value.flashlightEnabled)
    }

    @Test
    fun `manual flashlight OFF cannot be undone by stale remote ON snapshot`() = actuatorTest { fixture ->
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(flashlightEnabled = true)))
        runCurrent()
        fixture.viewModel.setManualFlashlight(false)
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(flashlightEnabled = true)))
        runCurrent()
        assertEquals(listOf(true, false), fixture.actuator.flashlightStates)
        assertFalse(fixture.viewModel.state.value.flashlightEnabled)
    }

    @Test
    fun `camera permission denial leaves flashlight off without hardware call`() = actuatorTest { fixture ->
        fixture.viewModel.reportFlashlightPermissionDenied()

        assertTrue(fixture.actuator.flashlightStates.isEmpty())
        assertFalse(fixture.viewModel.state.value.flashlightEnabled)
        assertTrue(fixture.viewModel.state.value.lastError!!.contains("permission denied"))
    }

    @Test
    fun `remote vibration true and false controls persistent vibration`() = actuatorTest { fixture ->
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(vibrationEnabled = true)))
        runCurrent()
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(vibrationEnabled = false)))
        runCurrent()
        assertEquals(listOf(true, false), fixture.actuator.vibrationStates)
        assertFalse(fixture.viewModel.state.value.vibrationEnabled)
        assertTrue(fixture.viewModel.state.value.remoteListenerConnected)
    }

    @Test
    fun `remote flashlight true and false controls exact requested state`() = actuatorTest { fixture ->
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(flashlightEnabled = true)))
        runCurrent()
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(flashlightEnabled = false)))
        runCurrent()
        assertEquals(listOf(true, false), fixture.actuator.flashlightStates)
        assertFalse(fixture.viewModel.state.value.flashlightEnabled)
    }

    @Test
    fun `partial remote snapshot changes only the addressed actuator`() = actuatorTest { fixture ->
        fixture.viewModel.setManualVibration(true)
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(flashlightEnabled = true)))
        runCurrent()
        assertTrue(fixture.viewModel.state.value.vibrationEnabled)
        assertTrue(fixture.viewModel.state.value.flashlightEnabled)
        assertEquals(listOf(true), fixture.actuator.vibrationStates)
    }

    @Test
    fun `missing Firebase control node does not change hardware state`() = actuatorTest { fixture ->
        fixture.viewModel.setManualVibration(true)
        fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState()))
        runCurrent()
        assertEquals(listOf(true), fixture.actuator.vibrationStates)
        assertTrue(fixture.viewModel.state.value.vibrationEnabled)
    }

    @Test
    fun `duplicate remote snapshot does not repeat hardware command`() = actuatorTest { fixture ->
        val command = RemoteControlEvent.Updated(RemoteControlState(flashlightEnabled = true))
        fixture.remote.emit(command)
        runCurrent()
        fixture.remote.emit(command)
        runCurrent()
        assertEquals(listOf(true), fixture.actuator.flashlightStates)
    }

    @Test
    fun `initial remote snapshot does not cancel a manual command issued while Firebase connects`() =
        actuatorTest { fixture ->
            fixture.viewModel.setManualVibration(true)
            fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(vibrationEnabled = false)))
            runCurrent()
            assertTrue(fixture.viewModel.state.value.vibrationEnabled)
            assertEquals(listOf(true), fixture.actuator.vibrationStates)
        }

    @Test
    fun `remote update after initial snapshot overrides manual state`() = actuatorTest { fixture ->
            fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState()))
            runCurrent()
            fixture.viewModel.setManualVibration(true)
            fixture.remote.emit(RemoteControlEvent.Updated(RemoteControlState(vibrationEnabled = false)))
            runCurrent()
            assertFalse(fixture.viewModel.state.value.vibrationEnabled)
            assertEquals(listOf(true, false), fixture.actuator.vibrationStates)
        }

    @Test
    fun `background transition stops hardware and suppresses automatic pulses`() = actuatorTest { fixture ->
        fixture.viewModel.setManualVibration(true)
        fixture.viewModel.setForegroundActive(false)
        fixture.sensor.value = sensor(.9f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(0, fixture.actuator.pulseCount)
        assertFalse(fixture.viewModel.state.value.vibrationEnabled)
        assertTrue(fixture.actuator.stopCalls >= 1)
    }

    @Test
    fun `automatic pulse is suppressed while persistent vibration is active`() = actuatorTest { fixture ->
        fixture.viewModel.setManualVibration(true)
        fixture.sensor.value = sensor(.9f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(0, fixture.actuator.pulseCount)
        assertTrue(fixture.viewModel.state.value.vibrationEnabled)
    }

    @Test
    fun `remote error updates diagnostic while local control remains functional`() = actuatorTest { fixture ->
        fixture.remote.emit(RemoteControlEvent.Error("permission denied"))
        runCurrent()
        assertEquals("permission denied", fixture.viewModel.state.value.lastError)
        assertFalse(fixture.viewModel.state.value.remoteListenerConnected)

        fixture.viewModel.setManualVibration(true)
        assertTrue(fixture.viewModel.state.value.vibrationEnabled)
    }

    @Test
    fun `remote listener reconnects after unexpected failure`() = runTest(dispatcher) {
        var subscriptions = 0
        val repository = object : RemoteControlRepository {
            override val events: Flow<RemoteControlEvent> = flow {
                subscriptions++
                if (subscriptions == 1) error("listener cancelled")
                emit(RemoteControlEvent.Updated(RemoteControlState(vibrationEnabled = true)))
            }
        }
        val actuator = FakeSmartActuator(true, true)
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ActuatorViewModel(
                MutableStateFlow(SensorUiState.Initializing), actuator, repository, log = { _, _ -> },
            ) as T
        })[ActuatorViewModel::class.java]
        viewModel.setForegroundActive(true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, subscriptions)
        assertTrue(viewModel.state.value.vibrationEnabled)
        store.clear()
    }

    @Test
    fun `unavailable flashlight does not crash or claim enabled state`() =
        actuatorTest(flashlightAvailable = false) { fixture ->
            fixture.viewModel.setManualFlashlight(true)
            assertFalse(fixture.viewModel.state.value.flashlightEnabled)
            assertEquals("Flashlight unavailable", fixture.viewModel.state.value.lastError)
        }

    @Test
    fun `unavailable vibrator does not crash or claim enabled state`() =
        actuatorTest(vibratorAvailable = false) { fixture ->
            fixture.viewModel.setManualVibration(true)
            assertFalse(fixture.viewModel.state.value.vibrationEnabled)
            assertEquals("Vibrator unavailable", fixture.viewModel.state.value.lastError)
        }

    @Test
    fun `local pulse does not toggle persistent vibration state`() = actuatorTest { fixture ->
        fixture.sensor.value = sensor(.8f, MovementState.MOVEMENT)
        runCurrent()
        assertEquals(1, fixture.actuator.pulseCount)
        assertFalse(fixture.viewModel.state.value.vibrationEnabled)
    }

    @Test
    fun `failed operation preserves previous state and reports error`() = actuatorTest { fixture ->
        fixture.actuator.nextFailure = "hardware busy"
        fixture.viewModel.setManualFlashlight(true)
        assertFalse(fixture.viewModel.state.value.flashlightEnabled)
        assertEquals("hardware busy", fixture.viewModel.state.value.lastError)
    }

    @Test
    fun `successful activation emits one typed event and disabling emits none`() = actuatorTest { fixture ->
        val first = async { fixture.viewModel.activations.first() }
        runCurrent()
        fixture.viewModel.setManualVibration(true)
        assertEquals(ActuatorAction.VIBRATION_ENABLED, first.await().action)
        assertEquals(TriggerSource.MANUAL, first.await().source)

        val events = mutableListOf<ActuatorAction>()
        val collector = launch { fixture.viewModel.activations.collect { events += it.action } }
        runCurrent()
        fixture.viewModel.setManualVibration(false)
        runCurrent()
        collector.cancel()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `failed activation does not emit event`() = actuatorTest { fixture ->
        val events = mutableListOf<ActuatorAction>()
        val collector = launch { fixture.viewModel.activations.collect { events += it.action } }
        runCurrent()
        fixture.actuator.nextFailure = "hardware busy"
        fixture.viewModel.setManualFlashlight(true)
        runCurrent()
        collector.cancel()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `cleanup stops all actuators`() = actuatorTest { fixture ->
        assertEquals(0, fixture.actuator.stopCalls)
        fixture.store.clear()
        runCurrent()
        assertEquals(1, fixture.actuator.stopCalls)
    }

    private fun actuatorTest(
        vibratorAvailable: Boolean = true,
        flashlightAvailable: Boolean = true,
        block: suspend TestScope.(Fixture) -> Unit,
    ) = runTest(dispatcher) {
        val fixture = Fixture(vibratorAvailable, flashlightAvailable)
        runCurrent()
        try {
            block(fixture)
        } finally {
            fixture.store.clear()
            runCurrent()
        }
    }

    private class Fixture(vibratorAvailable: Boolean, flashlightAvailable: Boolean) {
        val sensor = MutableStateFlow<SensorUiState>(SensorUiState.Initializing)
        val actuator = FakeSmartActuator(vibratorAvailable, flashlightAvailable)
        val remote = MutableSharedFlow<RemoteControlEvent>(extraBufferCapacity = 4)
        val store = ViewModelStore()
        val viewModel: ActuatorViewModel

        init {
            val repository = object : RemoteControlRepository {
                override val events: Flow<RemoteControlEvent> = remote
            }
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ActuatorViewModel(
                    sensorState = sensor,
                    actuator = actuator,
                    remoteRepository = repository,
                    decisionEngine = DecisionEngine(.65f),
                    log = { _, _ -> },
                ) as T
            }
            viewModel = ViewModelProvider(store, factory)[ActuatorViewModel::class.java]
            viewModel.setForegroundActive(true)
        }
    }

    private class FakeSmartActuator(
        override val vibratorAvailable: Boolean,
        override val flashlightAvailable: Boolean,
    ) : SmartActuator {
        var pulseCount = 0
        val vibrationStates = mutableListOf<Boolean>()
        val flashlightStates = mutableListOf<Boolean>()
        var stopCalls = 0
        var nextFailure: String? = null

        override fun triggerVibrationPulse(): ActuatorResult {
            if (!vibratorAvailable) return unavailable("Vibrator unavailable")
            nextFailure?.let { return failure(it) }
            pulseCount++
            return success("pulse")
        }

        override fun setPersistentVibration(enabled: Boolean): ActuatorResult {
            if (enabled && !vibratorAvailable) return unavailable("Vibrator unavailable")
            nextFailure?.let { return failure(it) }
            vibrationStates += enabled
            return success("vibration")
        }

        override fun setFlashlightEnabled(enabled: Boolean): ActuatorResult {
            if (enabled && !flashlightAvailable) return unavailable("Flashlight unavailable")
            nextFailure?.let { return failure(it) }
            flashlightStates += enabled
            return success("flashlight")
        }

        override fun stopAll() { stopCalls++ }

        private fun success(message: String) = ActuatorResult(ActuatorOperationStatus.SUCCESS, message)
        private fun unavailable(message: String) = ActuatorResult(ActuatorOperationStatus.UNAVAILABLE, message)
        private fun failure(message: String): ActuatorResult {
            nextFailure = null
            return ActuatorResult(ActuatorOperationStatus.ERROR, message)
        }
    }

    companion object {
        private fun sensor(intensity: Float, state: MovementState) = SensorUiState.Active(
            MovementAnalysis(SensorSample(1f, 2f, 3f, 1L), intensity, state),
        )
    }
}
