package com.example.sleeptrackersimulator.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.actuator.ActuatorAction
import com.example.sleeptrackersimulator.actuator.ActuatorOperationStatus
import com.example.sleeptrackersimulator.actuator.ActuatorResult
import com.example.sleeptrackersimulator.actuator.ActuatorUiState
import com.example.sleeptrackersimulator.actuator.Decision
import com.example.sleeptrackersimulator.actuator.DecisionEngine
import com.example.sleeptrackersimulator.actuator.RemoteControlEvent
import com.example.sleeptrackersimulator.actuator.RemoteControlRepository
import com.example.sleeptrackersimulator.actuator.SmartActuator
import com.example.sleeptrackersimulator.actuator.TriggerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ActuatorViewModel(
    sensorState: StateFlow<SensorUiState>,
    private val actuator: SmartActuator,
    private val remoteRepository: RemoteControlRepository,
    private val decisionEngine: DecisionEngine = DecisionEngine(),
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.i(TAG, message) else Log.e(TAG, message, error)
    },
) : ViewModel() {
    private val _state = MutableStateFlow(
        ActuatorUiState(
            vibratorAvailable = actuator.vibratorAvailable,
            flashlightAvailable = actuator.flashlightAvailable,
        ),
    )
    val state: StateFlow<ActuatorUiState> = _state

    init {
        viewModelScope.launch {
            sensorState.collect { sensor ->
                val analysis = (sensor as? SensorUiState.Active)?.analysis ?: return@collect
                val decision = decisionEngine.evaluate(analysis.motionIntensity, analysis.state)
                if (decision == Decision.TRIGGER_MOVEMENT_ALERT && _state.value.automaticControlEnabled) {
                    log("Local movement threshold crossed", null)
                    applyPulse(actuator.triggerVibrationPulse(), TriggerSource.LOCAL_AUTOMATIC)
                }
            }
        }
        viewModelScope.launch {
            try {
                remoteRepository.events.collect { event ->
                    when (event) {
                        is RemoteControlEvent.Updated -> {
                            _state.update { it.copy(remoteListenerConnected = true, lastError = null) }
                            log("Remote control updated", null)
                            applyPersistentVibration(event.controls.vibrationEnabled, TriggerSource.REMOTE)
                            applyFlashlight(event.controls.flashlightEnabled, TriggerSource.REMOTE)
                        }
                        is RemoteControlEvent.Error -> {
                            _state.update {
                                it.copy(remoteListenerConnected = false, lastError = event.message)
                            }
                            log("Remote control failed: ${event.message}", null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(remoteListenerConnected = false, lastError = error.message ?: error.javaClass.simpleName)
                }
                log("Remote control failed", error)
            }
        }
    }

    fun setAutomaticControlEnabled(enabled: Boolean) {
        _state.update { it.copy(automaticControlEnabled = enabled) }
    }

    fun setManualVibration(enabled: Boolean) {
        log("Manual vibration ${if (enabled) "enabled" else "disabled"}", null)
        applyPersistentVibration(enabled, TriggerSource.MANUAL)
    }

    fun setManualFlashlight(enabled: Boolean) {
        log("Manual flashlight ${if (enabled) "enabled" else "disabled"}", null)
        applyFlashlight(enabled, TriggerSource.MANUAL)
    }

    private fun applyPulse(result: ActuatorResult, source: TriggerSource) {
        applyResult(result) {
            it.copy(
                lastTriggerSource = source,
                lastActionTriggered = ActuatorAction.VIBRATION_PULSE,
                lastError = null,
            )
        }
    }

    private fun applyPersistentVibration(enabled: Boolean, source: TriggerSource) {
        applyResult(actuator.setPersistentVibration(enabled)) {
            it.copy(
                vibrationEnabled = enabled,
                lastTriggerSource = source,
                lastActionTriggered = if (enabled) {
                    ActuatorAction.VIBRATION_ENABLED
                } else {
                    ActuatorAction.VIBRATION_DISABLED
                },
                lastError = null,
            )
        }
    }

    private fun applyFlashlight(enabled: Boolean, source: TriggerSource) {
        applyResult(actuator.setFlashlightEnabled(enabled)) {
            it.copy(
                flashlightEnabled = enabled,
                lastTriggerSource = source,
                lastActionTriggered = if (enabled) {
                    ActuatorAction.FLASHLIGHT_ENABLED
                } else {
                    ActuatorAction.FLASHLIGHT_DISABLED
                },
                lastError = null,
            )
        }
    }

    private inline fun applyResult(
        result: ActuatorResult,
        onSuccess: (ActuatorUiState) -> ActuatorUiState,
    ) {
        if (result.status == ActuatorOperationStatus.SUCCESS) {
            log(result.message, null)
            _state.update(onSuccess)
        } else {
            _state.update { it.copy(lastError = result.message) }
            log("Actuator operation failed: ${result.message}", null)
        }
    }

    override fun onCleared() {
        decisionEngine.reset()
        actuator.stopAll()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ActuatorControl"

        fun factory(
            sensorState: StateFlow<SensorUiState>,
            actuator: SmartActuator,
            remoteRepository: RemoteControlRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ActuatorViewModel::class.java))
                return ActuatorViewModel(sensorState, actuator, remoteRepository) as T
            }
        }
    }
}
