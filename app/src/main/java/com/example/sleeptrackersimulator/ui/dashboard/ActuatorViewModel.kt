package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.actuator.ActuatorAction
import com.example.sleeptrackersimulator.actuator.ActuatorActivation
import com.example.sleeptrackersimulator.actuator.ActuatorOperationStatus
import com.example.sleeptrackersimulator.actuator.ActuatorResult
import com.example.sleeptrackersimulator.actuator.ActuatorUiState
import com.example.sleeptrackersimulator.actuator.Decision
import com.example.sleeptrackersimulator.actuator.DecisionEngine
import com.example.sleeptrackersimulator.actuator.RemoteControlEvent
import com.example.sleeptrackersimulator.actuator.RemoteControlRepository
import com.example.sleeptrackersimulator.actuator.SmartActuator
import com.example.sleeptrackersimulator.actuator.TriggerSource
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import com.example.sleeptrackersimulator.ml.ActivityClass
import com.example.sleeptrackersimulator.ml.MlUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ActuatorViewModel(
    sensorState: StateFlow<SensorUiState>,
    private val actuator: SmartActuator,
    private val remoteRepository: RemoteControlRepository,
    private val decisionEngine: DecisionEngine = DecisionEngine(),
    private val log: (String, Throwable?) -> Unit = { message, error ->
        DiagnosticLog.event(
            DiagnosticTags.ACTUATORS,
            "actuatorCommand",
            if (error == null) DiagnosticResult.SUCCESS else DiagnosticResult.FAILED,
            details = mapOf("message" to message, "reason" to error?.javaClass?.simpleName),
            error = error,
        )
    },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    mlState: StateFlow<MlUiState>? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ActuatorUiState(
            vibratorAvailable = actuator.vibratorAvailable,
            flashlightAvailable = actuator.flashlightAvailable,
        ),
    )
    val state: StateFlow<ActuatorUiState> = _state
    private val activationChannel = Channel<ActuatorActivation>(Channel.UNLIMITED)
    val activations: Flow<ActuatorActivation> = activationChannel.receiveAsFlow()
    private var foregroundActive = false
    private var latestRemoteControls: com.example.sleeptrackersimulator.actuator.RemoteControlState? = null
    private var receivedInitialRemoteSnapshot = false
    private var manualVibrationBeforeInitialRemoteSnapshot = false
    private var manualFlashlightBeforeInitialRemoteSnapshot = false
    private var lastMlCritical = false

    init {
        viewModelScope.launch {
            sensorState.collect { sensor ->
                val analysis = (sensor as? SensorUiState.Active)?.analysis
                if (analysis == null) {
                    decisionEngine.reset()
                    return@collect
                }
                val decision = decisionEngine.evaluate(analysis.motionIntensity, analysis.state)
                if (decision == Decision.TRIGGER_MOVEMENT_ALERT &&
                    foregroundActive && _state.value.automaticControlEnabled &&
                    !_state.value.vibrationEnabled
                ) {
                    DiagnosticLog.event(DiagnosticTags.ACTUATORS, "automaticMovementAlert", DiagnosticResult.STARTED, source = TriggerSource.LOCAL_AUTOMATIC.name)
                    applyPulse(actuator.triggerVibrationPulse(), TriggerSource.LOCAL_AUTOMATIC)
                }
            }
        }
        if (mlState != null) viewModelScope.launch {
            mlState.collect { ml ->
                val prediction = (ml as? MlUiState.Ready)?.prediction
                val critical = prediction?.activity == ActivityClass.ACTIVE && prediction.confidence >= ML_ALERT_CONFIDENCE
                if (critical && !lastMlCritical && foregroundActive &&
                    _state.value.automaticControlEnabled && !_state.value.vibrationEnabled
                ) {
                    DiagnosticLog.event(
                        DiagnosticTags.ACTUATORS,
                        "automaticMlAlert",
                        DiagnosticResult.STARTED,
                        source = TriggerSource.LOCAL_AUTOMATIC.name,
                        details = mapOf("class" to prediction.activity.name, "confidence" to prediction.confidence),
                    )
                    applyPulse(actuator.triggerVibrationPulse(), TriggerSource.LOCAL_AUTOMATIC)
                }
                lastMlCritical = critical
            }
        }
        viewModelScope.launch {
            try {
                remoteRepository.events.retryWhen { error, attempt ->
                    log("Remote listener disconnected; retry ${attempt + 1}", error)
                    if (attempt >= MAX_REMOTE_RECONNECT_ATTEMPTS - 1) false
                    else {
                        delay(REMOTE_RECONNECT_DELAY_MILLIS)
                        true
                    }
                }.collect { event ->
                    when (event) {
                        is RemoteControlEvent.Updated -> {
                            val initialSnapshot = !receivedInitialRemoteSnapshot
                            receivedInitialRemoteSnapshot = true
                            latestRemoteControls = event.controls
                            _state.update { it.copy(remoteListenerConnected = true, lastError = null) }
                            log(
                                "Remote command received: initial=$initialSnapshot " +
                                    "vibration=${event.controls.vibrationEnabled} " +
                                    "flashlight=${event.controls.flashlightEnabled}",
                                null,
                            )
                            if (foregroundActive) applyRemoteControls(event.controls, initialSnapshot)
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
        decisionEngine.reset()
    }

    fun setForegroundActive(active: Boolean) {
        if (foregroundActive == active) return
        foregroundActive = active
        decisionEngine.reset()
        if (!active) {
            DiagnosticLog.event(DiagnosticTags.ACTUATORS, "stopAll", DiagnosticResult.STARTED, source = "LIFECYCLE", details = mapOf("reason" to "foreground_false"))
            actuator.stopAll()
            _state.update {
                it.copy(vibrationEnabled = false, flashlightEnabled = false)
            }
        } else {
            latestRemoteControls?.let(::applyRemoteControls)
        }
    }

    fun setManualVibration(enabled: Boolean) {
        if (!foregroundActive) return
        if (!receivedInitialRemoteSnapshot) manualVibrationBeforeInitialRemoteSnapshot = true
        DiagnosticLog.event(DiagnosticTags.ACTUATORS, "setVibration", DiagnosticResult.STARTED, source = TriggerSource.MANUAL.name, details = mapOf("enabled" to enabled))
        applyPersistentVibration(enabled, TriggerSource.MANUAL)
    }

    fun setManualFlashlight(enabled: Boolean) {
        if (!foregroundActive) return
        if (!receivedInitialRemoteSnapshot) manualFlashlightBeforeInitialRemoteSnapshot = true
        DiagnosticLog.event(DiagnosticTags.ACTUATORS, "setFlashlight", DiagnosticResult.STARTED, source = TriggerSource.MANUAL.name, details = mapOf("enabled" to enabled))
        applyFlashlight(enabled, TriggerSource.MANUAL)
    }

    fun reportFlashlightPermissionDenied() {
        _state.update {
            it.copy(
                flashlightEnabled = false,
                lastError = "Camera permission denied; flashlight remains disabled",
            )
        }
        log("Camera permission denied", null)
    }

    fun triggerSmartRulePulse(): ActuatorResult {
        if (!foregroundActive) return ActuatorResult(ActuatorOperationStatus.ERROR, "APP_NOT_IN_FOREGROUND")
        val result = actuator.triggerVibrationPulse()
        applyPulse(result, TriggerSource.LOCAL_AUTOMATIC)
        return result
    }

    private fun applyPulse(result: ActuatorResult, source: TriggerSource) {
        applyResult(result, ActuatorAction.VIBRATION_PULSE, source) {
            it.copy(
                lastTriggerSource = source,
                lastActionTriggered = ActuatorAction.VIBRATION_PULSE,
                lastError = null,
            )
        }
    }

    private fun applyRemoteControls(
        controls: com.example.sleeptrackersimulator.actuator.RemoteControlState,
        initialSnapshot: Boolean = false,
    ) {
        if (initialSnapshot && manualVibrationBeforeInitialRemoteSnapshot) {
            log("Initial remote vibration state ignored: a manual command was already issued", null)
        } else {
            controls.vibrationEnabled?.let { applyPersistentVibration(it, TriggerSource.REMOTE) }
        }
        if (initialSnapshot && manualFlashlightBeforeInitialRemoteSnapshot) {
            log("Initial remote flashlight state ignored: a manual command was already issued", null)
        } else {
            controls.flashlightEnabled?.let { applyFlashlight(it, TriggerSource.REMOTE) }
        }
    }

    private fun applyPersistentVibration(enabled: Boolean, source: TriggerSource) {
        if (_state.value.vibrationEnabled == enabled) {
            DiagnosticLog.event(DiagnosticTags.ACTUATORS, "setVibration", DiagnosticResult.SKIPPED, source = source.name, details = mapOf("enabled" to enabled, "reason" to "duplicate"))
            return
        }
        DiagnosticLog.event(DiagnosticTags.ACTUATORS, "setVibration", DiagnosticResult.STARTED, source = source.name, details = mapOf("enabled" to enabled))
        val activation = if (enabled) ActuatorAction.VIBRATION_ENABLED else null
        applyResult(actuator.setPersistentVibration(enabled), activation, source) {
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
        if (_state.value.flashlightEnabled == enabled) {
            DiagnosticLog.event(DiagnosticTags.ACTUATORS, "setFlashlight", DiagnosticResult.SKIPPED, source = source.name, details = mapOf("enabled" to enabled, "reason" to "duplicate"))
            return
        }
        DiagnosticLog.event(DiagnosticTags.ACTUATORS, "setFlashlight", DiagnosticResult.STARTED, source = source.name, details = mapOf("enabled" to enabled))
        val activation = if (enabled) ActuatorAction.FLASHLIGHT_ENABLED else null
        applyResult(actuator.setFlashlightEnabled(enabled), activation, source) {
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
        activation: ActuatorAction?,
        source: TriggerSource,
        onSuccess: (ActuatorUiState) -> ActuatorUiState,
    ) {
        if (result.status == ActuatorOperationStatus.SUCCESS) {
            log(result.message, null)
            _state.update(onSuccess)
            if (activation != null) {
                activationChannel.trySend(
                    ActuatorActivation(activation, source, currentTimeMillis()),
                )
            }
        } else {
            _state.update { it.copy(lastError = result.message) }
            log("Actuator operation failed: ${result.message}", null)
        }
    }

    override fun onCleared() {
        decisionEngine.reset()
        DiagnosticLog.event(DiagnosticTags.ACTUATORS, "stopAll", DiagnosticResult.STARTED, source = "LIFECYCLE", details = mapOf("reason" to "view_model_cleared"))
        actuator.stopAll()
        activationChannel.close()
        super.onCleared()
    }

    companion object {
        private const val MAX_REMOTE_RECONNECT_ATTEMPTS = 3L
        private const val REMOTE_RECONNECT_DELAY_MILLIS = 1_000L
        private const val ML_ALERT_CONFIDENCE = 0.75f

        fun factory(
            sensorState: StateFlow<SensorUiState>,
            actuator: SmartActuator,
            remoteRepository: RemoteControlRepository,
            mlState: StateFlow<MlUiState>? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ActuatorViewModel::class.java))
                return ActuatorViewModel(sensorState, actuator, remoteRepository, mlState = mlState) as T
            }
        }
    }
}
