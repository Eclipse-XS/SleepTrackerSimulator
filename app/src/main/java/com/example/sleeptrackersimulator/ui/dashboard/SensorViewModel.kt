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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SensorViewModel(
    private val dataSource: AccelerometerDataSource,
    private val classifier: MovementClassifier = MovementClassifier(),
) : ViewModel() {
    private val foregroundActive = MutableStateFlow(false)
    private var lastMovementState: MovementState? = null
    private val mutableUiState = MutableStateFlow<SensorUiState>(SensorUiState.Paused)
    val uiState: StateFlow<SensorUiState> = mutableUiState.asStateFlow()
    private val mutableSamples = MutableSharedFlow<com.example.sleeptrackersimulator.core.model.SensorSample>(
        extraBufferCapacity = 64,
    )
    /** Ordered, non-conflated valid measurements from the single owned SensorManager listener. */
    val samples: SharedFlow<com.example.sleeptrackersimulator.core.model.SensorSample> = mutableSamples.asSharedFlow()

    init {
        viewModelScope.launch {
            foregroundActive.collectLatest { active ->
                if (!active) {
                    classifier.reset()
                    lastMovementState = null
                    mutableUiState.value = SensorUiState.Paused
                    return@collectLatest
                }
                classifier.reset()
                mutableUiState.value = SensorUiState.Initializing
                try {
                    dataSource.observe().collect { event ->
                        mutableUiState.value = when (event) {
                        is AccelerometerEvent.Measurement -> if (
                            !event.sample.x.isFinite() || !event.sample.y.isFinite() ||
                            !event.sample.z.isFinite() || event.sample.timestampNanos <= 0L ||
                            kotlin.math.abs(event.sample.x) > 200f || kotlin.math.abs(event.sample.y) > 200f ||
                            kotlin.math.abs(event.sample.z) > 200f
                        ) {
                            classifier.reset()
                            lastMovementState = null
                            SensorUiState.Error("Invalid accelerometer measurement")
                        } else {
                            mutableSamples.emit(event.sample)
                            SensorUiState.Active(classifier.analyze(event.sample).also { analysis ->
                            val previous = lastMovementState
                            if (previous != null && previous != analysis.state) {
                                DiagnosticLog.event(DiagnosticTags.SENSORS, "movementTransition", DiagnosticResult.SUCCESS, details = mapOf("from" to previous, "to" to analysis.state))
                            }
                            lastMovementState = analysis.state
                            })
                        }
                        AccelerometerEvent.Unavailable -> SensorUiState.Unavailable.also {
                            DiagnosticLog.event(DiagnosticTags.SENSORS, "monitoring", DiagnosticResult.FAILED, details = mapOf("reason" to "sensor_unavailable"))
                        }
                        is AccelerometerEvent.Error -> SensorUiState.Error(event.message).also {
                            DiagnosticLog.event(DiagnosticTags.SENSORS, "monitoring", DiagnosticResult.FAILED, details = mapOf("reason" to event.message))
                        }
                        }
                    }
                } catch (error: Exception) {
                    DiagnosticLog.event(DiagnosticTags.SENSORS, "monitoring", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
                    mutableUiState.value = SensorUiState.Error(error.message ?: "Unexpected sensor error")
                }
            }
        }
    }

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
