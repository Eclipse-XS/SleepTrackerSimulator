package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.sensor.AccelerometerDataSource
import com.example.sleeptrackersimulator.sensor.AccelerometerEvent
import com.example.sleeptrackersimulator.sensor.MovementClassifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

class SensorViewModel(
    private val dataSource: AccelerometerDataSource,
    private val classifier: MovementClassifier = MovementClassifier(),
) : ViewModel() {
    val uiState = dataSource.observe()
        .map { event ->
            when (event) {
                is AccelerometerEvent.Measurement -> SensorUiState.Active(classifier.analyze(event.sample))
                AccelerometerEvent.Unavailable -> SensorUiState.Unavailable
                is AccelerometerEvent.Error -> SensorUiState.Error(event.message)
            }
        }
        .onStart {
            classifier.reset()
            emit(SensorUiState.Initializing)
        }
        .catch { error ->
            emit(SensorUiState.Error(error.message ?: "Unexpected sensor error"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
            initialValue = SensorUiState.Initializing,
        )

    companion object {
        fun factory(dataSource: AccelerometerDataSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SensorViewModel::class.java))
                    return SensorViewModel(dataSource) as T
                }
            }
    }
}
