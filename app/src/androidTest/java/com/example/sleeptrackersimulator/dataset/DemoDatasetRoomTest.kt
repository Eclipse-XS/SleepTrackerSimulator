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
import com.example.sleeptrackersimulator.data.sessions.DataRetentionPolicy
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

    @Test fun retentionPreservesAllDemoRows() = runBlocking {
        repository.importDemoNights(DemoDatasetGenerator().generate())
        DataRetentionPolicy(
            db.sensorSampleDao(), db.heartRateSampleDao(), db.featureWindowDao(), db.predictionDao(), db.sessionDao(),
        ).pruneExpiredData(now = Long.MAX_VALUE / 2)

        assertEquals(90, repository.countDemoNights())
        assertEquals(4_320, repository.countDemoWindows())
        assertEquals(4_320, repository.countDemoPredictions())
        assertTrue(repository.verifyDemoNights(nowUtcMillis = Long.MAX_VALUE).isEmpty())
    }

    @Test fun retainedDemoRowsSurviveDatabaseRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "demo-retention-restart.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, SleepTrackerDatabase::class.java, name)
            .allowMainThreadQueries().build()
        fun repo(database: SleepTrackerDatabase) = RoomSessionRepository(
            database.sessionDao(), database.sensorSampleDao(), database.heartRateSampleDao(), database.featureWindowDao(),
            database.predictionDao(), database.sessionSummaryDao(), database.personalBaselineDao(), database.deviceDao(), database,
        )
        var fileDb = open()
        var fileRepository = repo(fileDb)
        try {
            fileRepository.importDemoNights(DemoDatasetGenerator().generate())
            DataRetentionPolicy(
                fileDb.sensorSampleDao(), fileDb.heartRateSampleDao(), fileDb.featureWindowDao(),
                fileDb.predictionDao(), fileDb.sessionDao(),
            ).pruneExpiredData(now = Long.MAX_VALUE / 2)
            fileDb.close()

            fileDb = open()
            fileRepository = repo(fileDb)
            assertEquals(90, fileRepository.countDemoNights())
            assertEquals(4_320, fileRepository.countDemoWindows())
            assertEquals(4_320, fileRepository.countDemoPredictions())
            assertTrue(fileRepository.verifyDemoNights(nowUtcMillis = Long.MAX_VALUE).isEmpty())
        } finally {
            fileDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun sourceAndPlacementSpecificBaselinesNeverMixCandidates() = runBlocking {
        val groups = listOf(
            Triple(SourceMode.PHONE_ONLY, DevicePlacement.MATTRESS, 0.20f),
            Triple(SourceMode.COMBINED, DevicePlacement.MATTRESS, 0.40f),
            Triple(SourceMode.WEARABLE_ONLY, DevicePlacement.MATTRESS, 0.60f),
            Triple(SourceMode.PHONE_ONLY, DevicePlacement.NIGHTSTAND, 0.80f),
        )
        groups.forEachIndexed { groupIndex, (mode, placement, lowMotion) ->
            repeat(3) { item ->
                val id = "baseline-$groupIndex-$item"
                repository.saveSession(
                    completedSession(id).copy(sourceMode = mode, devicePlacement = placement),
                )
                repository.saveSessionSummary(
                    summaryFor(completedSession(id)).copy(
                        observationDurationMs = 8 * 3_600_000L,
                        validDurationMs = 8 * 3_600_000L,
                        dataCoverageFraction = 0.95f,
                        lowMotionFraction = lowMotion,
                        devicePlacement = placement,
                    ),
                )
            }
        }

        groups.forEach { (mode, placement, expectedMedian) ->
            val baseline = repository.recalculateBaseline(mode, placement)
            assertNotNull(baseline)
            assertEquals(3, baseline?.compatibleSessionCount)
            assertEquals(expectedMedian, baseline?.medianLowMotionFraction ?: -1f, 0.0001f)
        }
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
