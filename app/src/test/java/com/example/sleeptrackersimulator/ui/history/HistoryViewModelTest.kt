package com.example.sleeptrackersimulator.ui.history

import com.example.sleeptrackersimulator.cloud.AuthRepository
import com.example.sleeptrackersimulator.cloud.CloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.CloudSyncConsentRepository
import com.example.sleeptrackersimulator.cloud.InMemoryCloudStateStore
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.cloud.SensorPayload
import com.example.sleeptrackersimulator.cloud.SessionSummaryPayload
import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.domain.recording.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `deleteSession when cloud sync consent is disabled still executes cloud data erasure`() = runTest(dispatcher) {
        val sessionRepo = FakeSessionRepo()
        val cloudRepo = RecordingCloudRepo()
        val consentRepo = FakeConsentRepo(enabled = false)

        val viewModel = HistoryViewModel(
            sessionRepository = sessionRepo,
            cloudRepository = cloudRepo,
            authRepository = FakeAuthRepo("uid-123"),
            cloudSyncConsentRepository = consentRepo,
        )

        viewModel.deleteSession("session-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("session-1"), sessionRepo.deletedSessions)
        assertEquals(listOf("session-1"), cloudRepo.deletedSessions)
    }

    @Test
    fun `deleteSession when cloud sync consent is enabled invokes both local and cloud delete`() = runTest(dispatcher) {
        val sessionRepo = FakeSessionRepo()
        val cloudRepo = RecordingCloudRepo()
        val consentRepo = FakeConsentRepo(enabled = true)

        val viewModel = HistoryViewModel(
            sessionRepository = sessionRepo,
            cloudRepository = cloudRepo,
            authRepository = FakeAuthRepo("uid-123"),
            cloudSyncConsentRepository = consentRepo,
        )

        viewModel.deleteSession("session-2")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("session-2"), sessionRepo.deletedSessions)
        assertEquals(listOf("session-2"), cloudRepo.deletedSessions)
    }

    @Test
    fun `deleteSession when cloud delete fails enqueues outbox operation and schedules recovery`() = runTest(dispatcher) {
        val sessionRepo = FakeSessionRepo()
        val cloudRepo = RecordingCloudRepo(shouldFail = true)
        val consentRepo = FakeConsentRepo(enabled = true)
        val cloudStore = InMemoryCloudStateStore()
        val recoveryScheduler = CountingScheduler()

        val viewModel = HistoryViewModel(
            sessionRepository = sessionRepo,
            cloudRepository = cloudRepo,
            authRepository = FakeAuthRepo("uid-123"),
            cloudSyncConsentRepository = consentRepo,
            cloudStateStore = cloudStore,
            cloudRecoveryScheduler = recoveryScheduler,
        )

        viewModel.deleteSession("session-3")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("session-3"), sessionRepo.deletedSessions)
        assertEquals(1, cloudStore.pendingCount)
        val queued = cloudStore.peek()
        assertEquals("session-3", queued?.sessionId)
        assertEquals(PendingOperationType.DELETE_SESSION, queued?.type)
        assertEquals(1, recoveryScheduler.calls)
    }

    private class FakeSessionRepo : SessionRepository {
        val deletedSessions = mutableListOf<String>()
        override suspend fun saveSession(session: RecordingSession) = Unit
        override suspend fun getSessionById(sessionId: String): RecordingSession? = null
        override fun observeAllSessions(): Flow<List<RecordingSession>> = flowOf(emptyList())
        override suspend fun getActiveSession(): RecordingSession? = null
        override suspend fun updateSession(session: RecordingSession) = Unit
        override suspend fun deleteSession(sessionId: String) { deletedSessions.add(sessionId) }
        override suspend fun insertSamplesBatch(samples: List<com.example.sleeptrackersimulator.core.model.RawSensorSample>) = Unit
        override suspend fun getSamplesForSession(sessionId: String) = emptyList<com.example.sleeptrackersimulator.core.model.RawSensorSample>()
        override suspend fun insertHeartRateSamples(samples: List<com.example.sleeptrackersimulator.core.model.HeartRateSample>) = Unit
        override suspend fun getHeartRateForSession(sessionId: String) = emptyList<com.example.sleeptrackersimulator.core.model.HeartRateSample>()
        override suspend fun insertFeatureWindows(windows: List<com.example.sleeptrackersimulator.core.model.FeatureWindow>) = Unit
        override suspend fun getFeatureWindows(sessionId: String) = emptyList<com.example.sleeptrackersimulator.core.model.FeatureWindow>()
        override suspend fun insertPredictions(predictions: List<com.example.sleeptrackersimulator.core.model.Prediction>) = Unit
        override suspend fun getPredictionsForSession(sessionId: String) = emptyList<com.example.sleeptrackersimulator.core.model.Prediction>()
        override suspend fun saveSessionSummary(summary: SessionSummary) = Unit
        override suspend fun getSessionSummary(sessionId: String): SessionSummary? = null
        override fun observeActiveSession(): Flow<RecordingSession?> = flowOf(null)
        override fun observeAllSummaries(): Flow<List<SessionSummary>> = flowOf(emptyList())
    }

    private class RecordingCloudRepo(val shouldFail: Boolean = false) : CloudRepository {
        val deletedSessions = mutableListOf<String>()
        override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) = Unit
        override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) = Unit
        override suspend fun uploadRuleEvent(ownerUid: String, sessionId: String, payload: SmartRuleEventPayload) = Unit
        override suspend fun uploadSessionSummary(ownerUid: String, payload: SessionSummaryPayload) = Unit
        override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) = Unit
        override suspend fun deleteSession(ownerUid: String, sessionId: String) {
            if (shouldFail) throw RuntimeException("Network offline")
            deletedSessions.add(sessionId)
        }
        override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = Unit
    }

    private class FakeAuthRepo(private val uid: String) : AuthRepository {
        override suspend fun ensureAuthenticated(): String = uid
        override val currentUid: String = uid
    }

    private class FakeConsentRepo(enabled: Boolean) : CloudSyncConsentRepository {
        override val isCloudSyncEnabled = MutableStateFlow(enabled)
        override fun setCloudSyncEnabled(enabled: Boolean) { isCloudSyncEnabled.value = enabled }
    }

    private class CountingScheduler : CloudRecoveryScheduler {
        var calls = 0
        override fun schedule() { calls++ }
    }
}
