package com.example.sleeptrackersimulator.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.actuator.ActuatorActivation
import com.example.sleeptrackersimulator.ui.dashboard.SensorUiState
import com.example.sleeptrackersimulator.ml.MlUiState
import com.example.sleeptrackersimulator.ml.MovementClass
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
    mlState: StateFlow<MlUiState>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val writeMutex = Mutex()
    private var pendingActivation = false
    private var latestActiveState: SensorUiState.Active? = null
    private var lastRecordedSensorTimestampNanos: Long? = null
    private var latestMlState: MlUiState = MlUiState.Loading
    private val _state = MutableStateFlow(LocalRecordingState())
    val state: StateFlow<LocalRecordingState> = _state

    init {
        require(sampleIntervalMillis > 0) { "Sample interval must be positive" }

        viewModelScope.launch(dispatcher) {
            sensorState.collect { state -> latestActiveState = state as? SensorUiState.Active }
        }
        viewModelScope.launch(dispatcher) {
            actuatorActivations.collect {
                writeMutex.withLock { pendingActivation = true }
            }
        }
        viewModelScope.launch(dispatcher) {
            mlState?.collect { latestMlState = it }
        }
        viewModelScope.launch(dispatcher) {
            while (isActive) {
                delay(currentSampleIntervalMillis())
                recordLatestIfNew()
            }
        }
    }

    private fun currentSampleIntervalMillis(): Long {
        val ready = latestMlState as? MlUiState.Ready
        return if (ready?.prediction?.predictedClass == MovementClass.PRONOUNCED_MOVEMENT) {
            FAST_SAMPLE_INTERVAL_MILLIS.coerceAtMost(sampleIntervalMillis)
        } else {
            sampleIntervalMillis
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
            val actionForRecord = pendingActivation
            try {
                repository.addMeasurement(clock(), value, actionForRecord)
                lastRecordedSensorTimestampNanos = sensorTimestamp
                if (actionForRecord) pendingActivation = false
                _state.update { it.copy(savedCount = it.savedCount + 1, lastError = null) }
            } catch (error: Exception) {
                _state.update { it.copy(lastError = error.message ?: "Local database write failed") }
            }
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 1_000L
        const val FAST_SAMPLE_INTERVAL_MILLIS = 250L

        fun factory(
            sensorState: StateFlow<SensorUiState>,
            actuatorActivations: Flow<ActuatorActivation>,
            repository: SensorHistoryRepository,
            mlState: StateFlow<MlUiState>? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(LocalHistoryRecorderViewModel::class.java))
                return LocalHistoryRecorderViewModel(
                    sensorState = sensorState,
                    actuatorActivations = actuatorActivations,
                    repository = repository,
                    mlState = mlState,
                ) as T
            }
        }
    }
}
