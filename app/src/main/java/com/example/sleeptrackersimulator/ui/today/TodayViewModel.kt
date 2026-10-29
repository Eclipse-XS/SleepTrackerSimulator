package com.example.sleeptrackersimulator.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
import com.example.sleeptrackersimulator.domain.recording.SessionCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUiState(
    val activeSession: RecordingSession? = null,
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val lastSummary: SessionSummary? = null,
    val activeDurationSeconds: Long = 0,
    val selectedSourceMode: SourceMode = SourceMode.PHONE_ONLY,
    val selectedPlacement: DevicePlacement = DevicePlacement.MATTRESS,
    val errorMessage: String? = null,
    val showOnboarding: Boolean = false,
)

class TodayViewModel(
    private val sessionCoordinator: SessionCoordinator,
    private val analyticsRepository: AnalyticsRepository,
    private val isFirstRun: () -> Boolean = { false },
    private val markOnboardingCompleted: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        if (isFirstRun()) {
            _uiState.value = _uiState.value.copy(showOnboarding = true)
        }
        // Observe active session from coordinator
        viewModelScope.launch {
            var previousSession: RecordingSession? = null
            sessionCoordinator.activeSession.collect { session ->
                _uiState.value = _uiState.value.copy(
                    activeSession = session,
                    sessionStatus = session?.status ?: SessionStatus.IDLE,
                )
                if (previousSession != null && session == null) {
                    val recent = analyticsRepository.getRecentSummaries(days = 14)
                    _uiState.value = _uiState.value.copy(lastSummary = recent.firstOrNull())
                }
                previousSession = session
            }
        }

        // Load most recent summary
        viewModelScope.launch {
            val recent = analyticsRepository.getRecentSummaries(days = 14)
            _uiState.value = _uiState.value.copy(lastSummary = recent.firstOrNull())
        }

        // Live ticker for active duration
        viewModelScope.launch {
            while (true) {
                val active = _uiState.value.activeSession
                if (active != null && active.status == SessionStatus.RECORDING) {
                    val durationSec = (System.currentTimeMillis() - active.startUtcMillis) / 1000L
                    _uiState.value = _uiState.value.copy(activeDurationSeconds = durationSec)
                }
                delay(1000L)
            }
        }
    }

    fun startRecording(mode: SourceMode = _uiState.value.selectedSourceMode, placement: DevicePlacement = _uiState.value.selectedPlacement) {
        viewModelScope.launch {
            val result = sessionCoordinator.startSession(sourceMode = mode, placement = placement)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(errorMessage = result.exceptionOrNull()?.message)
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
                _uiState.value = _uiState.value.copy(lastSummary = recent.firstOrNull())
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setPlacement(placement: DevicePlacement) {
        _uiState.value = _uiState.value.copy(selectedPlacement = placement)
    }

    fun setSourceMode(mode: SourceMode) {
        _uiState.value = _uiState.value.copy(selectedSourceMode = mode)
    }

    fun reportServiceCommandFailure(operation: String, error: Throwable?) {
        _uiState.value = _uiState.value.copy(
            errorMessage = "$operation failed: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}",
        )
    }

    fun dismissOnboarding() {
        _uiState.value = _uiState.value.copy(showOnboarding = false)
        markOnboardingCompleted()
    }

    fun openOnboarding() {
        _uiState.value = _uiState.value.copy(showOnboarding = true)
    }

    companion object {
        fun factory(
            sessionCoordinator: SessionCoordinator,
            analyticsRepository: AnalyticsRepository,
            context: android.content.Context? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val prefs = context?.getSharedPreferences("sleepsense_settings", android.content.Context.MODE_PRIVATE)
                return TodayViewModel(
                    sessionCoordinator = sessionCoordinator,
                    analyticsRepository = analyticsRepository,
                    isFirstRun = { prefs?.getBoolean("has_completed_onboarding", false)?.not() ?: false },
                    markOnboardingCompleted = { prefs?.edit()?.putBoolean("has_completed_onboarding", true)?.apply() },
                ) as T
            }
        }
    }
}
