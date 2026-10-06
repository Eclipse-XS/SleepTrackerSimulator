package com.example.sleeptrackersimulator.cloud

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AtomicJsonCloudStateStoreTest {
    @Test fun `operations and active session survive re-instantiation`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        val first = AtomicJsonCloudStateStore(file, 3)
        first.saveActiveSession(ActiveCloudSession("session-1", 10, "uid-1"))
        first.enqueue(upload("m-1", 1)); first.enqueue(upload("m-2", 2))
        val second = AtomicJsonCloudStateStore(file, 3)
        assertEquals("session-1", second.activeSession()?.sessionId)
        assertEquals(listOf("m-1", "m-2"), second.pendingOperations().map { it.operationId })
        assertEquals("m-1", second.pendingOperations()[0].payload?.measurementId)
    }

    @Test fun `bounded FIFO and overflow survive persistence`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        AtomicJsonCloudStateStore(file, 2).apply { enqueue(upload("m-1", 1)); enqueue(upload("m-2", 2)); enqueue(upload("m-3", 3)) }
        val restored = AtomicJsonCloudStateStore(file, 2)
        assertEquals(listOf("m-2", "m-3"), restored.pendingOperations().map { it.operationId })
    }

    @Test fun `end session and mixed operation order persist`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        AtomicJsonCloudStateStore(file).apply {
            enqueue(upload("m-1", 1)); enqueue(upload("m-2", 2))
            enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3))
        }
        assertEquals(listOf(PendingOperationType.UPLOAD_MEASUREMENT, PendingOperationType.UPLOAD_MEASUREMENT, PendingOperationType.END_SESSION), AtomicJsonCloudStateStore(file).pendingOperations().map { it.type })
    }

    @Test fun `corrupted storage is treated as empty`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile().apply { writeText("{broken") }
        val store = AtomicJsonCloudStateStore(file)
        assertEquals(0, store.pendingCount); assertNull(store.activeSession())
    }

    private fun upload(id: String, timestamp: Long) = PendingCloudOperation(id, PendingOperationType.UPLOAD_MEASUREMENT, "s", SensorPayload(id, CLOUD_DEVICE_ID, "ACCELEROMETER", timestamp = timestamp, movementState = "STILL", restState = "MONITORING"))
}
