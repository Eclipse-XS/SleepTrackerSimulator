package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.sensor.AccelerometerDataSource
import com.example.sleeptrackersimulator.sensor.AccelerometerEvent
import com.example.sleeptrackersimulator.sensor.MovementClassifier
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModel(
    private val dataSource: AccelerometerDataSource,
    private val classifier: MovementClassifier = MovementClassifier(),
) : ViewModel() {
    private val foregroundActive = MutableStateFlow(false)
    private var lastMovementState: MovementState? = null

    val uiState = foregroundActive.flatMapLatest { active ->
        if (!active) {
            classifier.reset()
            flowOf(SensorUiState.Paused)
        } else {
            dataSource.observe()
                .map { event ->
                    when (event) {
                        is AccelerometerEvent.Measurement -> SensorUiState.Active(classifier.analyze(event.sample).also { analysis ->
                            val previous = lastMovementState
                            if (previous != null && previous != analysis.state) {
                                DiagnosticLog.event(DiagnosticTags.SENSORS, "movementTransition", DiagnosticResult.SUCCESS, details = mapOf("from" to previous, "to" to analysis.state))
                            }
                            lastMovementState = analysis.state
                        })
                        AccelerometerEvent.Unavailable -> SensorUiState.Unavailable.also {
                            DiagnosticLog.event(DiagnosticTags.SENSORS, "monitoring", DiagnosticResult.FAILED, details = mapOf("reason" to "sensor_unavailable"))
                        }
                        is AccelerometerEvent.Error -> SensorUiState.Error(event.message).also {
                            DiagnosticLog.event(DiagnosticTags.SENSORS, "monitoring", DiagnosticResult.FAILED, details = mapOf("reason" to event.message))
                        }
                    }
                }
                .onStart {
                    classifier.reset()
                    emit(SensorUiState.Initializing)
                }
                .catch { error ->
                    DiagnosticLog.event(DiagnosticTags.SENSORS, "monitoring", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
                    emit(SensorUiState.Error(error.message ?: "Unexpected sensor error"))
                }
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
            initialValue = SensorUiState.Paused,
        )

    fun setForegroundActive(active: Boolean) {
        if (foregroundActive.value == active) {
            DiagnosticLog.event(DiagnosticTags.SENSORS, if (active) "startMonitoring" else "stopMonitoring", DiagnosticResult.SKIPPED, details = mapOf("reason" to "already_in_requested_state"))
            return
        }
        DiagnosticLog.event(DiagnosticTags.SENSORS, if (active) "startMonitoring" else "stopMonitoring", DiagnosticResult.SUCCESS, source = "LIFECYCLE")
        if (!active) lastMovementState = null
        foregroundActive.value = active
    }

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
