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
    fun pendingOperations(): List<PendingCloudOperation>
    fun activeSession(): ActiveCloudSession?
    fun saveActiveSession(session: ActiveCloudSession?)
}

data class PersistedCloudState(
    val operations: List<PendingCloudOperation> = emptyList(),
    val activeSession: ActiveCloudSession? = null,
    val droppedTelemetryCount: Long = 0,
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
        val uploads = state.operations.count { it.type == PendingOperationType.UPLOAD_MEASUREMENT }
        val shouldDropUpload = operation.type == PendingOperationType.UPLOAD_MEASUREMENT && uploads >= capacity
        val updated = if (shouldDropUpload) {
            val dropIndex = state.operations.indexOfFirst { it.type == PendingOperationType.UPLOAD_MEASUREMENT }
            state.operations.filterIndexed { index, _ -> index != dropIndex } + operation
        } else {
            state.operations + operation
        }
        val dropped = if (shouldDropUpload) 1 else 0
        persist(
            state.copy(
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

    @Synchronized override fun pendingOperations(): List<PendingCloudOperation> = state.operations.toList()

    @Synchronized override fun activeSession(): ActiveCloudSession? = state.activeSession

    @Synchronized override fun saveActiveSession(session: ActiveCloudSession?) {
        persist(state.copy(activeSession = session))
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
        val uploads = state.operations.count { it.type == PendingOperationType.UPLOAD_MEASUREMENT }
        val shouldDropUpload = operation.type == PendingOperationType.UPLOAD_MEASUREMENT && uploads >= capacity
        val updated = if (shouldDropUpload) {
            val dropIndex = state.operations.indexOfFirst { it.type == PendingOperationType.UPLOAD_MEASUREMENT }
            state.operations.filterIndexed { index, _ -> index != dropIndex } + operation
        } else state.operations + operation
        val dropped = if (shouldDropUpload) 1 else 0
        state = state.copy(
            operations = updated,
            droppedTelemetryCount = state.droppedTelemetryCount + dropped,
        )
        return EnqueueResult(dropped)
    }
    override fun peek() = state.operations.firstOrNull()
    override fun removeFirst() { if (state.operations.isNotEmpty()) state = state.copy(operations = state.operations.drop(1)) }
    override fun pendingOperations() = state.operations.toList()
    override fun activeSession() = state.activeSession
    override fun saveActiveSession(session: ActiveCloudSession?) { state = state.copy(activeSession = session) }
}
