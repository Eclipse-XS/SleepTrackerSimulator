package com.example.sleeptrackersimulator.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sleeptrackersimulator.cloud.FIREBASE_DATABASE_URL
import com.example.sleeptrackersimulator.cloud.FirebaseAnonymousAuthRepository
import com.example.sleeptrackersimulator.cloud.FirebaseCloudRepository
import com.example.sleeptrackersimulator.cloud.PendingCloudOperation
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.SessionSummaryPayload
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.data.sessions.RoomSessionRepository
import com.example.sleeptrackersimulator.domain.recording.SessionCoordinatorImpl
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudOfflineE2EIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: SleepTrackerDatabase
    private lateinit var store: RoomCloudStateStore
    private lateinit var sessionRepository: RoomSessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SleepTrackerDatabase::class.java).build()
        store = RoomCloudStateStore(dao = database.cloudOutboxDao())
        sessionRepository = RoomSessionRepository(
            sessionDao = database.sessionDao(),
            sensorSampleDao = database.sensorSampleDao(),
            heartRateSampleDao = database.heartRateSampleDao(),
            featureWindowDao = database.featureWindowDao(),
            predictionDao = database.predictionDao(),
            sessionSummaryDao = database.sessionSummaryDao(),
            personalBaselineDao = database.personalBaselineDao(),
            deviceDao = database.deviceDao(),
            database = database,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun offlineSessionEnd_queuesToRoomOutbox_survivesRestart_andSyncsToRtdbEmulator() {
        if (InstrumentationRegistry.getArguments().getString("firebaseE2E") != "true") {
            // Run pure local outbox verification if emulator arg is not explicitly set
            runBlocking {
                verifyLocalOutboxQueueAndRestart()
            }
            return
        }

        runBlocking {
            val auth = FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2", 9099) }
            val firebase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL).apply { useEmulator("10.0.2.2", 9000) }
            val uid = FirebaseAnonymousAuthRepository(auth).ensureAuthenticated()
            val cloud = FirebaseCloudRepository(firebase)

            val coordinator = SessionCoordinatorImpl(
                sessionRepository = sessionRepository,
                activityClassifier = null,
                cloudRepository = cloud,
                cloudStateStore = store,
                cloudRecoveryScheduler = null, // Manual drain in test
            )

            // 1. Start and stop session offline
            val startRes = coordinator.startSession(
                sourceMode = SourceMode.PHONE_ONLY,
                placement = DevicePlacement.MATTRESS,
                samplingHz = 20,
            )
            val session = startRes.getOrThrow()
            val sessionId = session.sessionId

            coordinator.stopSession().getOrThrow()

            // 2. Verify Room Outbox has pending operation
            assertEquals("Outbox must hold exactly 1 pending operation", 1, store.pendingCount)
            val pending = requireNotNull(store.peek())
            assertEquals("summary-$sessionId", pending.operationId)
            assertEquals(PendingOperationType.UPLOAD_SESSION_SUMMARY, pending.type)
            assertEquals(sessionId, pending.sessionId)
            val summaryPayload = requireNotNull(pending.summary)
            assertEquals(sessionId, summaryPayload.sessionId)
            assertEquals(2, summaryPayload.schemaVersion)

            // 3. Process restart simulation: new store instance pointing to same database
            val restartedStore = RoomCloudStateStore(dao = database.cloudOutboxDao())
            assertEquals("Outbox operations must persist across process restart", 1, restartedStore.pendingCount)
            val restartedOp = requireNotNull(restartedStore.peek())
            assertEquals(pending.operationId, restartedOp.operationId)

            try {
                // 4. Network Restored: Deliver outbox operation to Firebase RTDB Emulator
                cloud.uploadSessionSummary(uid, requireNotNull(restartedOp.summary))

                // 5. ACK: Remove from store
                restartedStore.removeFirst()
                assertEquals("Outbox should be empty after ACK", 0, restartedStore.pendingCount)

                // 6. Verify actual data in Firebase RTDB Emulator
                val remoteSummarySnapshot = firebase.reference
                    .child("users/$uid/sessionSummaries/$sessionId")
                    .get()
                    .await()

                assertTrue("Remote summary must exist in RTDB", remoteSummarySnapshot.exists())
                assertEquals(sessionId, remoteSummarySnapshot.child("sessionId").getValue(String::class.java))
                assertEquals(2L, remoteSummarySnapshot.child("schemaVersion").getValue(Long::class.java))
                assertEquals(DevicePlacement.MATTRESS.name, remoteSummarySnapshot.child("placement").getValue(String::class.java))

                // 7. Idempotent replay: sending same payload again causes no duplicates or errors
                cloud.uploadSessionSummary(uid, requireNotNull(restartedOp.summary))
                val replaySnapshot = firebase.reference
                    .child("users/$uid/sessionSummaries/$sessionId")
                    .get()
                    .await()
                assertEquals(sessionId, replaySnapshot.child("sessionId").getValue(String::class.java))
            } finally {
                // Cleanup remote test data
                runCatching {
                    firebase.reference.child("users/$uid/sessionSummaries/$sessionId").removeValue().await()
                }
            }
        }
    }

    private suspend fun verifyLocalOutboxQueueAndRestart() {
        val coordinator = SessionCoordinatorImpl(
            sessionRepository = sessionRepository,
            activityClassifier = null,
            cloudStateStore = store,
            cloudRecoveryScheduler = null,
        )

        val session = coordinator.startSession(
            sourceMode = SourceMode.PHONE_ONLY,
            placement = DevicePlacement.MATTRESS,
            samplingHz = 20,
        ).getOrThrow()

        coordinator.stopSession().getOrThrow()

        // Verify outbox
        assertEquals(1, store.pendingCount)
        val op = store.peek()
        assertNotNull(op)
        assertEquals(PendingOperationType.UPLOAD_SESSION_SUMMARY, op?.type)
        assertEquals(session.sessionId, op?.sessionId)

        // Verify restart
        val restartedStore = RoomCloudStateStore(dao = database.cloudOutboxDao())
        assertEquals(1, restartedStore.pendingCount)
        assertEquals(op?.operationId, restartedStore.peek()?.operationId)

        // Verify ACK removal
        restartedStore.removeFirst()
        assertEquals(0, restartedStore.pendingCount)
    }
}
