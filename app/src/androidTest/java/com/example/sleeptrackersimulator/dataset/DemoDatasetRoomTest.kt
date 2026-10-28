package com.example.sleeptrackersimulator.dataset

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SourceMode
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
}
