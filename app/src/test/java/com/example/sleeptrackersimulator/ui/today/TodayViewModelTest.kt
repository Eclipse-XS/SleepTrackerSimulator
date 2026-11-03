package com.example.sleeptrackersimulator.ui.today

import com.example.sleeptrackersimulator.core.model.BaselineComparison
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.PersonalBaseline
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
import com.example.sleeptrackersimulator.domain.recording.SessionCoordinator
import com.example.sleeptrackersimulator.domain.recording.LiveInferenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TodayViewModelTest {

    private class FakeSessionCoordinator : SessionCoordinator {
        private val _session = MutableStateFlow<RecordingSession?>(null)
        override val activeSession: StateFlow<RecordingSession?> = _session
        override val sessionStatus: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.IDLE)
        override val liveInference: StateFlow<LiveInferenceState> = MutableStateFlow(LiveInferenceState())

        override suspend fun startSession(sourceMode: SourceMode, placement: DevicePlacement, samplingHz: Int): Result<RecordingSession> =
            Result.success(
                RecordingSession(
                    sessionId = "s1",
                    startUtcMillis = 1000L,
                    startElapsedRealtimeNanos = 100L,
                    status = SessionStatus.RECORDING,
                    sourceMode = sourceMode,
                    devicePlacement = placement,
                )
            )

        override suspend fun pauseSession(): Result<Unit> = Result.success(Unit)
        override suspend fun resumeSession(): Result<Unit> = Result.success(Unit)
        override suspend fun stopSession(): Result<RecordingSession> = Result.success(
            RecordingSession(
                sessionId = "s1",
                startUtcMillis = 1000L,
                startElapsedRealtimeNanos = 100L,
                status = SessionStatus.COMPLETED,
            )
        )
        override suspend fun failSession(reason: String, droppedSamples: Long): Result<RecordingSession> = Result.failure(Exception(reason))
        override suspend fun reportDroppedSamples(count: Long) {}
        override suspend fun recoverSession(sessionId: String): Result<RecordingSession> = Result.failure(Exception("None"))
        override suspend fun discardSession(): Result<Unit> = Result.success(Unit)
        override suspend fun ingestSensorEvent(
            sourceId: String,
            elapsedNanos: Long,
            x: Float,
            y: Float,
            z: Float,
            accuracy: Int,
            sourceKind: SourceKind,
        ) {}
        override suspend fun ingestHeartRateSample(
            deviceId: String,
            bpm: Int,
            utcTimestampMillis: Long,
            timingUncertaintyMillis: Long,
            sourceKind: SourceKind,
        ) {}
    }

    private class FakeAnalyticsRepository : AnalyticsRepository {
        override fun observeAllSummaries(): Flow<List<SessionSummary>> = flowOf(emptyList())
        override suspend fun getAllSummaries(): List<SessionSummary> = emptyList()
        override suspend fun getRecentSummaries(days: Int): List<SessionSummary> = emptyList()
        override fun observeRecentSessions(limit: Int): Flow<List<RecordingSession>> = flowOf(emptyList())
        override suspend fun getSessionSummary(sessionId: String): SessionSummary? = null
        override suspend fun getPersonalBaseline(sourceMode: SourceMode, placement: DevicePlacement): PersonalBaseline? = null
        override suspend fun recalculateBaseline(sourceMode: SourceMode, placement: DevicePlacement): PersonalBaseline? = null
        override suspend fun compareSessionWithBaseline(sessionId: String): BaselineComparison? = null
    }

    @Test
    fun testFirstRunTriggersOnboardingDisplay() = runTest {
        val completed = AtomicBoolean(false)
        val vm = TodayViewModel(
            sessionCoordinator = FakeSessionCoordinator(),
            analyticsRepository = FakeAnalyticsRepository(),
            isFirstRun = { true },
            markOnboardingCompleted = { completed.set(true) },
        )

        assertTrue(vm.uiState.value.showOnboarding)
        assertFalse(completed.get())
    }

    @Test
    fun testDismissOnboardingMarksCompletedAndHidesDialog() = runTest {
        val completed = AtomicBoolean(false)
        val vm = TodayViewModel(
            sessionCoordinator = FakeSessionCoordinator(),
            analyticsRepository = FakeAnalyticsRepository(),
            isFirstRun = { true },
            markOnboardingCompleted = { completed.set(true) },
        )

        assertTrue(vm.uiState.value.showOnboarding)

        vm.dismissOnboarding()

        assertFalse(vm.uiState.value.showOnboarding)
        assertTrue(completed.get())
    }

    @Test
    fun testReopenOnboardingShowsDialogAgain() = runTest {
        val vm = TodayViewModel(
            sessionCoordinator = FakeSessionCoordinator(),
            analyticsRepository = FakeAnalyticsRepository(),
            isFirstRun = { false },
            markOnboardingCompleted = {},
        )

        assertFalse(vm.uiState.value.showOnboarding)

        vm.openOnboarding()

        assertTrue(vm.uiState.value.showOnboarding)
    }

    @Test
    fun testNightstandPlacementSelectionUpdatesState() = runTest {
        val vm = TodayViewModel(
            sessionCoordinator = FakeSessionCoordinator(),
            analyticsRepository = FakeAnalyticsRepository(),
        )

        assertEquals(DevicePlacement.MATTRESS, vm.uiState.value.selectedPlacement)

        vm.setPlacement(DevicePlacement.NIGHTSTAND)

        assertEquals(DevicePlacement.NIGHTSTAND, vm.uiState.value.selectedPlacement)
    }

    @Test
    fun combinedModeRequiresCurrentWearableStream() = runTest {
        val vm = TodayViewModel(
            sessionCoordinator = FakeSessionCoordinator(),
            analyticsRepository = FakeAnalyticsRepository(),
        )

        assertEquals(SourceMode.PHONE_ONLY, vm.uiState.value.selectedSourceMode)

        vm.setSourceMode(SourceMode.COMBINED)

        assertEquals(SourceMode.PHONE_ONLY, vm.uiState.value.selectedSourceMode)
        assertTrue(vm.uiState.value.errorMessage?.contains("current heart-rate stream") == true)
    }
}
