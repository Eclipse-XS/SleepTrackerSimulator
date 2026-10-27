package com.example.sleeptrackersimulator.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository
import com.example.sleeptrackersimulator.domain.recording.SessionCoordinatorImpl
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DataFlowIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: SleepTrackerDatabase
    private lateinit var sessionRepository: RoomSessionRepository
    private lateinit var classifier: ActivityClassifier
    private lateinit var coordinator: SessionCoordinatorImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SleepTrackerDatabase::class.java).build()
        sessionRepository = RoomSessionRepository(
            sessionDao = database.sessionDao(),
            sensorSampleDao = database.sensorSampleDao(),
            heartRateSampleDao = database.heartRateSampleDao(),
            featureWindowDao = database.featureWindowDao(),
            predictionDao = database.predictionDao(),
            sessionSummaryDao = database.sessionSummaryDao(),
            personalBaselineDao = database.personalBaselineDao(),
            deviceDao = database.deviceDao(),
        )
        classifier = ActivityClassifier(context)
        coordinator = SessionCoordinatorImpl(
            sessionRepository = sessionRepository,
            activityClassifier = classifier,
        )
    }

    @After
    fun tearDown() {
        classifier.close()
        database.close()
    }

    @Test
    fun completePipeline_sensorsToWindowsToTfliteToRoomToSummaryToInsights() = runBlocking {
        // 1. Start Session
        val startResult = coordinator.startSession(
            sourceMode = SourceMode.PHONE_ONLY,
            placement = DevicePlacement.MATTRESS,
            samplingHz = 20,
        )
        assertTrue(startResult.isSuccess)
        val session = startResult.getOrThrow()
        val sessionId = session.sessionId

        // 2. Feed 20 Still Samples (Window 1: Rest)
        var elapsedNanos = 1_000_000_000L
        for (i in 0 until 20) {
            coordinator.ingestSensorEvent(
                sourceId = "phone-accel",
                elapsedNanos = elapsedNanos,
                x = 0.05f * sin(i.toDouble()).toFloat(),
                y = 0.02f,
                z = 9.81f,
                accuracy = 3,
                sourceKind = SourceKind.PHONE_REAL,
            )
            elapsedNanos += 50_000_000L // 50ms = 20Hz
        }

        // 3. Feed 20 Movement Samples (Window 2: Movement)
        for (i in 0 until 20) {
            val wave = sin(i * 1.5).toFloat() * 3.2f
            coordinator.ingestSensorEvent(
                sourceId = "phone-accel",
                elapsedNanos = elapsedNanos,
                x = wave,
                y = -wave * 0.5f,
                z = 9.81f + wave * 0.4f,
                accuracy = 3,
                sourceKind = SourceKind.PHONE_REAL,
            )
            elapsedNanos += 50_000_000L
        }

        // Ingest a heart rate sample as well
        coordinator.ingestHeartRateSample(
            deviceId = "ble-band-01",
            bpm = 64,
            utcTimestampMillis = System.currentTimeMillis(),
            sourceKind = SourceKind.BLE_REAL,
        )

        // 4. Verify feature windows and predictions generated in-flight
        val storedWindows = database.featureWindowDao().getWindowsForSession(sessionId)
        assertEquals("Expected exactly 2 windows of 20 samples each", 2, storedWindows.size)
        assertTrue(storedWindows.all { it.sampleCount == 20 && it.expectedSampleCount == 20 })

        val storedPredictions = database.predictionDao().getPredictionsForSession(sessionId)
        assertEquals("Expected 2 TFLite predictions", 2, storedPredictions.size)
        assertTrue(storedPredictions.all { it.modelId == "activity-v2.1" })
        assertTrue(storedPredictions.all { it.windowId.isNotBlank() })
        assertTrue(storedPredictions.all { it.confidence > 0f })

        // First window should be REST, second window should be ACTIVE or RESTLESS
        assertEquals(ActivityClass.REST, storedPredictions[0].toDomain().predictedClass)
        assertTrue(storedPredictions[1].toDomain().predictedClass.isMovement)

        // 5. Stop session and verify summary calculation + sample batch flushing
        val stopResult = coordinator.stopSession()
        assertTrue(stopResult.isSuccess)

        val storedSamples = database.sensorSampleDao().getSamplesForSession(sessionId)
        assertEquals("Expected 40 raw accelerometer samples flushed to Room", 40, storedSamples.size)

        val summary = sessionRepository.getSessionSummary(sessionId)
        assertNotNull("Session summary must be generated and persisted", summary)
        assertEquals(sessionId, summary?.sessionId)
        assertEquals(SourceKind.PHONE_REAL, summary?.sourceKind)
        assertEquals(DevicePlacement.MATTRESS, summary?.devicePlacement)
        assertEquals(1, summary?.movementEpisodeCount)
        assertEquals(0.5f, summary!!.lowMotionFraction, 0.05f)
        assertTrue(summary.dataCoverageFraction >= 0.99f)

        // 6. Simulate Process Restart: instantiate fresh repository on same DB
        val restartedRepo = RoomSessionRepository(
            sessionDao = database.sessionDao(),
            sensorSampleDao = database.sensorSampleDao(),
            heartRateSampleDao = database.heartRateSampleDao(),
            featureWindowDao = database.featureWindowDao(),
            predictionDao = database.predictionDao(),
            sessionSummaryDao = database.sessionSummaryDao(),
            personalBaselineDao = database.personalBaselineDao(),
            deviceDao = database.deviceDao(),
        )

        val recentSessions = restartedRepo.observeRecentSessions(10).first()
        assertEquals(1, recentSessions.size)
        assertEquals(sessionId, recentSessions[0].sessionId)

        val retrievedSummary = restartedRepo.getSessionSummary(sessionId)
        assertNotNull(retrievedSummary)
        assertEquals(summary.observationDurationMs, retrievedSummary?.observationDurationMs)
        assertEquals(summary.lowMotionFraction, retrievedSummary!!.lowMotionFraction, 0.001f)
    }
}
