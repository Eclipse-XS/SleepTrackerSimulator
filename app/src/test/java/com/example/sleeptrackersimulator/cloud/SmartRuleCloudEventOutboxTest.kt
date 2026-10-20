package com.example.sleeptrackersimulator.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartRuleCloudEventOutboxTest {
    @Test fun `stable rule event id is queued and recovery is scheduled`() {
        val store = FakeStore(ActiveCloudSession("session-1", 1, "uid"))
        var schedules = 0
        val sink = OutboxSmartRuleCloudEventSink(store) { schedules++ }
        val result = sink.enqueue(SmartRuleEventPayload(eventId = "event-1", timestamp = 2))
        assertEquals("PENDING", result.status)
        assertEquals("session-1", result.sessionId)
        assertEquals("rule-event-1", store.operations.single().operationId)
        assertEquals("event-1", store.operations.single().ruleEvent?.eventId)
        assertEquals(1, schedules)
    }

    @Test fun `event is not queued without active session`() {
        val store = FakeStore(null)
        val result = OutboxSmartRuleCloudEventSink(store) { error("must not schedule") }
            .enqueue(SmartRuleEventPayload(eventId = "event-1", timestamp = 2))
        assertEquals("NO_ACTIVE_SESSION", result.status)
        assertNull(result.sessionId)
        assertEquals(emptyList<PendingCloudOperation>(), store.operations)
    }

    private class FakeStore(private val session: ActiveCloudSession?) : CloudStateStore {
        val operations = mutableListOf<PendingCloudOperation>()
        override val pendingCount get() = operations.size
        override val droppedTelemetryCount = 0L
        override val loadError: String? = null
        override fun enqueue(operation: PendingCloudOperation) = EnqueueResult().also { operations += operation }
        override fun peek() = operations.firstOrNull()
        override fun removeFirst() { if (operations.isNotEmpty()) operations.removeAt(0) }
        override fun pendingOperations() = operations.toList()
        override fun activeSession() = session
        override fun saveActiveSession(session: ActiveCloudSession?) = Unit
        override fun completeSessionEnd(sessionId: String) = Unit
    }
}
