package com.example.sleeptrackersimulator.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCloudStateStoreTest {
    private lateinit var database: SleepTrackerDatabase
    private lateinit var store: RoomCloudStateStore

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SleepTrackerDatabase::class.java,
        ).build()
        store = RoomCloudStateStore(database.cloudOutboxDao(), maximumOperations = 4)
    }

    @After fun closeDatabase() = database.close()

    @Test
    fun fifoSessionEndAndStorageLimitAreTransactional() = runBlocking(Dispatchers.IO) {
        store.saveActiveSession(ActiveCloudSession("s", 1, "uid", monitoringActive = false))
        store.enqueue(upload("m1", 1))
        store.enqueue(upload("m2", 2))
        store.enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 3, createdAt = 3))

        assertEquals(listOf("m1", "m2", "end-s"), store.pendingOperations().map { it.operationId })
        assertThrows(CloudOutboxFullException::class.java) { store.enqueue(upload("m3", 4)) }
        assertEquals(3, store.pendingCount)

        store.removeFirst()
        store.removeFirst()
        store.completeSessionEnd("s")
        assertEquals(0, store.pendingCount)
        assertNull(store.activeSession())
    }

    @Test
    fun fullTelemetryQueueStillAcceptsOneTerminalSessionOperation() = runBlocking(Dispatchers.IO) {
        val smallStore = RoomCloudStateStore(database.cloudOutboxDao(), maximumOperations = 4)
        smallStore.enqueue(upload("m1", 1))
        smallStore.enqueue(upload("m2", 2))
        assertThrows(CloudOutboxFullException::class.java) { smallStore.enqueue(upload("m3", 3)) }
        smallStore.enqueue(ruleEvent("rule-e1", 3))
        smallStore.enqueue(PendingCloudOperation("end-s", PendingOperationType.END_SESSION, "s", endedAt = 4, createdAt = 4))

        assertEquals(listOf("m1", "m2", "rule-e1", "end-s"), smallStore.pendingOperations().map { it.operationId })
        assertThrows(CloudOutboxFullException::class.java) {
            smallStore.enqueue(PendingCloudOperation("end-s-2", PendingOperationType.END_SESSION, "s", endedAt = 5, createdAt = 5))
        }
        Unit
    }

    private fun upload(id: String, timestamp: Long) = PendingCloudOperation(
        operationId = id,
        type = PendingOperationType.UPLOAD_MEASUREMENT,
        sessionId = "s",
        payload = SensorPayload(id, CLOUD_DEVICE_ID, "ACCELEROMETER", timestamp = timestamp, movementState = "STILL", restState = "MONITORING"),
        createdAt = timestamp,
    )

    private fun ruleEvent(id: String, timestamp: Long) = PendingCloudOperation(
        operationId = id,
        type = PendingOperationType.UPLOAD_RULE_EVENT,
        sessionId = "s",
        ruleEvent = SmartRuleEventPayload(eventId = id.removePrefix("rule-"), timestamp = timestamp),
        createdAt = timestamp,
    )
}
