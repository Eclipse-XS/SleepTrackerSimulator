package com.example.sleeptrackersimulator.cloud

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.google.gson.Gson
import java.io.File

@Entity(
    tableName = "cloud_outbox",
    indices = [Index(value = ["operationId"], unique = true), Index(value = ["sessionId", "sequence"])],
)
data class CloudOutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String,
    val type: String,
    val sessionId: String,
    val payloadJson: String?,
    val endedAt: Long?,
    val createdAt: Long,
    val retryCount: Int,
    val lastError: String?,
    val deliveryState: String,
    val readyForDelivery: Boolean = true,
)

@Entity(tableName = "cloud_delivery_intents", indices = [Index(value = ["operationId"], unique = true)])
data class CloudDeliveryIntentEntity(
    @PrimaryKey val operationId: String,
    val type: String,
    val sessionId: String,
    val payloadJson: String?,
    val endedAt: Long?,
    val createdAt: Long,
)

@Entity(tableName = "cloud_session_deletions")
data class CloudSessionDeletionEntity(
    @PrimaryKey val sessionId: String,
    val ownerUid: String,
    val requestedAt: Long,
    val completedAt: Long?,
)

@Entity(tableName = "cloud_active_session")
data class CloudSessionEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val sessionId: String,
    val startedAt: Long,
    val ownerUid: String,
    val monitoringActive: Boolean,
) {
    companion object { const val SINGLETON_ID = 1 }
}

class CloudOutboxFullException(message: String) : IllegalStateException(message)

@Dao
abstract class CloudOutboxDao {
    @Query("SELECT COUNT(*) FROM cloud_outbox")
    abstract fun count(): Int

    @Query("SELECT COUNT(*) FROM cloud_outbox WHERE type = :type AND sessionId = :sessionId")
    protected abstract fun countTypeForSession(type: String, sessionId: String): Int

    @Query("SELECT COUNT(*) FROM cloud_session_deletions WHERE sessionId = :sessionId")
    protected abstract fun hasDeletion(sessionId: String): Int

    @Query("SELECT * FROM cloud_outbox ORDER BY sequence ASC LIMIT 1")
    abstract fun first(): CloudOutboxEntity?

    @Query("SELECT * FROM cloud_outbox WHERE type = 'DELETE_SESSION' ORDER BY sequence ASC LIMIT 1")
    abstract fun firstDeletion(): CloudOutboxEntity?

    @Query("SELECT * FROM cloud_outbox ORDER BY sequence ASC")
    abstract fun all(): List<CloudOutboxEntity>

    @Query("SELECT * FROM cloud_active_session WHERE singletonId = 1")
    abstract fun activeSession(): CloudSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insert(entity: CloudOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertDeletion(entity: CloudSessionDeletionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertIntent(entity: CloudDeliveryIntentEntity): Long

    @Query("SELECT * FROM cloud_delivery_intents ORDER BY createdAt ASC LIMIT 1")
    protected abstract fun firstIntent(): CloudDeliveryIntentEntity?

    @Query("DELETE FROM cloud_delivery_intents WHERE operationId = :operationId")
    protected abstract fun deleteIntent(operationId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertForMigration(entity: CloudOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveSession(entity: CloudSessionEntity)

    @Query("DELETE FROM cloud_active_session")
    abstract fun clearSession()

    @Query("DELETE FROM cloud_outbox WHERE sequence = :sequence")
    protected abstract fun deleteSequence(sequence: Long): Int

    @Query("DELETE FROM cloud_outbox WHERE operationId = :operationId")
    abstract fun deleteOperation(operationId: String): Int

    @Query("DELETE FROM cloud_active_session WHERE sessionId = :sessionId")
    protected abstract fun deleteSession(sessionId: String): Int

    @Query("DELETE FROM cloud_outbox WHERE sessionId = :sessionId")
    abstract fun deleteForSession(sessionId: String): Int

    @Query("DELETE FROM cloud_delivery_intents WHERE sessionId = :sessionId")
    protected abstract fun deleteIntentsForSession(sessionId: String): Int

    @Query("DELETE FROM cloud_outbox WHERE type != 'DELETE_SESSION'")
    abstract fun purgeUnconsentedUploads(): Int

    @Query("UPDATE cloud_outbox SET retryCount = MIN(retryCount + 1, 5), lastError = :message, deliveryState = :state WHERE sequence = :sequence")
    protected abstract fun updateFailure(sequence: Long, message: String, state: String): Int

    @Query("UPDATE cloud_outbox SET retryCount = MIN(retryCount + 1, 5), lastError = :message, deliveryState = :state WHERE operationId = :operationId")
    protected abstract fun updateOperationFailure(operationId: String, message: String, state: String): Int

    @Transaction
    open fun enqueue(entity: CloudOutboxEntity, maximumOperations: Int) {
        if (entity.type == PendingOperationType.DELETE_SESSION.name) {
            deleteForSession(entity.sessionId)
            deleteIntentsForSession(entity.sessionId)
            insertDeletion(CloudSessionDeletionEntity(entity.sessionId, "", entity.createdAt, null))
            insert(entity)
            return
        } else if (hasDeletion(entity.sessionId) > 0) {
            return
        }
        val currentCount = count()
        val limit = when (entity.type) {
            PendingOperationType.UPLOAD_MEASUREMENT.name -> maximumOperations - RoomCloudStateStore.RESERVED_CRITICAL_SLOTS
            PendingOperationType.UPLOAD_RULE_EVENT.name -> maximumOperations - RoomCloudStateStore.RESERVED_END_SESSION_SLOTS
            PendingOperationType.UPLOAD_SESSION_SUMMARY.name -> maximumOperations
            PendingOperationType.END_SESSION.name -> maximumOperations
            else -> maximumOperations
        }
        val duplicateEnd = entity.type == PendingOperationType.END_SESSION.name &&
            countTypeForSession(PendingOperationType.END_SESSION.name, entity.sessionId) > 0
        if (duplicateEnd || currentCount >= limit) {
            throw CloudOutboxFullException(
                "Cloud outbox capacity policy rejected ${entity.type} at $currentCount/$maximumOperations",
            )
        }
        insert(entity)
    }

    @Transaction
    open fun saveDeliveryIntent(entity: CloudOutboxEntity) {
        if (hasDeletion(entity.sessionId) > 0) return
        insertIntent(entity.toDeliveryIntent())
    }

    @Transaction
    open fun reconcileDeliveryIntents(maximumOperations: Int): Int {
        var moved = 0
        while (count() < maximumOperations) {
            val intent = firstIntent() ?: break
            if (hasDeletion(intent.sessionId) == 0) {
                try {
                    enqueue(intent.toOutbox(), maximumOperations)
                } catch (_: CloudOutboxFullException) {
                    break
                }
            }
            check(deleteIntent(intent.operationId) == 1)
            moved++
        }
        return moved
    }

    @Transaction
    open fun completeDeletion(operationId: String, sessionId: String, ownerUid: String, completedAt: Long) {
        deleteForSession(sessionId)
        deleteIntentsForSession(sessionId)
        insertDeletion(CloudSessionDeletionEntity(sessionId, ownerUid, completedAt, completedAt))
    }

    @Transaction
    open fun removeFirst() {
        val head = first() ?: return
        check(deleteSequence(head.sequence) == 1) { "Outbox head changed during acknowledgement" }
    }

    @Transaction
    open fun completeSessionEnd(operationId: String, sessionId: String) {
        deleteOperation(operationId)
        deleteSession(sessionId)
    }

    @Transaction
    open fun recordFirstFailure(message: String, permanent: Boolean) {
        val head = first() ?: return
        val state = if (permanent) DeliveryState.PERMANENT_FAILURE.name else DeliveryState.PENDING.name
        check(updateFailure(head.sequence, message, state) == 1)
    }

    @Transaction
    open fun recordOperationFailure(operationId: String, message: String, permanent: Boolean) {
        val state = if (permanent) DeliveryState.PERMANENT_FAILURE.name else DeliveryState.PENDING.name
        check(updateOperationFailure(operationId, message, state) == 1)
    }

    @Transaction
    open fun importLegacy(operations: List<CloudOutboxEntity>, session: CloudSessionEntity?) {
        operations.forEach(::insertForMigration)
        if (activeSession() == null && session != null) saveSession(session)
    }
}

class RoomCloudStateStore(
    private val dao: CloudOutboxDao,
    private val legacyFile: File? = null,
    private val maximumOperations: Int = DEFAULT_MAXIMUM_OPERATIONS,
    private val gson: Gson = Gson(),
) : CloudStateStore {
    @Volatile private var legacyChecked = false

    init { require(maximumOperations > 0) }

    override val pendingCount: Int get() { ensureLegacyImported(); return dao.count() }
    override val droppedTelemetryCount: Long get() = 0
    override val loadError: String? get() = null

    override fun enqueue(operation: PendingCloudOperation): EnqueueResult {
        ensureLegacyImported()
        dao.enqueue(operation.toEntity(), maximumOperations)
        return EnqueueResult()
    }

    override fun peek(): PendingCloudOperation? {
        ensureLegacyImported()
        return dao.first()?.takeIf { it.readyForDelivery }?.toModel()
    }

    override fun removeFirst() = dao.removeFirst()
    override fun peekDeletion(): PendingCloudOperation? {
        ensureLegacyImported()
        return dao.firstDeletion()?.takeIf { it.readyForDelivery }?.toModel()
    }
    override fun removeOperation(operationId: String): Boolean = dao.deleteOperation(operationId) == 1
    override fun pendingOperations(): List<PendingCloudOperation> {
        ensureLegacyImported()
        return dao.all().map { it.toModel() }
    }
    override fun activeSession(): ActiveCloudSession? {
        ensureLegacyImported()
        return dao.activeSession()?.toModel()
    }
    override fun saveActiveSession(session: ActiveCloudSession?) {
        ensureLegacyImported()
        if (session == null) dao.clearSession() else dao.saveSession(session.toEntity())
    }
    override fun completeSessionEnd(operationId: String, sessionId: String) = dao.completeSessionEnd(operationId, sessionId)
    override fun removeOperationsForSession(sessionId: String): Int {
        ensureLegacyImported()
        return dao.deleteForSession(sessionId)
    }
    override fun purgeUnconsentedUploads(): Int {
        ensureLegacyImported()
        return dao.purgeUnconsentedUploads()
    }
    override fun recordFirstFailure(message: String, permanent: Boolean) =
        dao.recordFirstFailure(message, permanent)
    override fun recordOperationFailure(operationId: String, message: String, permanent: Boolean) =
        dao.recordOperationFailure(operationId, message, permanent)
    override fun reconcileDeliveryIntents(): Int = dao.reconcileDeliveryIntents(maximumOperations)
    override fun completeDeletion(operationId: String, sessionId: String, ownerUid: String) =
        dao.completeDeletion(operationId, sessionId, ownerUid, System.currentTimeMillis())

    @Synchronized
    private fun ensureLegacyImported() {
        if (legacyChecked) return
        val source = legacyFile
        if (source != null && source.exists()) {
            val persisted = try {
                gson.fromJson(source.readText(), PersistedCloudState::class.java)
                    ?: throw IllegalStateException("Legacy cloud state is empty")
            } catch (error: Exception) {
                throw IllegalStateException("Legacy cloud state is unreadable and was preserved", error)
            }
            dao.importLegacy(
                persisted.operations.map { it.toEntity(gson) },
                persisted.activeSession?.toEntity(),
            )
            val migrated = source.resolveSibling("${source.name}.migrated")
            if (!source.renameTo(migrated)) {
                throw IllegalStateException("Legacy cloud state was imported but could not be archived")
            }
        }
        legacyChecked = true
    }

    companion object {
        const val DEFAULT_MAXIMUM_OPERATIONS = 10_000
        const val RESERVED_CRITICAL_SLOTS = 2
        const val RESERVED_END_SESSION_SLOTS = 1
    }
}

fun PendingCloudOperation.toEntity(gson: Gson = Gson()): CloudOutboxEntity = CloudOutboxEntity(
    operationId = operationId,
    type = type.name,
    sessionId = sessionId,
    payloadJson = when (type) {
        PendingOperationType.UPLOAD_MEASUREMENT -> payload?.let(gson::toJson)
        PendingOperationType.UPLOAD_RULE_EVENT -> ruleEvent?.let(gson::toJson)
        PendingOperationType.UPLOAD_SESSION_SUMMARY -> summary?.let(gson::toJson)
        PendingOperationType.END_SESSION,
        PendingOperationType.DELETE_SESSION -> null
    },
    endedAt = endedAt,
    createdAt = createdAt,
    retryCount = retryCount,
    lastError = lastError,
    deliveryState = deliveryState.name,
    readyForDelivery = readyForDelivery,
)

fun CloudOutboxEntity.toModel(gson: Gson = Gson()): PendingCloudOperation = PendingCloudOperation(
    operationId = operationId,
    type = PendingOperationType.valueOf(type),
    sessionId = sessionId,
    payload = payloadJson?.takeIf { type == PendingOperationType.UPLOAD_MEASUREMENT.name }?.let { gson.fromJson(it, SensorPayload::class.java) },
    ruleEvent = payloadJson?.takeIf { type == PendingOperationType.UPLOAD_RULE_EVENT.name }?.let { gson.fromJson(it, SmartRuleEventPayload::class.java) },
    summary = payloadJson?.takeIf { type == PendingOperationType.UPLOAD_SESSION_SUMMARY.name }?.let { gson.fromJson(it, SessionSummaryPayload::class.java) },
    endedAt = endedAt,
    createdAt = createdAt,
    retryCount = retryCount,
    lastError = lastError,
    deliveryState = DeliveryState.valueOf(deliveryState),
    readyForDelivery = readyForDelivery,
)

private fun CloudOutboxEntity.toDeliveryIntent() = CloudDeliveryIntentEntity(
    operationId, type, sessionId, payloadJson, endedAt, createdAt,
)

private fun CloudDeliveryIntentEntity.toOutbox() = CloudOutboxEntity(
    operationId = operationId,
    type = type,
    sessionId = sessionId,
    payloadJson = payloadJson,
    endedAt = endedAt,
    createdAt = createdAt,
    retryCount = 0,
    lastError = null,
    deliveryState = DeliveryState.PENDING.name,
)

fun ActiveCloudSession.toEntity(): CloudSessionEntity = CloudSessionEntity(
    sessionId = sessionId,
    startedAt = startedAt,
    ownerUid = ownerUid,
    monitoringActive = monitoringActive,
)

fun CloudSessionEntity.toModel(): ActiveCloudSession = ActiveCloudSession(
    sessionId = sessionId,
    startedAt = startedAt,
    ownerUid = ownerUid,
    monitoringActive = monitoringActive,
)
