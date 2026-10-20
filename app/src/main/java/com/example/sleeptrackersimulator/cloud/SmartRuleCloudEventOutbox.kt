package com.example.sleeptrackersimulator.cloud

data class RuleEventEnqueueResult(val sessionId: String?, val status: String, val error: String? = null)

fun interface SmartRuleCloudStatusUpdater {
    suspend fun markSynced(eventId: String)
}

object NoOpSmartRuleCloudStatusUpdater : SmartRuleCloudStatusUpdater {
    override suspend fun markSynced(eventId: String) = Unit
}

interface SmartRuleCloudEventSink {
    fun activeSessionId(): String?
    fun enqueue(payload: SmartRuleEventPayload): RuleEventEnqueueResult
}

object NoOpSmartRuleCloudEventSink : SmartRuleCloudEventSink {
    override fun activeSessionId(): String? = null
    override fun enqueue(payload: SmartRuleEventPayload) = RuleEventEnqueueResult(null, "NO_ACTIVE_SESSION")
}

class OutboxSmartRuleCloudEventSink(
    private val store: CloudStateStore,
    private val scheduler: CloudRecoveryScheduler,
) : SmartRuleCloudEventSink {
    override fun activeSessionId(): String? = store.activeSession()?.sessionId

    override fun enqueue(payload: SmartRuleEventPayload): RuleEventEnqueueResult {
        val sessionId = activeSessionId() ?: return RuleEventEnqueueResult(null, "NO_ACTIVE_SESSION")
        return runCatching {
            store.enqueue(
                PendingCloudOperation(
                    operationId = "rule-${payload.eventId}",
                    type = PendingOperationType.UPLOAD_RULE_EVENT,
                    sessionId = sessionId,
                    ruleEvent = payload,
                    createdAt = payload.timestamp,
                ),
            )
            scheduler.schedule()
            RuleEventEnqueueResult(sessionId, "PENDING")
        }.getOrElse { RuleEventEnqueueResult(sessionId, "ENQUEUE_FAILED", it.message) }
    }
}
