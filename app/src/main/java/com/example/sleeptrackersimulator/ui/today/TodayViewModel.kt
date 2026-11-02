package com.example.sleeptrackersimulator.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.data.ble.SwitchingDeviceTransport
import com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
import com.example.sleeptrackersimulator.domain.recording.SessionCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val activeSession: RecordingSession? = null,
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val lastSummary: SessionSummary? = null,
    val activeDurationSeconds: Long = 0,
    val selectedSourceMode: SourceMode = SourceMode.PHONE_ONLY,
    val selectedPlacement: DevicePlacement = DevicePlacement.MATTRESS,
    val connectedDevice: DeviceInfo? = null,
    val deviceConnectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val connectedHeartRate: Int? = null,
    val errorMessage: String? = null,
    val showOnboarding: Boolean = false,
)

class TodayViewModel(
    private val sessionCoordinator: SessionCoordinator,
    private val analyticsRepository: AnalyticsRepository,
    private val isFirstRun: () -> Boolean = { false },
    private val markOnboardingCompleted: () -> Unit = {},
    private val deviceTransport: SwitchingDeviceTransport? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        if (isFirstRun()) {
            _uiState.update { it.copy(showOnboarding = true) }
        }

        // Observe BLE wearable transport if available
        if (deviceTransport != null) {
            viewModelScope.launch {
                deviceTransport.connectionState.collect { state ->
                    _uiState.update { it.copy(deviceConnectionState = state) }
                }
            }
            viewModelScope.launch {
                deviceTransport.connectedDevice.collect { device ->
                    _uiState.update { current -> current.copy(
                        connectedDevice = device,
                        selectedSourceMode = if (device != null && current.selectedSourceMode == SourceMode.PHONE_ONLY) {
                            SourceMode.COMBINED
                        } else current.selectedSourceMode,
                    ) }
                }
            }
            viewModelScope.launch {
                deviceTransport.heartRateStream.collect { hrSample ->
                    _uiState.update { it.copy(connectedHeartRate = hrSample.bpm) }
                }
            }
        }
        // Observe active session from coordinator
        viewModelScope.launch {
            var previousSession: RecordingSession? = null
            sessionCoordinator.activeSession.collect { session ->
                _uiState.update { it.copy(
                    activeSession = session,
                    sessionStatus = session?.status ?: SessionStatus.IDLE,
                ) }
                if (previousSession != null && session == null) {
                    val recent = analyticsRepository.getRecentSummaries(days = 14)
                    _uiState.update { it.copy(lastSummary = recent.firstOrNull()) }
                }
                previousSession = session
            }
        }

        // Load most recent summary
        viewModelScope.launch {
            val recent = analyticsRepository.getRecentSummaries(days = 14)
            _uiState.update { it.copy(lastSummary = recent.firstOrNull()) }
        }

        // Live ticker for active duration
        viewModelScope.launch {
            while (true) {
                val active = _uiState.value.activeSession
                if (active != null && active.status == SessionStatus.RECORDING) {
                    val durationSec = (System.currentTimeMillis() - active.startUtcMillis) / 1000L
                    _uiState.update { it.copy(activeDurationSeconds = durationSec) }
                }
                delay(1000L)
            }
        }
    }

    fun startRecording(mode: SourceMode = _uiState.value.selectedSourceMode, placement: DevicePlacement = _uiState.value.selectedPlacement) {
        viewModelScope.launch {
            val result = sessionCoordinator.startSession(sourceMode = mode, placement = placement)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            }
        }
    }

    fun pauseRecording() {
        viewModelScope.launch {
            sessionCoordinator.pauseSession()
        }
    }

    fun resumeRecording() {
        viewModelScope.launch {
            sessionCoordinator.resumeSession()
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val result = sessionCoordinator.stopSession()
            if (result.isSuccess) {
                val recent = analyticsRepository.getRecentSummaries(days = 14)
                _uiState.update { it.copy(lastSummary = recent.firstOrNull()) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setPlacement(placement: DevicePlacement) {
        _uiState.update { it.copy(selectedPlacement = placement) }
    }

    fun setSourceMode(mode: SourceMode) {
        _uiState.update { it.copy(selectedSourceMode = mode) }
    }

    fun reportServiceCommandFailure(operation: String, error: Throwable?) {
        _uiState.update { it.copy(
            errorMessage = "$operation failed: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}",
        ) }
    }

    fun dismissOnboarding() {
        _uiState.update { it.copy(showOnboarding = false) }
        markOnboardingCompleted()
    }

    fun openOnboarding() {
        _uiState.update { it.copy(showOnboarding = true) }
    }

    companion object {
        fun factory(
            sessionCoordinator: SessionCoordinator,
            analyticsRepository: AnalyticsRepository,
            context: android.content.Context? = null,
            deviceTransport: SwitchingDeviceTransport? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val prefs = context?.getSharedPreferences("sleepsense_settings", android.content.Context.MODE_PRIVATE)
                return TodayViewModel(
                    sessionCoordinator = sessionCoordinator,
                    analyticsRepository = analyticsRepository,
                    isFirstRun = { prefs?.getBoolean("has_completed_onboarding", false)?.not() ?: false },
                    markOnboardingCompleted = { prefs?.edit()?.putBoolean("has_completed_onboarding", true)?.apply() },
                    deviceTransport = deviceTransport,
                ) as T
            }
        }
    }
}
