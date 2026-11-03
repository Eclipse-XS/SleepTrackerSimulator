package com.example.sleeptrackersimulator.cloud

import androidx.work.ListenableWorker.Result
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseFailureAndOutboxRegressionTest {
    private class Auth : AuthRepository {
        override suspend fun ensureAuthenticated() = "uid"
        override val currentUid = "uid"
    }

    private class Repository(
        private val deleteFailure: CloudOperationException? = null,
    ) : CloudRepository {
        val deleted = mutableListOf<String>()
        override suspend fun createSession(ownerUid: String, sessionId: String, deviceId: String, startedAt: Long) = Unit
        override suspend fun uploadMeasurement(ownerUid: String, sessionId: String, payload: SensorPayload) = Unit
        override suspend fun uploadRuleEvent(ownerUid: String, sessionId: String, payload: SmartRuleEventPayload) = Unit
        override suspend fun uploadSessionSummary(ownerUid: String, payload: SessionSummaryPayload) = Unit
        override suspend fun endSession(ownerUid: String, sessionId: String, endedAt: Long) = Unit
        override suspend fun deleteSession(ownerUid: String, sessionId: String) {
            deleteFailure?.let { throw it }
            deleted += sessionId
        }
        override suspend fun applyRetention(ownerUid: String, activeSessionId: String?, now: Long) = Unit
    }

    @Test
    fun `permission denied is permanent and never classified for retry`() {
        val type = classifyFirebaseFailure(IllegalStateException("Firebase Database error: Permission denied"))
        val failure = CloudOperationException(type, "denied")
        assertEquals(CloudErrorType.PERMISSION, type)
        assertFalse(failure.retriable)
    }

    @Test
    fun `transport failures remain network retry candidates`() {
        assertEquals(CloudErrorType.NETWORK, classifyFirebaseFailure(IOException("network disconnected")))
        assertTrue(CloudOperationException(CloudErrorType.NETWORK, "offline").retriable)
    }

    @Test
    fun `network deletion retry becomes permanent at bounded attempt count`() = runTest {
        val store = InMemoryCloudStateStore()
        store.enqueue(
            PendingCloudOperation(
                operationId = "delete",
                type = PendingOperationType.DELETE_SESSION,
                sessionId = "s1",
                retryCount = CloudOutboxDrainer.MAX_RETRY_ATTEMPTS - 1,
            ),
        )

        val result = CloudOutboxDrainer.drain(
            store,
            Repository(CloudOperationException(CloudErrorType.NETWORK, "offline")),
            Auth(),
        )

        assertEquals(Result.failure(), result)
        assertEquals(DeliveryState.PERMANENT_FAILURE, store.pendingOperations().single().deliveryState)
        assertEquals(CloudOutboxDrainer.MAX_RETRY_ATTEMPTS, store.pendingOperations().single().retryCount)
    }

    @Test
    fun `deletion bypasses unrelated permanently failed upload without mutating it`() = runTest {
        val store = InMemoryCloudStateStore()
        val failedUpload = PendingCloudOperation(
            operationId = "upload",
            type = PendingOperationType.UPLOAD_SESSION_SUMMARY,
            sessionId = "other",
            summary = SessionSummaryPayload(sessionId = "other"),
            retryCount = 1,
            lastError = "permission denied",
            deliveryState = DeliveryState.PERMANENT_FAILURE,
        )
        store.enqueue(failedUpload)
        store.enqueue(PendingCloudOperation("delete", PendingOperationType.DELETE_SESSION, "erase-me"))
        val repository = Repository()

        assertEquals(Result.failure(), CloudOutboxDrainer.drain(store, repository, Auth()))
        assertEquals(listOf("erase-me"), repository.deleted)
        assertEquals(listOf(failedUpload), store.pendingOperations())
    }

    @Test
    fun `atomic json outbox preserves complete queue across restart`() {
        val file = kotlin.io.path.createTempDirectory("outbox-regression").resolve("state.json").toFile()
        val first = AtomicJsonCloudStateStore(file)
        repeat(75) { index ->
            first.enqueue(PendingCloudOperation("op-$index", PendingOperationType.UPLOAD_SESSION_SUMMARY, "s-$index"))
        }

        val restarted = AtomicJsonCloudStateStore(file)
        assertEquals(75, restarted.pendingCount)
        assertEquals((0 until 75).map { "op-$it" }, restarted.pendingOperations().map { it.operationId })
    }
}
