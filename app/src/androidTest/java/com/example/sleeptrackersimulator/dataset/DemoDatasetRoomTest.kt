package com.example.sleeptrackersimulator.dataset

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SessionQualityStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.cloud.CloudOutboxEntity
import com.example.sleeptrackersimulator.cloud.PendingCloudOperation
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoDatasetRoomTest {
    private lateinit var db: SleepTrackerDatabase
    private lateinit var repository: RoomSessionRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SleepTrackerDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomSessionRepository(db.sessionDao(), db.sensorSampleDao(), db.heartRateSampleDao(), db.featureWindowDao(), db.predictionDao(), db.sessionSummaryDao(), db.personalBaselineDao(), db.deviceDao(), db)
    }

    @After fun close() = db.close()

    @Test fun importIsIdempotentAndResetPreservesRealSession() = runBlocking {
        val real = RecordingSession(
            sessionId = "real-session",
            startUtcMillis = 1000,
            endUtcMillis = 2000,
            startElapsedRealtimeNanos = 0,
            status = SessionStatus.COMPLETED,
            sourceMode = SourceMode.PHONE_ONLY,
            devicePlacement = DevicePlacement.MATTRESS,
        )
        repository.saveSession(real)
        val nights = DemoDatasetGenerator().generate()
        repository.importDemoNights(nights)
        repository.importDemoNights(nights)
        assertEquals(90, repository.countDemoNights())
        assertTrue(repository.verifyDemoNights().isEmpty())
        assertEquals(90, repository.resetDemoNights())
        assertEquals(0, repository.countDemoNights())
        assertNotNull(repository.getSessionById("real-session"))
    }

    @Test fun verificationReadsPersistedRowsAndRejectsCorruption() = runBlocking {
        val nights = DemoDatasetGenerator().generate()
        repository.importDemoNights(nights)
        repository.saveSessionSummary(nights.first().summary.copy(dataCoverageFraction = 1.5f))
        val errors = repository.verifyDemoNights()
        assertTrue(errors.any { it.startsWith("Invalid coverage") })
    }

    @Test fun verificationDetectsMissingSummary() = runBlocking {
        val nights = DemoDatasetGenerator().generate()
        repository.importDemoNights(nights.dropLast(1))
        repository.saveSession(nights.last().session)
        val errors = repository.verifyDemoNights()
        assertTrue(errors.any { it.startsWith("Missing summary") })
    }

    @Test fun clearAllLocalDataRemovesRealAndDemoSessions() = runBlocking {
        val real = RecordingSession(
            sessionId = "real-to-delete",
            startUtcMillis = 1000,
            endUtcMillis = 2000,
            startElapsedRealtimeNanos = 1,
            status = SessionStatus.COMPLETED,
            sourceMode = SourceMode.PHONE_ONLY,
            devicePlacement = DevicePlacement.MATTRESS,
        )
        repository.saveSession(real)
        repository.importDemoNights(DemoDatasetGenerator().generate())

        repository.clearAllLocalData()

        assertEquals(null, repository.getSessionById(real.sessionId))
        assertEquals(0, repository.countDemoNights())
    }

    @Test fun fullOutboxDoesNotPreventLocalSessionCompletion() = runBlocking {
        val session = completedSession("capacity-session")
        repository.saveSession(session.copy(status = SessionStatus.RECORDING, endUtcMillis = null))
        repeat(RoomCloudStateStore.DEFAULT_MAXIMUM_OPERATIONS) { index ->
            db.cloudOutboxDao().enqueue(
                CloudOutboxEntity(
                    operationId = "existing-$index", type = PendingOperationType.UPLOAD_SESSION_SUMMARY.name,
                    sessionId = "other-$index", payloadJson = "{}", endedAt = null, createdAt = index.toLong(),
                    retryCount = 0, lastError = null, deliveryState = "PENDING",
                ),
                RoomCloudStateStore.DEFAULT_MAXIMUM_OPERATIONS,
            )
        }
        val summary = summaryFor(session)

        val queued = repository.saveSessionSummaryAndOutbox(
            summary, session,
            PendingCloudOperation("summary-capacity", PendingOperationType.UPLOAD_SESSION_SUMMARY, session.sessionId),
        )

        assertEquals(false, queued)
        assertEquals(SessionStatus.COMPLETED, repository.getSessionById(session.sessionId)?.status)
        assertEquals(summary, repository.getSessionSummary(session.sessionId))
    }

    @Test fun crashDuringLocalClearRollsBackAndPreservesDeletionTombstone() = runBlocking {
        val tombstone = PendingCloudOperation("delete-durable", PendingOperationType.DELETE_SESSION, "remote-session")
        RoomCloudStateStore(db.cloudOutboxDao()).enqueue(tombstone)
        repository.saveSession(completedSession("local-session"))
        val crashingRepository = RoomSessionRepository(
            db.sessionDao(), db.sensorSampleDao(), db.heartRateSampleDao(), db.featureWindowDao(), db.predictionDao(),
            db.sessionSummaryDao(), db.personalBaselineDao(), db.deviceDao(), db,
            localDeletionCrashInjector = { error("injected crash") },
        )

        runCatching { crashingRepository.clearAllLocalData() }

        assertNotNull(repository.getSessionById("local-session"))
        assertEquals(listOf(tombstone), RoomCloudStateStore(db.cloudOutboxDao()).pendingOperations())
    }

    private fun completedSession(id: String) = RecordingSession(
        sessionId = id, startUtcMillis = 1_000, endUtcMillis = 2_000, startElapsedRealtimeNanos = 1,
        status = SessionStatus.COMPLETED, sourceMode = SourceMode.PHONE_ONLY,
        devicePlacement = DevicePlacement.MATTRESS, qualityStatus = SessionQualityStatus.GOOD,
    )

    private fun summaryFor(session: RecordingSession) = SessionSummary(
        sessionId = session.sessionId, observationDurationMs = 1_000, validDurationMs = 1_000,
        lowMotionFraction = 1f,
        movementEpisodeCount = 0, movementRatePerHour = 0f, dataCoverageFraction = 1f,
        sourceKind = SourceKind.PHONE_REAL,
    )
}
