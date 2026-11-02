package com.example.sleeptrackersimulator.cloud

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudErasureConsentPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class RecordingCloudRepository : CloudRepository {
        val createdSessions = mutableListOf<String>()
        val uploads = mutableListOf<SensorPayload>()
        val deletedSessions = mutableListOf<String>()
        val summaries = mutableListOf<SessionSummaryPayload>()
        var throwOnDelete: CloudOperationException? = null

        override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) {
            createdSessions += sessionId
        }

        override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) {
            uploads += payload
        }

        override suspend fun uploadRuleEvent(ownerUid: String, sessionId: String, payload: SmartRuleEventPayload) = Unit
        override suspend fun uploadSessionSummary(ownerUid: String, payload: SessionSummaryPayload) { summaries += payload }
        override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) = Unit

        override suspend fun deleteSession(ownerUid: String, sessionId: String) {
            throwOnDelete?.let { throw it }
            deletedSessions += sessionId
        }

        override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = Unit
    }

    private class FakeAuth(
        private val uid: String = "test-uid-123",
        var shouldFail: Boolean = false,
    ) : AuthRepository {
        override suspend fun ensureAuthenticated(): String {
            if (shouldFail) throw CloudOperationException(CloudErrorType.AUTH, "Authentication failed")
            return uid
        }
        override val currentUid: String get() = uid
    }

    @Test
    fun `test 1 - consent ON upload then consent OFF delete executes deletion successfully`() = runTest {
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()
        val store = InMemoryCloudStateStore()

        // 1. Consent ON: Session active and measurement uploaded
        store.saveActiveSession(ActiveCloudSession("session-1", 1000L, "test-uid-123", true))
        store.enqueue(PendingCloudOperation("m-1", PendingOperationType.UPLOAD_MEASUREMENT, "session-1", payload = SensorPayload()))
        assertEquals(Result.success(), CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = true))
        assertEquals(1, repository.uploads.size)
        assertEquals(0, store.pendingCount)

        // 2. Consent OFF: User requests data erasure (Right to be Forgotten)
        store.enqueue(PendingCloudOperation("del-1", PendingOperationType.DELETE_SESSION, "session-1"))
        assertEquals(1, store.pendingCount)

        // Drain with consent OFF: deletion executes, Right to be Forgotten is preserved
        val drainResult = CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)
        assertEquals(listOf("session-1"), repository.deletedSessions)
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `test 2 - consent OFF prevents new uploads and purges unconsented operations`() = runTest {
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()
        val store = InMemoryCloudStateStore()

        store.saveActiveSession(ActiveCloudSession("session-2", 1000L, "test-uid-123", true))
        store.enqueue(PendingCloudOperation("m-2", PendingOperationType.UPLOAD_MEASUREMENT, "session-2", payload = SensorPayload()))
        assertEquals(1, store.pendingCount)

        // Drain with consent OFF must purge unconsented uploads and make NO network calls
        val drainResult = CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)
        assertEquals(0, repository.uploads.size)
        assertEquals(0, repository.createdSessions.size)
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `test 3 - offline cloud deletion enqueues and reconnect completes deletion`() = runTest {
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()
        val store = InMemoryCloudStateStore()

        // User requests deletion while offline -> enqueued in durable outbox
        store.enqueue(PendingCloudOperation("del-offline", PendingOperationType.DELETE_SESSION, "session-offline"))
        assertEquals(1, store.pendingCount)
        assertEquals(0, repository.deletedSessions.size)

        // Network restored (worker drains) -> completed
        val drainResult = CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)
        assertEquals(listOf("session-offline"), repository.deletedSessions)
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `test 4 - consent withdrawal while deletion is pending purges uploads but preserves deletion`() = runTest {
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()
        val store = InMemoryCloudStateStore()

        store.saveActiveSession(ActiveCloudSession("session-4", 1000L, "test-uid-123", true))
        // Interleaved operations: deletion followed by upload, or upload followed by deletion
        store.enqueue(PendingCloudOperation("del-4", PendingOperationType.DELETE_SESSION, "session-4"))
        store.enqueue(PendingCloudOperation("m-4", PendingOperationType.UPLOAD_MEASUREMENT, "session-4", payload = SensorPayload()))
        assertEquals(2, store.pendingCount)

        // Consent withdrawn
        val drainResult = CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)
        // Deletion was executed
        assertEquals(listOf("session-4"), repository.deletedSessions)
        // Upload was purged, not sent
        assertEquals(0, repository.uploads.size)
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `test 5 - deletion across multiple sessions executes each deletion idempotently`() = runTest {
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()
        val store = InMemoryCloudStateStore()

        store.enqueue(PendingCloudOperation("del-s1", PendingOperationType.DELETE_SESSION, "session-A"))
        store.enqueue(PendingCloudOperation("del-s2", PendingOperationType.DELETE_SESSION, "session-B"))
        store.enqueue(PendingCloudOperation("del-s3", PendingOperationType.DELETE_SESSION, "session-C"))
        assertEquals(3, store.pendingCount)

        val drainResult = CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)
        assertEquals(listOf("session-A", "session-B", "session-C"), repository.deletedSessions)
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `test 6 - app restart with pending deletion reloads from disk and drains successfully`() = runTest {
        val stateFile = tempFolder.newFile("cloud-state.json")
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()

        // First process instance: enqueue DELETE_SESSION and crash before draining
        val initialStore = AtomicJsonCloudStateStore(stateFile)
        initialStore.enqueue(PendingCloudOperation("del-persist", PendingOperationType.DELETE_SESSION, "session-persisted"))
        assertEquals(1, initialStore.pendingCount)

        // Simulate app restart: instantiate fresh store pointing to the persisted file
        val restartedStore = AtomicJsonCloudStateStore(stateFile)
        assertEquals(1, restartedStore.pendingCount)
        assertEquals(PendingOperationType.DELETE_SESSION, restartedStore.peek()?.type)

        // Drain on restarted app
        val drainResult = CloudOutboxDrainer.drain(restartedStore, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)
        assertEquals(listOf("session-persisted"), repository.deletedSessions)
        assertEquals(0, restartedStore.pendingCount)

        // Verify state is clean across subsequent reload
        val verifiedStore = AtomicJsonCloudStateStore(stateFile)
        assertEquals(0, verifiedStore.pendingCount)
    }

    @Test
    fun `test 7 - cloud authorization failure handles error gracefully without crashing`() = runTest {
        val repository = RecordingCloudRepository()
        val unauthenticatedAuth = FakeAuth(shouldFail = true)
        val store = InMemoryCloudStateStore()

        store.enqueue(PendingCloudOperation("del-auth-fail", PendingOperationType.DELETE_SESSION, "session-auth-fail"))
        assertEquals(1, store.pendingCount)

        // Drain fails with non-retriable auth error
        val drainResult = CloudOutboxDrainer.drain(store, repository, unauthenticatedAuth, isConsentEnabled = false)
        assertEquals(Result.failure(), drainResult)
        // Operation is preserved for when credentials or sign-in is refreshed
        assertEquals(1, store.pendingCount)
        assertEquals(0, repository.deletedSessions.size)
    }

    @Test
    fun `test 8 - no resurrection or re-upload of previously deleted records`() = runTest {
        val repository = RecordingCloudRepository()
        val auth = FakeAuth()
        val store = InMemoryCloudStateStore()

        // Suppose a session was active or ended
        store.saveActiveSession(ActiveCloudSession("session-deleted", 1000L, "test-uid-123", false))
        store.enqueue(PendingCloudOperation("del-no-resurrect", PendingOperationType.DELETE_SESSION, "session-deleted"))

        // Drain deletion with consent disabled
        val drainResult = CloudOutboxDrainer.drain(store, repository, auth, isConsentEnabled = false)
        assertEquals(Result.success(), drainResult)

        // CRITICAL CHECK: createSession MUST NOT have been called.
        // Resurrecting deleted records in Firebase Realtime Database is strictly prohibited.
        assertTrue("createSession must never be called when draining deletions", repository.createdSessions.isEmpty())
        assertEquals(listOf("session-deleted"), repository.deletedSessions)
        assertEquals(0, repository.uploads.size)
    }

    @Test
    fun `session summary drains after monitoring session has ended`() = runTest {
        val repository = RecordingCloudRepository()
        val store = InMemoryCloudStateStore()
        val summary = SessionSummaryPayload(sessionId = "completed-session")
        store.enqueue(
            PendingCloudOperation(
                operationId = "summary-completed-session",
                type = PendingOperationType.UPLOAD_SESSION_SUMMARY,
                sessionId = "completed-session",
                summary = summary,
            ),
        )

        assertNull(store.activeSession())
        assertEquals(Result.success(), CloudOutboxDrainer.drain(store, repository, FakeAuth(), true))
        assertEquals(listOf(summary), repository.summaries)
        assertEquals(0, store.pendingCount)
    }
}
