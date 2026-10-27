package com.example.sleeptrackersimulator.domain.recording

import com.example.sleeptrackersimulator.cloud.InMemoryCloudStateStore
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.Prediction
import com.example.sleeptrackersimulator.core.model.SessionStatus
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
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import com.example.sleeptrackersimulator.ml.ActivityPrediction
import com.example.sleeptrackersimulator.ml.ActivityClass as MlActivityClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionProcessDeathRecoveryTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var sensorSampleDao: FakeSensorSampleDao
    private lateinit var heartRateSampleDao: FakeHeartRateSampleDao
    private lateinit var featureWindowDao: FakeFeatureWindowDao
    private lateinit var predictionDao: FakePredictionDao
    private lateinit var sessionSummaryDao: FakeSessionSummaryDao
    private lateinit var personalBaselineDao: FakePersonalBaselineDao
    private lateinit var deviceDao: FakeDeviceDao

    private lateinit var repository: RoomSessionRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        sessionDao = FakeSessionDao()
        sensorSampleDao = FakeSensorSampleDao()
        heartRateSampleDao = FakeHeartRateSampleDao()
        featureWindowDao = FakeFeatureWindowDao()
        predictionDao = FakePredictionDao()
        sessionSummaryDao = FakeSessionSummaryDao()
        personalBaselineDao = FakePersonalBaselineDao()
        deviceDao = FakeDeviceDao()

        repository = RoomSessionRepository(
            sessionDao = sessionDao,
            sensorSampleDao = sensorSampleDao,
            heartRateSampleDao = heartRateSampleDao,
            featureWindowDao = featureWindowDao,
            predictionDao = predictionDao,
            sessionSummaryDao = sessionSummaryDao,
            personalBaselineDao = personalBaselineDao,
            deviceDao = deviceDao,
            dispatcher = testDispatcher,
        )
    }

    @Test
    fun `reconstructs counters and movement episodes accurately across process death`() = runTest(testDispatcher) {
        // --- 1. Coordinator 1 (Before Process Death) ---
        val coordinator1 = SessionCoordinatorImpl(
            sessionRepository = repository,
            activityClassifier = null,
            dispatcher = testDispatcher,
        )

        val startResult = coordinator1.startSession(
            sourceMode = SourceMode.PHONE_ONLY,
            placement = DevicePlacement.MATTRESS,
            samplingHz = 20,
        )
        assertTrue(startResult.isSuccess)
        val session = startResult.getOrThrow()
        val sessionId = session.sessionId

        // Simulate 3 predictions already classified and persisted to Room before crash:
        // Window 1 (t=1000): REST (not movement)
        // Window 2 (t=2000): RESTLESS (movement episode 1 starts)
        // Window 3 (t=3000): ACTIVE (movement episode 1 continues)
        val preDeathPredictions = listOf(
            Prediction(
                windowId = "w1",
                sessionId = sessionId,
                modelId = "activity-v2.1",
                predictedClass = ActivityClass.REST,
                probabilities = mapOf(ActivityClass.REST to 0.9f),
                confidence = 0.9f,
                inferenceDurationNs = 1_000_000L,
                timestampMillis = 1000L,
            ),
            Prediction(
                windowId = "w2",
                sessionId = sessionId,
                modelId = "activity-v2.1",
                predictedClass = ActivityClass.RESTLESS,
                probabilities = mapOf(ActivityClass.RESTLESS to 0.85f),
                confidence = 0.85f,
                inferenceDurationNs = 1_000_000L,
                timestampMillis = 2000L,
            ),
            Prediction(
                windowId = "w3",
                sessionId = sessionId,
                modelId = "activity-v2.1",
                predictedClass = ActivityClass.ACTIVE,
                probabilities = mapOf(ActivityClass.ACTIVE to 0.95f),
                confidence = 0.95f,
                inferenceDurationNs = 1_000_000L,
                timestampMillis = 3000L,
            ),
        )
        repository.insertPredictions(preDeathPredictions)

        // --- 2. Process Death Simulation ---
        // Coordinator 1 instance is destroyed.
        // Process relaunches.
        // Coordinator 2 is created with the persistent repository.
        var nextActivity = MlActivityClass.ACTIVE
        val controllableClassifier = object : ActivityClassifier {
            override fun classify(window: Array<FloatArray>, windowEndTimestampNanos: Long): ActivityPrediction {
                return ActivityPrediction(
                    predictionId = "pred-$windowEndTimestampNanos",
                    windowEndTimestampNanos = windowEndTimestampNanos,
                    activity = nextActivity,
                    confidence = 0.9f,
                    probabilities = mapOf(nextActivity to 0.9f),
                    inferenceNanos = 500_000L,
                )
            }
            override fun close() {}
        }

        val cloudStore = InMemoryCloudStateStore()
        val coordinator2 = SessionCoordinatorImpl(
            sessionRepository = repository,
            activityClassifier = controllableClassifier,
            cloudStateStore = cloudStore,
            dispatcher = testDispatcher,
        )

        advanceUntilIdle()

        assertEquals(SessionStatus.INTERRUPTED, coordinator2.sessionStatus.value)
        assertEquals(
            "Recording process was restarted; persisted samples were preserved",
            coordinator2.activeSession.value?.interruptionReason,
        )

        // Explicitly recover session
        val recoverResult = coordinator2.recoverSession(sessionId)
        assertTrue(recoverResult.isSuccess)
        assertEquals(SessionStatus.RECORDING, coordinator2.sessionStatus.value)

        // --- 3. Window 4 (Post-Crash): ACTIVE ---
        // Since Window 3 (pre-crash) was ACTIVE (isMovement=true),
        // Window 4 (ACTIVE) should NOT trigger a new movement episode!
        nextActivity = MlActivityClass.ACTIVE
        for (i in 1..20) {
            coordinator2.ingestSensorEvent(
                sourceId = "phone",
                elapsedNanos = 4_000_000_000L + (i * 50_000_000L),
                x = 1.0f,
                y = 1.0f,
                z = 9.8f,
                accuracy = 3,
            )
        }
        advanceUntilIdle()

        // --- 4. Window 5 (Post-Crash): REST ---
        nextActivity = MlActivityClass.REST
        for (i in 1..20) {
            coordinator2.ingestSensorEvent(
                sourceId = "phone",
                elapsedNanos = 5_000_000_000L + (i * 50_000_000L),
                x = 0.0f,
                y = 0.0f,
                z = 9.81f,
                accuracy = 3,
            )
        }
        advanceUntilIdle()

        // --- 5. Window 6 (Post-Crash): RESTLESS ---
        // This transition from REST -> RESTLESS MUST start movement episode 2!
        nextActivity = MlActivityClass.RESTLESS
        for (i in 1..20) {
            coordinator2.ingestSensorEvent(
                sourceId = "phone",
                elapsedNanos = 6_000_000_000L + (i * 50_000_000L),
                x = 0.5f,
                y = 0.5f,
                z = 9.81f,
                accuracy = 3,
            )
        }
        advanceUntilIdle()

        // --- 6. Stop session and verify summary ---
        val stopResult = coordinator2.stopSession()
        assertTrue(stopResult.isSuccess)
        val completedSession = stopResult.getOrThrow()
        assertEquals(SessionStatus.COMPLETED, completedSession.status)

        val summary = repository.getSessionSummary(sessionId)
        assertNotNull(summary)

        // Verifications:
        // Total predictions: 3 (pre-crash) + 3 (post-crash) = 6
        // Movement episodes: Episode 1 (w2, w3, w4) + Episode 2 (w6) = Exactly 2 episodes!
        // No double-counting at process-death boundary!
        assertEquals(2, summary!!.movementEpisodeCount)

        // REST count: w1 (pre), w5 (post) = 2
        // RESTLESS count: w2 (pre), w6 (post) = 2
        // ACTIVE count: w3 (pre), w4 (post) = 2
        // lowMotionFraction = rest / total = 2 / 6 = 0.3333f
        assertEquals(2.0f / 6.0f, summary.lowMotionFraction, 0.01f)

        // Cloud Outbox verification
        assertEquals(1, cloudStore.pendingCount)
        val outboxOp = cloudStore.peek()
        assertNotNull(outboxOp)
        assertEquals("summary-$sessionId", outboxOp!!.operationId)
        assertEquals(PendingOperationType.UPLOAD_SESSION_SUMMARY, outboxOp.type)
        assertEquals(sessionId, outboxOp.sessionId)
        assertNotNull(outboxOp.summary)
        assertEquals(2, outboxOp.summary!!.schemaVersion)
    }

    @Test
    fun `recovers counters accurately when stopped immediately without new samples`() = runTest(testDispatcher) {
        val coordinator1 = SessionCoordinatorImpl(
            sessionRepository = repository,
            activityClassifier = null,
            dispatcher = testDispatcher,
        )

        val startResult = coordinator1.startSession()
        val session = startResult.getOrThrow()
        val sessionId = session.sessionId

        val preDeathPredictions = listOf(
            Prediction(
                windowId = "w1",
                sessionId = sessionId,
                modelId = "activity-v2.1",
                predictedClass = ActivityClass.REST,
                probabilities = mapOf(ActivityClass.REST to 0.95f),
                confidence = 0.95f,
                inferenceDurationNs = 1_000_000L,
                timestampMillis = 1000L,
            ),
            Prediction(
                windowId = "w2",
                sessionId = sessionId,
                modelId = "activity-v2.1",
                predictedClass = ActivityClass.REST,
                probabilities = mapOf(ActivityClass.REST to 0.92f),
                confidence = 0.92f,
                inferenceDurationNs = 1_000_000L,
                timestampMillis = 2000L,
            ),
        )
        repository.insertPredictions(preDeathPredictions)

        // Process dies and recreates
        val coordinator2 = SessionCoordinatorImpl(
            sessionRepository = repository,
            activityClassifier = null,
            dispatcher = testDispatcher,
        )

        advanceUntilIdle()

        // Immediate stop
        val stopResult = coordinator2.stopSession()
        assertTrue(stopResult.isSuccess)

        val summary = repository.getSessionSummary(sessionId)
        assertNotNull(summary)
        assertEquals(0, summary!!.movementEpisodeCount)
        assertEquals(1.0f, summary.lowMotionFraction, 0.001f)
    }
}
