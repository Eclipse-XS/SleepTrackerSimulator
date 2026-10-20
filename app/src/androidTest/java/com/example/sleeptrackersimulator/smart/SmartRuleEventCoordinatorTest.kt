package com.example.sleeptrackersimulator.smart

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sleeptrackersimulator.cloud.ActiveCloudSession
import com.example.sleeptrackersimulator.cloud.CloudOutboxFullException
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartRuleEventCoordinatorTest {
    private lateinit var database: SleepTrackerDatabase
    private lateinit var store: RoomCloudStateStore

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), SleepTrackerDatabase::class.java,
        ).build()
        store = RoomCloudStateStore(database.cloudOutboxDao())
    }

    @After fun close() = database.close()

    @Test fun prepareAndGatedOutboxAreAtomicThenFinalizedTogether() = runBlocking {
        store.saveActiveSession(ActiveCloudSession("s1", 1, "uid"))
        val coordinator = RoomSmartRuleEventCoordinator(database.smartRuleEventCoordinatorDao())
        val prepared = coordinator.prepare(execution("e1"), payload("e1", "PENDING"))
        assertTrue(prepared.outboxCreated)
        assertEquals("PREPARING", prepared.execution.cloudStatus)
        assertFalse(store.pendingOperations().single().readyForDelivery)
        assertEquals(null, store.peek())

        coordinator.markActionInProgress("e1")
        val finalized = coordinator.finalizeEvent("e1", "SUCCESS", null, "PULSE_OK", "NOT_REQUESTED", payload("e1", "PULSE_OK"))
        assertEquals("PENDING", finalized.cloudStatus)
        assertTrue(store.pendingOperations().single().readyForDelivery)
        assertEquals("e1", store.peek()?.ruleEvent?.eventId)
    }

    @Test fun capacityFailureRollsBackExecutionAndOutbox() = runBlocking {
        store.saveActiveSession(ActiveCloudSession("s1", 1, "uid"))
        val coordinator = RoomSmartRuleEventCoordinator(database.smartRuleEventCoordinatorDao(), maximumOperations = 1)
        assertThrows(CloudOutboxFullException::class.java) {
            runBlocking { coordinator.prepare(execution("e1"), payload("e1", "PENDING")) }
        }
        assertEquals(emptyList<SmartRuleExecutionEntity>(), database.smartRuleDao().observeRecentExecutions(10).first())
        assertEquals(0, store.pendingCount)
    }

    @Test fun restartReconcilesGatedIntentWithoutReplayingPhysicalAction() = runBlocking {
        store.saveActiveSession(ActiveCloudSession("s1", 1, "uid"))
        val coordinator = RoomSmartRuleEventCoordinator(database.smartRuleEventCoordinatorDao())
        coordinator.prepare(execution("e1"), payload("e1", "PENDING"))

        val report = coordinator.reconcile(createdBefore = 11)
        assertEquals(1, report.recovered)
        val recovered = database.smartRuleDao().observeRecentExecutions(10).first().single()
        assertEquals("OUTCOME_UNKNOWN", recovered.result)
        assertEquals("OUTCOME_UNKNOWN", recovered.actuatorStatus)
        assertTrue(store.pendingOperations().single().readyForDelivery)
    }

    private fun execution(id: String) = SmartRuleExecutionEntity(
        executionId = id, ruleId = "r1", timestamp = 10, sourceValue = "1.0",
        action = RuleAction.VIBRATION_PULSE.name, result = "PENDING", failureReason = null,
        ruleNameSnapshot = "Rule 1", sensorTimestampNanos = 9,
        triggerSource = RuleSource.MOVEMENT_INTENSITY.name,
    )

    private fun payload(id: String, actuator: String) = SmartRuleEventPayload(
        eventId = id, ruleId = "r1", ruleName = "Rule 1", timestamp = 10,
        source = RuleSource.MOVEMENT_INTENSITY.name, sourceValue = "1.0",
        sensorTimestampNanos = 9, requestedAction = RuleAction.VIBRATION_PULSE.name,
        actuatorResult = actuator, bleDeliveryResult = "NOT_REQUESTED",
    )
}
