package com.example.sleeptrackersimulator.smart

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.sleeptrackersimulator.cloud.CloudOutboxEntity
import com.example.sleeptrackersimulator.cloud.CloudOutboxFullException
import com.example.sleeptrackersimulator.cloud.CloudSessionEntity
import com.example.sleeptrackersimulator.cloud.DeliveryState
import com.example.sleeptrackersimulator.cloud.PendingOperationType
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RuleEventPreparation(val execution: SmartRuleExecutionEntity, val outboxCreated: Boolean)
data class RuleEventFinalization(val cloudStatus: String)
data class RuleEventRecoveryReport(val recovered: Int, val deferred: Int, val legacyUnrecoverable: Int)

@Dao
abstract class SmartRuleEventCoordinatorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertExecution(entity: SmartRuleExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertOutbox(entity: CloudOutboxEntity)

    @Query("SELECT * FROM cloud_active_session WHERE singletonId = 1")
    protected abstract fun activeSession(): CloudSessionEntity?

    @Query("SELECT COUNT(*) FROM cloud_outbox")
    protected abstract fun outboxCount(): Int

    @Query("SELECT COUNT(*) FROM cloud_outbox WHERE operationId = :operationId")
    protected abstract fun outboxCount(operationId: String): Int

    @Query("""UPDATE smart_rule_executions SET result = 'ACTION_IN_PROGRESS'
        WHERE executionId = :executionId AND result = 'PENDING'""")
    abstract fun markActionInProgress(executionId: String): Int

    @Query("""UPDATE smart_rule_executions SET result = :result, failureReason = :failureReason,
        actuatorStatus = :actuatorStatus, bleStatus = :bleStatus, cloudStatus = :cloudStatus
        WHERE executionId = :executionId""")
    protected abstract fun updateExecution(
        executionId: String,
        result: String,
        failureReason: String?,
        actuatorStatus: String,
        bleStatus: String,
        cloudStatus: String,
    ): Int

    @Query("""UPDATE cloud_outbox SET payloadJson = :payloadJson, readyForDelivery = 1
        WHERE operationId = :operationId AND type = 'UPLOAD_RULE_EVENT'""")
    protected abstract fun finalizeOutbox(operationId: String, payloadJson: String): Int

    @Query("""SELECT * FROM smart_rule_executions
        WHERE timestamp < :createdBefore AND (
            result IN ('PENDING','ACTION_IN_PROGRESS') OR cloudStatus IN ('NOT_QUEUED','PREPARING')
        ) ORDER BY timestamp ASC""")
    abstract fun recoveryCandidates(createdBefore: Long): List<SmartRuleExecutionEntity>

    @Query("UPDATE smart_rule_executions SET cloudStatus = :status WHERE executionId = :executionId")
    protected abstract fun updateCloudStatus(executionId: String, status: String): Int

    @Transaction
    open fun prepare(
        execution: SmartRuleExecutionEntity,
        draftPayloadJson: String,
        maximumOperations: Int,
    ): RuleEventPreparation {
        val session = activeSession()
        val outboxCreated = session != null
        if (outboxCreated) requireCapacity(PendingOperationType.UPLOAD_RULE_EVENT, maximumOperations)
        val stored = execution.copy(
            sessionId = session?.sessionId,
            cloudStatus = if (outboxCreated) "PREPARING" else "NOT_QUEUED",
        )
        insertExecution(stored)
        if (session != null) {
            insertOutbox(ruleOutbox(stored, session.sessionId, draftPayloadJson, ready = false))
        }
        return RuleEventPreparation(stored, outboxCreated)
    }

    @Transaction
    open fun finalizeEvent(
        executionId: String,
        result: String,
        failureReason: String?,
        actuatorStatus: String,
        bleStatus: String,
        payloadJson: String,
    ): RuleEventFinalization {
        val operationId = operationId(executionId)
        val hasOutbox = outboxCount(operationId) == 1
        val cloudStatus = if (hasOutbox) "PENDING" else "NOT_QUEUED"
        check(updateExecution(executionId, result, failureReason, actuatorStatus, bleStatus, cloudStatus) == 1)
        if (hasOutbox) check(finalizeOutbox(operationId, payloadJson) == 1)
        return RuleEventFinalization(cloudStatus)
    }

    @Transaction
    open fun recover(
        execution: SmartRuleExecutionEntity,
        recoveredPayloadJson: String,
        maximumOperations: Int,
    ): String {
        if (execution.triggerSource.isBlank()) {
            check(updateCloudStatus(execution.executionId, "LEGACY_UNRECOVERABLE") == 1)
            return "LEGACY_UNRECOVERABLE"
        }
        val operationId = operationId(execution.executionId)
        val existingOutbox = outboxCount(operationId) == 1
        val session = activeSession()
        if (!existingOutbox && session == null) {
            val recoveredResult = if (execution.result in INCOMPLETE_RESULTS) "OUTCOME_UNKNOWN" else execution.result
            check(updateExecution(
                execution.executionId, recoveredResult, execution.failureReason,
                recoveredActuatorStatus(execution), recoveredBleStatus(execution), "NOT_QUEUED",
            ) == 1)
            return "DEFERRED_NO_SESSION"
        }
        if (!existingOutbox) {
            requireCapacity(PendingOperationType.UPLOAD_RULE_EVENT, maximumOperations)
            insertOutbox(ruleOutbox(execution, requireNotNull(session).sessionId, recoveredPayloadJson, ready = true))
        } else {
            check(finalizeOutbox(operationId, recoveredPayloadJson) == 1)
        }
        val recoveredResult = if (execution.result in INCOMPLETE_RESULTS) "OUTCOME_UNKNOWN" else execution.result
        check(updateExecution(
            execution.executionId, recoveredResult, execution.failureReason,
            recoveredActuatorStatus(execution), recoveredBleStatus(execution), "PENDING",
        ) == 1)
        return "RECOVERED"
    }

    private fun requireCapacity(type: PendingOperationType, maximumOperations: Int) {
        val current = outboxCount()
        val limit = when (type) {
            PendingOperationType.UPLOAD_MEASUREMENT -> maximumOperations - RoomCloudStateStore.RESERVED_CRITICAL_SLOTS
            PendingOperationType.UPLOAD_RULE_EVENT -> maximumOperations - RoomCloudStateStore.RESERVED_END_SESSION_SLOTS
            PendingOperationType.UPLOAD_SESSION_SUMMARY -> maximumOperations
            PendingOperationType.END_SESSION -> maximumOperations
        }
        if (current >= limit) throw CloudOutboxFullException(
            "Cloud outbox capacity policy rejected ${type.name} at $current/$maximumOperations",
        )
    }

    private fun ruleOutbox(
        execution: SmartRuleExecutionEntity,
        sessionId: String,
        payloadJson: String,
        ready: Boolean,
    ) = CloudOutboxEntity(
        operationId = operationId(execution.executionId),
        type = PendingOperationType.UPLOAD_RULE_EVENT.name,
        sessionId = sessionId,
        payloadJson = payloadJson,
        endedAt = null,
        createdAt = execution.timestamp,
        retryCount = 0,
        lastError = null,
        deliveryState = DeliveryState.PENDING.name,
        readyForDelivery = ready,
    )

    private fun recoveredActuatorStatus(execution: SmartRuleExecutionEntity) =
        if (execution.result in INCOMPLETE_RESULTS && execution.action in ACTUATOR_ACTIONS) "OUTCOME_UNKNOWN" else execution.actuatorStatus

    private fun recoveredBleStatus(execution: SmartRuleExecutionEntity) =
        if (execution.result in INCOMPLETE_RESULTS && execution.action in BLE_ACTIONS) "OUTCOME_UNKNOWN" else execution.bleStatus

    companion object {
        private val INCOMPLETE_RESULTS = setOf("PENDING", "ACTION_IN_PROGRESS")
        private val ACTUATOR_ACTIONS = setOf(RuleAction.VIBRATION_PULSE.name, RuleAction.CRITICAL_ALERT.name)
        private val BLE_ACTIONS = setOf(RuleAction.MOCK_BLE_ALERT.name, RuleAction.CRITICAL_ALERT.name)
        fun operationId(executionId: String) = "rule-$executionId"
    }
}

interface SmartRuleEventCoordinator {
    suspend fun prepare(execution: SmartRuleExecutionEntity, draftPayload: SmartRuleEventPayload): RuleEventPreparation
    suspend fun markActionInProgress(executionId: String)
    suspend fun finalizeEvent(
        executionId: String,
        result: String,
        failureReason: String?,
        actuatorStatus: String,
        bleStatus: String,
        finalPayload: SmartRuleEventPayload,
    ): RuleEventFinalization
    suspend fun reconcile(createdBefore: Long): RuleEventRecoveryReport
}

class RoomSmartRuleEventCoordinator(
    private val dao: SmartRuleEventCoordinatorDao,
    private val gson: Gson = Gson(),
    private val maximumOperations: Int = RoomCloudStateStore.DEFAULT_MAXIMUM_OPERATIONS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmartRuleEventCoordinator {
    override suspend fun prepare(execution: SmartRuleExecutionEntity, draftPayload: SmartRuleEventPayload) =
        withContext(dispatcher) { dao.prepare(execution, gson.toJson(draftPayload), maximumOperations) }

    override suspend fun markActionInProgress(executionId: String) = withContext(dispatcher) {
        check(dao.markActionInProgress(executionId) == 1) { "Execution $executionId is not pending" }
    }

    override suspend fun finalizeEvent(
        executionId: String,
        result: String,
        failureReason: String?,
        actuatorStatus: String,
        bleStatus: String,
        finalPayload: SmartRuleEventPayload,
    ) = withContext(dispatcher) {
        dao.finalizeEvent(executionId, result, failureReason, actuatorStatus, bleStatus, gson.toJson(finalPayload))
    }

    override suspend fun reconcile(createdBefore: Long): RuleEventRecoveryReport = withContext(dispatcher) {
        var recovered = 0
        var deferred = 0
        var legacy = 0
        dao.recoveryCandidates(createdBefore).forEach { execution ->
            val recoveredResult = if (execution.result in setOf("PENDING", "ACTION_IN_PROGRESS")) "OUTCOME_UNKNOWN" else execution.result
            val actuatorStatus = if (recoveredResult == "OUTCOME_UNKNOWN" && execution.action in setOf("VIBRATION_PULSE", "CRITICAL_ALERT")) "OUTCOME_UNKNOWN" else execution.actuatorStatus
            val bleStatus = if (recoveredResult == "OUTCOME_UNKNOWN" && execution.action in setOf("MOCK_BLE_ALERT", "CRITICAL_ALERT")) "OUTCOME_UNKNOWN" else execution.bleStatus
            val payload = execution.toPayload(actuatorStatus, bleStatus)
            when (dao.recover(execution, gson.toJson(payload), maximumOperations)) {
                "RECOVERED" -> recovered++
                "DEFERRED_NO_SESSION" -> deferred++
                else -> legacy++
            }
        }
        RuleEventRecoveryReport(recovered, deferred, legacy)
    }

    private fun SmartRuleExecutionEntity.toPayload(actuator: String, ble: String) = SmartRuleEventPayload(
        eventId = executionId,
        ruleId = ruleId,
        ruleName = ruleNameSnapshot,
        timestamp = timestamp,
        source = triggerSource,
        sourceValue = sourceValue,
        sensorTimestampNanos = sensorTimestampNanos,
        predictionId = predictionId,
        mlClass = mlClass,
        mlConfidence = mlConfidence,
        requestedAction = action,
        actuatorResult = actuator,
        bleDeliveryResult = ble,
    )
}
