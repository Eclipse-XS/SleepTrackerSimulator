package com.example.sleeptrackersimulator.domain.recording

import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.data.sessions.FakeDeviceDao
import com.example.sleeptrackersimulator.data.sessions.FakeFeatureWindowDao
import com.example.sleeptrackersimulator.data.sessions.FakeHeartRateSampleDao
import com.example.sleeptrackersimulator.data.sessions.FakePersonalBaselineDao
import com.example.sleeptrackersimulator.data.sessions.FakePredictionDao
import com.example.sleeptrackersimulator.data.sessions.FakeSensorSampleDao
import com.example.sleeptrackersimulator.data.sessions.FakeSessionDao
import com.example.sleeptrackersimulator.data.sessions.FakeSessionSummaryDao
import com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {

    private lateinit var repository: RoomSessionRepository
    private lateinit var coordinator: SessionCoordinatorImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        repository = RoomSessionRepository(
            sessionDao = FakeSessionDao(),
            sensorSampleDao = FakeSensorSampleDao(),
            heartRateSampleDao = FakeHeartRateSampleDao(),
            featureWindowDao = FakeFeatureWindowDao(),
            predictionDao = FakePredictionDao(),
            sessionSummaryDao = FakeSessionSummaryDao(),
            personalBaselineDao = FakePersonalBaselineDao(),
            deviceDao = FakeDeviceDao(),
            dispatcher = testDispatcher,
        )

        coordinator = SessionCoordinatorImpl(
            sessionRepository = repository,
            activityClassifier = null, // Heuristic pipeline test without loading native TFLite
            dispatcher = testDispatcher,
        )
    }

    @Test
    fun `start session initializes state and persists session`() = runTest(testDispatcher) {
        val result = coordinator.startSession(
            sourceMode = SourceMode.PHONE_ONLY,
            placement = DevicePlacement.MATTRESS,
            samplingHz = 20,
        )

        assertTrue(result.isSuccess)
        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals(SessionStatus.RECORDING, coordinator.sessionStatus.value)
        assertEquals(session!!.sessionId, coordinator.activeSession.value?.sessionId)

        // Attempting to start another active session should fail
        val secondResult = coordinator.startSession()
        assertTrue(secondResult.isFailure)
    }

    @Test
    fun `session coordinator pauses resumes and finishes cleanly`() = runTest(testDispatcher) {
        val startResult = coordinator.startSession()
        assertTrue(startResult.isSuccess)

        // Pause
        val pauseResult = coordinator.pauseSession()
        assertTrue(pauseResult.isSuccess)
        assertEquals(SessionStatus.PAUSED, coordinator.sessionStatus.value)

        // Resume
        val resumeResult = coordinator.resumeSession()
        assertTrue(resumeResult.isSuccess)
        assertEquals(SessionStatus.RECORDING, coordinator.sessionStatus.value)

        // Feed some sensor samples
        for (i in 1..25) {
            coordinator.ingestSensorEvent(
                sourceId = "phone",
                elapsedNanos = 1000L + (i * 50_000_000L),
                x = 0f,
                y = 0f,
                z = 9.81f,
                accuracy = 3,
                sourceKind = SourceKind.PHONE_REAL,
            )
        }

        // Stop session
        val stopResult = coordinator.stopSession()
        assertTrue(stopResult.isSuccess)
        val completed = stopResult.getOrNull()
        assertNotNull(completed)
        assertEquals(SessionStatus.COMPLETED, completed!!.status)
        assertEquals(SessionStatus.IDLE, coordinator.sessionStatus.value)
        assertNull(coordinator.activeSession.value)

        // Verify summary was saved
        val summary = repository.getSessionSummary(completed.sessionId)
        assertNotNull(summary)
        assertEquals(completed.sessionId, summary!!.sessionId)
        assertTrue(summary.dataCoverageFraction > 0.0f)
    }
}
