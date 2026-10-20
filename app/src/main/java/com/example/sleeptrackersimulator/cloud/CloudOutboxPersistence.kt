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

    @Query("SELECT * FROM cloud_outbox ORDER BY sequence ASC LIMIT 1")
    abstract fun first(): CloudOutboxEntity?

    @Query("SELECT * FROM cloud_outbox ORDER BY sequence ASC")
    abstract fun all(): List<CloudOutboxEntity>

    @Query("SELECT * FROM cloud_active_session WHERE singletonId = 1")
    abstract fun activeSession(): CloudSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insert(entity: CloudOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertForMigration(entity: CloudOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveSession(entity: CloudSessionEntity)

    @Query("DELETE FROM cloud_active_session")
    abstract fun clearSession()

    @Query("DELETE FROM cloud_outbox WHERE sequence = :sequence")
    protected abstract fun deleteSequence(sequence: Long): Int

    @Query("DELETE FROM cloud_active_session WHERE sessionId = :sessionId")
    protected abstract fun deleteSession(sessionId: String): Int

    @Query("UPDATE cloud_outbox SET retryCount = retryCount + 1, lastError = :message, deliveryState = :state WHERE sequence = :sequence")
    protected abstract fun updateFailure(sequence: Long, message: String, state: String): Int

    @Transaction
    open fun enqueue(entity: CloudOutboxEntity, maximumOperations: Int) {
        val full = count() >= maximumOperations
        val protectedOperation = when (entity.type) {
            PendingOperationType.END_SESSION.name -> countTypeForSession(PendingOperationType.END_SESSION.name, entity.sessionId) == 0
            PendingOperationType.UPLOAD_RULE_EVENT.name -> countTypeForSession(PendingOperationType.UPLOAD_RULE_EVENT.name, entity.sessionId) < maximumOperations
            else -> false
        }
        if (full && !protectedOperation) {
            throw CloudOutboxFullException(
                "Cloud outbox limit ($maximumOperations) reached; collection was not acknowledged",
            )
        }
        insert(entity)
    }

    @Transaction
    open fun removeFirst() {
        val head = first() ?: return
        check(deleteSequence(head.sequence) == 1) { "Outbox head changed during acknowledgement" }
    }

    @Transaction
    open fun completeSessionEnd(sessionId: String) {
        val head = first()
        require(head?.type == PendingOperationType.END_SESSION.name && head.sessionId == sessionId) {
            "The first pending operation is not END_SESSION for $sessionId"
        }
        check(deleteSequence(head.sequence) == 1)
        deleteSession(sessionId)
    }

    @Transaction
    open fun recordFirstFailure(message: String, permanent: Boolean) {
        val head = first() ?: return
        val state = if (permanent) DeliveryState.PERMANENT_FAILURE.name else DeliveryState.PENDING.name
        check(updateFailure(head.sequence, message, state) == 1)
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
        return dao.first()?.toModel()
    }

    override fun removeFirst() = dao.removeFirst()
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
    override fun completeSessionEnd(sessionId: String) = dao.completeSessionEnd(sessionId)
    override fun recordFirstFailure(message: String, permanent: Boolean) =
        dao.recordFirstFailure(message, permanent)

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
                persisted.operations.map { it.toEntity() },
                persisted.activeSession?.toEntity(),
            )
            val migrated = source.resolveSibling("${source.name}.migrated")
            if (!source.renameTo(migrated)) {
                throw IllegalStateException("Legacy cloud state was imported but could not be archived")
            }
        }
        legacyChecked = true
    }

    private fun PendingCloudOperation.toEntity() = CloudOutboxEntity(
        operationId = operationId,
        type = type.name,
        sessionId = sessionId,
        payloadJson = when (type) {
            PendingOperationType.UPLOAD_MEASUREMENT -> payload?.let(gson::toJson)
            PendingOperationType.UPLOAD_RULE_EVENT -> ruleEvent?.let(gson::toJson)
            PendingOperationType.END_SESSION -> null
        },
        endedAt = endedAt,
        createdAt = createdAt,
        retryCount = retryCount,
        lastError = lastError,
        deliveryState = deliveryState.name,
    )

    private fun CloudOutboxEntity.toModel() = PendingCloudOperation(
        operationId = operationId,
        type = PendingOperationType.valueOf(type),
        sessionId = sessionId,
        payload = payloadJson?.takeIf { type == PendingOperationType.UPLOAD_MEASUREMENT.name }?.let { gson.fromJson(it, SensorPayload::class.java) },
        ruleEvent = payloadJson?.takeIf { type == PendingOperationType.UPLOAD_RULE_EVENT.name }?.let { gson.fromJson(it, SmartRuleEventPayload::class.java) },
        endedAt = endedAt,
        createdAt = createdAt,
        retryCount = retryCount,
        lastError = lastError,
        deliveryState = DeliveryState.valueOf(deliveryState),
    )

    private fun ActiveCloudSession.toEntity() = CloudSessionEntity(
        sessionId = sessionId,
        startedAt = startedAt,
        ownerUid = ownerUid,
        monitoringActive = monitoringActive,
    )

    private fun CloudSessionEntity.toModel() = ActiveCloudSession(
        sessionId = sessionId,
        startedAt = startedAt,
        ownerUid = ownerUid,
        monitoringActive = monitoringActive,
    )

    companion object { const val DEFAULT_MAXIMUM_OPERATIONS = 10_000 }
}
