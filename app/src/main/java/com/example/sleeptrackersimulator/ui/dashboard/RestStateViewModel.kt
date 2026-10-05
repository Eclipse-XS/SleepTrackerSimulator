package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.rest.RestStateClassifier
import com.example.sleeptrackersimulator.rest.RestStateInput
import com.example.sleeptrackersimulator.rest.RestStateResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class RestStateViewModel(
    sensorState: StateFlow<SensorUiState>,
    bleState: StateFlow<BleConnectionState>,
    heartRate: StateFlow<HeartRateMeasurement?>,
    heartRateHistory: StateFlow<List<Int>>,
    private val classifier: RestStateClassifier = RestStateClassifier(),
) : ViewModel() {
    val state: StateFlow<RestStateResult> = combine(
        sensorState,
        bleState,
        heartRate,
        heartRateHistory,
    ) { sensor, connection, measurement, history ->
        val analysis = (sensor as? SensorUiState.Active)?.analysis
        classifier.classify(
            RestStateInput(
                movementState = analysis?.state,
                movementIntensity = analysis?.motionIntensity ?: 0f,
                heartRateBpm = measurement?.beatsPerMinute,
                heartRateHistory = history,
                heartRateAvailable = connection is BleConnectionState.Connected,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        initialValue = RestStateResult.INITIAL,
    )

    override fun onCleared() {
        classifier.reset()
        super.onCleared()
    }

    companion object {
        fun factory(
            sensorState: StateFlow<SensorUiState>,
            bleState: StateFlow<BleConnectionState>,
            heartRate: StateFlow<HeartRateMeasurement?>,
            heartRateHistory: StateFlow<List<Int>>,
            classifier: RestStateClassifier = RestStateClassifier(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RestStateViewModel::class.java))
                return RestStateViewModel(
                    sensorState,
                    bleState,
                    heartRate,
                    heartRateHistory,
                    classifier,
                ) as T
            }
        }
    }
}
