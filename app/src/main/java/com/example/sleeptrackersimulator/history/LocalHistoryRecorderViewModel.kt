package com.example.sleeptrackersimulator.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.actuator.ActuatorActivation
import com.example.sleeptrackersimulator.ui.dashboard.SensorUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

data class LocalRecordingState(
    val savedCount: Long = 0,
    val lastError: String? = null,
)

class LocalHistoryRecorderViewModel(
    sensorState: StateFlow<SensorUiState>,
    actuatorActivations: Flow<ActuatorActivation>,
    private val repository: SensorHistoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val writeMutex = Mutex()
    private var pendingActivation = false
    private val pendingEvents = ArrayDeque<ActuatorEvent>()
    @Volatile private var latestActiveState: SensorUiState.Active? = null
    private var lastRecordedSensorTimestampNanos: Long? = null
    private val _state = MutableStateFlow(LocalRecordingState())
    val state: StateFlow<LocalRecordingState> = _state

    init {
        require(sampleIntervalMillis > 0) { "Sample interval must be positive" }

        viewModelScope.launch(dispatcher) {
            sensorState.collect { state -> latestActiveState = state as? SensorUiState.Active }
        }
        viewModelScope.launch(dispatcher) {
            actuatorActivations.collect { activation ->
                writeMutex.withLock {
                    pendingActivation = true
                    pendingEvents.addLast(
                        ActuatorEvent(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = activation.timestamp,
                            action = activation.action.name,
                            triggerSource = activation.source.name,
                            requestedState = true,
                            result = "SUCCESS",
                        ),
                    )
                    persistPendingEventsLocked()
                }
            }
        }
        viewModelScope.launch(dispatcher) {
            while (isActive) {
                delay(sampleIntervalMillis)
                recordLatestIfNew()
            }
        }
        viewModelScope.launch(dispatcher) {
            while (isActive) {
                runCatching { repository.deleteOlderThan((clock() - LOCAL_RETENTION_MILLIS).coerceAtLeast(1L)) }
                    .onFailure { error -> _state.update { it.copy(lastError = error.message ?: "Local retention failed") } }
                delay(RETENTION_CHECK_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun recordLatestIfNew() {
        val active = latestActiveState ?: return
        val analysis = active.analysis
        val sensorTimestamp = analysis.sample.timestampNanos
        if (lastRecordedSensorTimestampNanos == sensorTimestamp) return
        val value = analysis.motionIntensity
        if (!value.isFinite() || value < 0f || sensorTimestamp <= 0L) return

        writeMutex.withLock {
            persistPendingEventsLocked()
            val actionForRecord = pendingActivation
            try {
                repository.addMeasurement(clock(), value, actionForRecord)
                lastRecordedSensorTimestampNanos = sensorTimestamp
                if (actionForRecord) pendingActivation = false
                _state.update { it.copy(savedCount = it.savedCount + 1, lastError = null) }
            } catch (error: Exception) {
                DiagnosticLog.event(DiagnosticTags.ROOM, "recordLatest", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
                _state.update { it.copy(lastError = error.message ?: "Local database write failed") }
            }
        }
    }

    private suspend fun persistPendingEventsLocked() {
        while (pendingEvents.isNotEmpty()) {
            try {
                repository.addActuatorEvent(pendingEvents.first())
                pendingEvents.removeFirst()
            } catch (error: Exception) {
                DiagnosticLog.event(DiagnosticTags.ROOM, "recordActuatorEvent", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
                _state.update { it.copy(lastError = error.message ?: "Actuator event write failed") }
                return
            }
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 1_000L
        const val LOCAL_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val RETENTION_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L

        fun factory(
            sensorState: StateFlow<SensorUiState>,
            actuatorActivations: Flow<ActuatorActivation>,
            repository: SensorHistoryRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LocalHistoryRecorderViewModel::class.java))
                return LocalHistoryRecorderViewModel(sensorState, actuatorActivations, repository) as T
            }
        }
    }
}
