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

    @Test fun `legacy capacity never truncates durable FIFO`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        AtomicJsonCloudStateStore(file, 2).apply { enqueue(upload("m-1", 1)); enqueue(upload("m-2", 2)); enqueue(upload("m-3", 3)) }
        val restored = AtomicJsonCloudStateStore(file, 2)
        assertEquals(listOf("m-1", "m-2", "m-3"), restored.pendingOperations().map { it.operationId })
        assertEquals(0L, restored.droppedTelemetryCount)
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
        assertNotNull(store.loadError)
        assertTrue(file.parentFile.resolve("state.json.corrupt").exists())
    }

    @Test fun `legacy capacity preserves telemetry and end session control operation`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        val store = AtomicJsonCloudStateStore(file, 2)
        store.enqueue(upload("m-1", 1))
        store.enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 2))
        store.enqueue(upload("m-2", 2))
        store.enqueue(upload("m-3", 3))
        assertEquals(
            listOf("m-1", "end-s", "m-2", "m-3"),
            store.pendingOperations().map { it.operationId },
        )
        assertEquals(0L, store.droppedTelemetryCount)
    }

    @Test fun `one hundred telemetry operations remain durable with end operation`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        val store = AtomicJsonCloudStateStore(file, 50)
        repeat(49) { store.enqueue(upload("m-$it", it.toLong() + 1)) }
        store.enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 50))
        repeat(51) { store.enqueue(upload("late-$it", it.toLong() + 51)) }
        assertEquals(101, store.pendingCount)
        assertEquals(0L, store.droppedTelemetryCount)
        assertTrue(store.pendingOperations().any { it.type == PendingOperationType.END_SESSION })
    }

    @Test fun `failed persistence does not mutate in memory queue`() {
        val invalidParent = Files.createTempFile("cloud-store-parent", ".tmp").toFile()
        val store = AtomicJsonCloudStateStore(invalidParent.resolve("state.json"))
        assertThrows(Exception::class.java) { store.enqueue(upload("m-1", 1)) }
        assertEquals(0, store.pendingCount)
    }

    @Test fun `completing end session atomically removes operation and active session`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        AtomicJsonCloudStateStore(file).apply {
            saveActiveSession(ActiveCloudSession("s", 1, "uid", monitoringActive = false))
            enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 2))
            completeSessionEnd("s")
        }
        val restored = AtomicJsonCloudStateStore(file)
        assertNull(restored.activeSession())
        assertTrue(restored.pendingOperations().isEmpty())
    }

    @Test fun `legacy json without new counters remains readable`() {
        val file = Files.createTempDirectory("cloud-store").resolve("state.json").toFile()
        file.writeText("""{"operations":[],"activeSession":null}""")
        val store = AtomicJsonCloudStateStore(file)
        assertEquals(0, store.pendingCount)
        assertEquals(0L, store.droppedTelemetryCount)
        assertNull(store.loadError)
    }

    private fun upload(id: String, timestamp: Long) = PendingCloudOperation(id, PendingOperationType.UPLOAD_MEASUREMENT, "s", SensorPayload(id, CLOUD_DEVICE_ID, "ACCELEROMETER", timestamp = timestamp, movementState = "STILL", restState = "MONITORING"))
}
