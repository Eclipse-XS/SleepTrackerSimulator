package com.example.sleeptrackersimulator.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.cloud.AuthRepository
import com.example.sleeptrackersimulator.cloud.CloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.CloudStateStore
import com.example.sleeptrackersimulator.cloud.CloudSyncConsentRepository
import com.example.sleeptrackersimulator.cloud.PendingCloudOperation
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.Prediction
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.domain.recording.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryFilter {
    ALL,
    REAL_ONLY,
    DEMO_ONLY,
}

data class SessionDetailsUiState(
    val session: RecordingSession,
    val summary: SessionSummary?,
    val featureWindows: List<FeatureWindow> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
)

data class HistoryUiState(
    val sessions: List<RecordingSession> = emptyList(),
    val provenanceBySessionId: Map<String, SourceKind> = emptyMap(),
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val selectedSessionDetails: SessionDetailsUiState? = null,
    val isLoading: Boolean = false,
)

class HistoryViewModel(
    private val sessionRepository: SessionRepository,
    private val cloudRepository: CloudRepository? = null,
    private val authRepository: AuthRepository? = null,
    private val cloudSyncConsentRepository: CloudSyncConsentRepository? = null,
    private val cloudStateStore: CloudStateStore? = null,
    private val cloudRecoveryScheduler: CloudRecoveryScheduler? = null,
) : ViewModel() {

    private val _filter = MutableStateFlow(HistoryFilter.ALL)
    private val _selectedDetails = MutableStateFlow<SessionDetailsUiState?>(null)
    private val _isLoading = MutableStateFlow(false)

    val uiState: StateFlow<HistoryUiState> = combine(
        sessionRepository.observeAllSessions(),
        sessionRepository.observeAllSummaries(),
        _filter,
        _selectedDetails,
        _isLoading,
    ) { sessions, summaries, filter, details, loading ->
        val filtered = when (filter) {
            HistoryFilter.ALL -> sessions
            HistoryFilter.REAL_ONLY -> sessions.filter { it.sourceMode.requiresPhoneSensor || it.sourceMode.requiresBleConnection }
            HistoryFilter.DEMO_ONLY -> sessions.filter { it.sourceMode.isSynthetic }
        }
        HistoryUiState(
            sessions = filtered,
            provenanceBySessionId = summaries.associate { it.sessionId to it.sourceKind },
            selectedFilter = filter,
            selectedSessionDetails = details,
            isLoading = loading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryUiState(),
    )

    fun setFilter(filter: HistoryFilter) {
        _filter.value = filter
    }

    fun openSessionDetails(sessionId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val session = sessionRepository.getSessionById(sessionId)
            if (session != null) {
                val summary = sessionRepository.getSessionSummary(sessionId)
                val windows = sessionRepository.getFeatureWindows(sessionId)
                val predictions = sessionRepository.getPredictionsForSession(sessionId)

                _selectedDetails.value = SessionDetailsUiState(
                    session = session,
                    summary = summary,
                    featureWindows = windows,
                    predictions = predictions,
                )
            }
            _isLoading.value = false
        }
    }

    fun closeSessionDetails() {
        _selectedDetails.value = null
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.deleteSession(sessionId)
            if (_selectedDetails.value?.session?.sessionId == sessionId) {
                _selectedDetails.value = null
            }
            if (cloudRepository != null) {
                val uid = authRepository?.currentUid
                if (uid != null) {
                    val result = runCatching { cloudRepository.deleteSession(uid, sessionId) }
                    if (result.isFailure && cloudStateStore != null) {
                        cloudStateStore.enqueue(
                            PendingCloudOperation(
                                operationId = "delete-$sessionId",
                                type = PendingOperationType.DELETE_SESSION,
                                sessionId = sessionId,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        cloudRecoveryScheduler?.schedule()
                    }
                } else if (cloudStateStore != null) {
                    cloudStateStore.enqueue(
                        PendingCloudOperation(
                            operationId = "delete-$sessionId",
                            type = PendingOperationType.DELETE_SESSION,
                            sessionId = sessionId,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    cloudRecoveryScheduler?.schedule()
                }
            }
        }
    }

    companion object {
        fun factory(
            sessionRepository: SessionRepository,
            cloudRepository: CloudRepository? = null,
            authRepository: AuthRepository? = null,
            cloudSyncConsentRepository: CloudSyncConsentRepository? = null,
            cloudStateStore: CloudStateStore? = null,
            cloudRecoveryScheduler: CloudRecoveryScheduler? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HistoryViewModel(
                        sessionRepository = sessionRepository,
                        cloudRepository = cloudRepository,
                        authRepository = authRepository,
                        cloudSyncConsentRepository = cloudSyncConsentRepository,
                        cloudStateStore = cloudStateStore,
                        cloudRecoveryScheduler = cloudRecoveryScheduler,
                    ) as T
                }
            }
    }
}
