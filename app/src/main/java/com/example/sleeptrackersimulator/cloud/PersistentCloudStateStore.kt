package com.example.sleeptrackersimulator.cloud

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface CloudStateStore {
    val pendingCount: Int
    fun enqueue(operation: PendingCloudOperation)
    fun peek(): PendingCloudOperation?
    fun removeFirst()
    fun pendingOperations(): List<PendingCloudOperation>
    fun activeSession(): ActiveCloudSession?
    fun saveActiveSession(session: ActiveCloudSession?)
}

data class PersistedCloudState(
    val operations: List<PendingCloudOperation> = emptyList(),
    val activeSession: ActiveCloudSession? = null,
)

class AtomicJsonCloudStateStore(
    private val file: File,
    private val capacity: Int = CLOUD_PENDING_QUEUE_CAPACITY,
    private val gson: Gson = Gson(),
) : CloudStateStore {
    private var state = readSafely()

    init { require(capacity > 0) }

    @get:Synchronized override val pendingCount: Int get() = state.operations.size

    @Synchronized override fun enqueue(operation: PendingCloudOperation) {
        val updated = (state.operations + operation).takeLast(capacity)
        persist(state.copy(operations = updated))
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
    } catch (_: Exception) {
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
    override fun enqueue(operation: PendingCloudOperation) {
        state = state.copy(operations = (state.operations + operation).takeLast(capacity))
    }
    override fun peek() = state.operations.firstOrNull()
    override fun removeFirst() { if (state.operations.isNotEmpty()) state = state.copy(operations = state.operations.drop(1)) }
    override fun pendingOperations() = state.operations.toList()
    override fun activeSession() = state.activeSession
    override fun saveActiveSession(session: ActiveCloudSession?) { state = state.copy(activeSession = session) }
}
