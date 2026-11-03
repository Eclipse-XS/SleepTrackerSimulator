package com.example.sleeptrackersimulator.cloud

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface CloudStateStore {
    val pendingCount: Int
    val droppedTelemetryCount: Long
    val loadError: String?
    fun enqueue(operation: PendingCloudOperation): EnqueueResult
    fun peek(): PendingCloudOperation?
    fun removeFirst()
    fun peekDeletion(): PendingCloudOperation? = pendingOperations().firstOrNull { it.type == PendingOperationType.DELETE_SESSION }
    fun removeOperation(operationId: String): Boolean = false
    fun pendingOperations(): List<PendingCloudOperation>
    fun activeSession(): ActiveCloudSession?
    fun saveActiveSession(session: ActiveCloudSession?)
    fun completeSessionEnd(operationId: String, sessionId: String)
    fun recordFirstFailure(message: String, permanent: Boolean) = Unit
    fun recordOperationFailure(operationId: String, message: String, permanent: Boolean) = Unit
    fun removeOperationsForSession(sessionId: String): Int = 0
    fun purgeUnconsentedUploads(): Int = 0
    fun reconcileDeliveryIntents(): Int = 0
    fun completeDeletion(operationId: String, sessionId: String, ownerUid: String) {
        removeOperationsForSession(sessionId)
    }
}

data class PersistedCloudState(
    val operations: List<PendingCloudOperation> = emptyList(),
    val activeSession: ActiveCloudSession? = null,
    val droppedTelemetryCount: Long = 0,
    val deletedSessions: Map<String, String> = emptyMap(),
)

data class EnqueueResult(val droppedTelemetry: Int = 0)

class AtomicJsonCloudStateStore(
    private val file: File,
    private val capacity: Int = CLOUD_PENDING_QUEUE_CAPACITY,
    private val gson: Gson = Gson(),
) : CloudStateStore {
    private var initialLoadError: String? = null
    private var state = readSafely()

    init { require(capacity > 0) }

    @get:Synchronized override val pendingCount: Int get() = state.operations.size
    @get:Synchronized override val droppedTelemetryCount: Long get() = state.droppedTelemetryCount
    override val loadError: String? get() = initialLoadError

    @Synchronized override fun enqueue(operation: PendingCloudOperation): EnqueueResult {
        if (operation.type != PendingOperationType.DELETE_SESSION && operation.sessionId in state.deletedSessions) {
            return EnqueueResult()
        }
        val base = if (operation.type == PendingOperationType.DELETE_SESSION) {
            state.copy(
                operations = state.operations.filterNot { it.sessionId == operation.sessionId },
                deletedSessions = state.deletedSessions + (operation.sessionId to ""),
            )
        } else state
        val updated = base.operations + operation
        val dropped = 0
        persist(
            base.copy(
                operations = updated,
                droppedTelemetryCount = state.droppedTelemetryCount + dropped,
            ),
        )
        return EnqueueResult(dropped)
    }

    @Synchronized override fun peek(): PendingCloudOperation? = state.operations.firstOrNull()

    @Synchronized override fun removeFirst() {
        if (state.operations.isNotEmpty()) persist(state.copy(operations = state.operations.drop(1)))
    }

    @Synchronized override fun removeOperation(operationId: String): Boolean {
        val filtered = state.operations.filterNot { it.operationId == operationId }
        if (filtered.size == state.operations.size) return false
        persist(state.copy(operations = filtered))
        return true
    }

    @Synchronized override fun recordOperationFailure(operationId: String, message: String, permanent: Boolean) {
        val index = state.operations.indexOfFirst { it.operationId == operationId }
        if (index < 0) return
        val operation = state.operations[index]
        val updated = operation.copy(
            retryCount = minOf(operation.retryCount + 1, CloudOutboxDrainer.MAX_RETRY_ATTEMPTS),
            lastError = message,
            deliveryState = if (permanent) DeliveryState.PERMANENT_FAILURE else DeliveryState.PENDING,
        )
        persist(state.copy(operations = state.operations.toMutableList().also { it[index] = updated }))
    }

    @Synchronized override fun pendingOperations(): List<PendingCloudOperation> = state.operations.toList()

    @Synchronized override fun activeSession(): ActiveCloudSession? = state.activeSession

    @Synchronized override fun saveActiveSession(session: ActiveCloudSession?) {
        persist(state.copy(activeSession = session))
    }

    @Synchronized override fun completeSessionEnd(operationId: String, sessionId: String) {
        persist(state.copy(
            operations = state.operations.filterNot { it.operationId == operationId },
            activeSession = state.activeSession?.takeUnless { it.sessionId == sessionId },
        ))
    }

    @Synchronized override fun removeOperationsForSession(sessionId: String): Int {
        val before = state.operations.size
        val filtered = state.operations.filterNot { it.sessionId == sessionId }
        val removed = before - filtered.size
        if (removed > 0) {
            persist(state.copy(operations = filtered))
        }
        return removed
    }

    @Synchronized override fun completeDeletion(operationId: String, sessionId: String, ownerUid: String) {
        persist(state.copy(
            operations = state.operations.filterNot { it.sessionId == sessionId },
            deletedSessions = state.deletedSessions + (sessionId to ownerUid),
        ))
    }

    @Synchronized override fun purgeUnconsentedUploads(): Int {
        val (erasureOps, uploadOps) = state.operations.partition { it.type == PendingOperationType.DELETE_SESSION }
        if (uploadOps.isNotEmpty()) {
            persist(state.copy(operations = erasureOps))
        }
        return uploadOps.size
    }

    private fun readSafely(): PersistedCloudState = try {
        if (!file.exists()) PersistedCloudState()
        else gson.fromJson(file.readText(), PersistedCloudState::class.java) ?: PersistedCloudState()
    } catch (error: Exception) {
        initialLoadError = "Cloud state was unreadable and preserved as ${file.name}.corrupt"
        runCatching {
            Files.move(
                file.toPath(),
                file.resolveSibling("${file.name}.corrupt").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        PersistedCloudState()
    }

    private fun persist(next: PersistedCloudState) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(gson.toJson(next))
        try {
            Files.move(
                temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        state = next
    }
}

class InMemoryCloudStateStore(
    private val capacity: Int = CLOUD_PENDING_QUEUE_CAPACITY,
) : CloudStateStore {
    private var state = PersistedCloudState()
    override val pendingCount: Int get() = state.operations.size
    override val droppedTelemetryCount: Long get() = state.droppedTelemetryCount
    override val loadError: String? = null
    override fun enqueue(operation: PendingCloudOperation): EnqueueResult {
        if (operation.type != PendingOperationType.DELETE_SESSION && operation.sessionId in state.deletedSessions) return EnqueueResult()
        if (operation.type == PendingOperationType.DELETE_SESSION) {
            state = state.copy(
                operations = state.operations.filterNot { it.sessionId == operation.sessionId },
                deletedSessions = state.deletedSessions + (operation.sessionId to ""),
            )
        }
        val updated = state.operations + operation
        val dropped = 0
        state = state.copy(
            operations = updated,
            droppedTelemetryCount = state.droppedTelemetryCount + dropped,
        )
        return EnqueueResult(dropped)
    }
    override fun peek() = state.operations.firstOrNull()
    override fun removeFirst() { if (state.operations.isNotEmpty()) state = state.copy(operations = state.operations.drop(1)) }
    override fun removeOperation(operationId: String): Boolean {
        val filtered = state.operations.filterNot { it.operationId == operationId }
        if (filtered.size == state.operations.size) return false
        state = state.copy(operations = filtered)
        return true
    }
    override fun recordOperationFailure(operationId: String, message: String, permanent: Boolean) {
        val index = state.operations.indexOfFirst { it.operationId == operationId }
        if (index < 0) return
        val operation = state.operations[index]
        state = state.copy(operations = state.operations.toMutableList().also {
            it[index] = operation.copy(
                retryCount = minOf(operation.retryCount + 1, CloudOutboxDrainer.MAX_RETRY_ATTEMPTS),
                lastError = message,
                deliveryState = if (permanent) DeliveryState.PERMANENT_FAILURE else DeliveryState.PENDING,
            )
        })
    }
    override fun pendingOperations() = state.operations.toList()
    override fun activeSession() = state.activeSession
    override fun saveActiveSession(session: ActiveCloudSession?) { state = state.copy(activeSession = session) }
    override fun completeSessionEnd(operationId: String, sessionId: String) {
        state = state.copy(
            operations = state.operations.filterNot { it.operationId == operationId },
            activeSession = state.activeSession?.takeUnless { it.sessionId == sessionId },
        )
    }
    override fun removeOperationsForSession(sessionId: String): Int {
        val before = state.operations.size
        state = state.copy(operations = state.operations.filterNot { it.sessionId == sessionId })
        return before - state.operations.size
    }
    override fun completeDeletion(operationId: String, sessionId: String, ownerUid: String) {
        state = state.copy(
            operations = state.operations.filterNot { it.sessionId == sessionId },
            deletedSessions = state.deletedSessions + (sessionId to ownerUid),
        )
    }
    override fun purgeUnconsentedUploads(): Int {
        val (erasureOps, uploadOps) = state.operations.partition { it.type == PendingOperationType.DELETE_SESSION }
        if (uploadOps.isNotEmpty()) {
            state = state.copy(operations = erasureOps)
        }
        return uploadOps.size
    }
}
