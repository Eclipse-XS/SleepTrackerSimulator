package com.example.sleeptrackersimulator.ml

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ui.dashboard.SensorUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MlViewModel(
    sensorState: StateFlow<SensorUiState>,
    private val classifier: MovementInference,
    private val buffer: SensorWindowBuffer = SensorWindowBuffer(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<MlUiState>(MlUiState.Loading)
    val state: StateFlow<MlUiState> = _state.asStateFlow()
    private val _pronouncedEvents = MutableSharedFlow<MlPrediction>(extraBufferCapacity = 1)
    val pronouncedEvents: SharedFlow<MlPrediction> = _pronouncedEvents.asSharedFlow()
    private var pronouncedLatched = false

    init {
        viewModelScope.launch(dispatcher) {
            sensorState.collect { sensor ->
                when (sensor) {
                    SensorUiState.Initializing -> reset(MlUiState.Loading)
                    SensorUiState.Unavailable -> reset(MlUiState.Unavailable)
                    is SensorUiState.Error -> reset(MlUiState.Error(sensor.message))
                    is SensorUiState.Active -> process(sensor)
                }
            }
        }
    }

    private fun process(sensor: SensorUiState.Active) {
        val window = buffer.add(sensor.analysis.sample)
        if (window == null) {
            _state.value = MlUiState.Collecting(buffer.size, SensorWindowBuffer.WINDOW_SIZE)
            return
        }
        try {
            val prediction = classifier.classify(window)
            _state.value = MlUiState.Ready(prediction)
            val pronounced = prediction.predictedClass == MovementClass.PRONOUNCED_MOVEMENT &&
                prediction.confidence >= PRONOUNCED_CONFIDENCE
            if (pronounced && !pronouncedLatched) _pronouncedEvents.tryEmit(prediction)
            pronouncedLatched = pronounced
        } catch (error: Exception) {
            _state.value = MlUiState.Error(error.message ?: "ML inference failed")
        }
    }

    private fun reset(next: MlUiState) {
        buffer.clear()
        pronouncedLatched = false
        _state.value = next
    }

    override fun onCleared() {
        classifier.close()
        super.onCleared()
    }

    companion object {
        const val PRONOUNCED_CONFIDENCE = 0.70f

        fun factory(
            sensorState: StateFlow<SensorUiState>,
            classifier: MovementInference,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MlViewModel::class.java))
                return MlViewModel(sensorState, classifier) as T
            }
        }
    }
}
