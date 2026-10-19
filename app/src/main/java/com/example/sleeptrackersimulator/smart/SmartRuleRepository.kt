package com.example.sleeptrackersimulator.smart

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Dao
interface SmartRuleDao {
    @Query("SELECT * FROM smart_rules ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SmartRuleEntity>>

    @Query("SELECT * FROM smart_rules WHERE enabled = 1 ORDER BY createdAt ASC")
    fun observeEnabled(): Flow<List<SmartRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SmartRuleEntity)

    @Query("DELETE FROM smart_rules WHERE id = :id")
    suspend fun delete(id: String): Int

    @Insert
    suspend fun insertExecution(entity: SmartRuleExecutionEntity)

    @Query("SELECT * FROM smart_rule_executions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentExecutions(limit: Int): Flow<List<SmartRuleExecutionEntity>>
}

interface SmartRuleRepository {
    fun observeAll(): Flow<List<SmartRule>>
    fun observeEnabled(): Flow<List<SmartRule>>
    fun observeRecentExecutions(limit: Int): Flow<List<SmartRuleExecutionEntity>>
    suspend fun save(rule: SmartRule)
    suspend fun delete(id: String)
    suspend fun record(execution: SmartRuleExecutionEntity)
}

class RoomSmartRuleRepository(
    private val dao: SmartRuleDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmartRuleRepository {
    override fun observeAll() = dao.observeAll().map { rows -> rows.map(SmartRuleEntity::toDomain) }
    override fun observeEnabled() = dao.observeEnabled().map { rows -> rows.map(SmartRuleEntity::toDomain) }
    override fun observeRecentExecutions(limit: Int) = dao.observeRecentExecutions(limit.coerceIn(1, 100))
    override suspend fun save(rule: SmartRule) { rule.validate(); withContext(dispatcher) { dao.upsert(rule.toEntity()) } }
    override suspend fun delete(id: String) { require(id.isNotBlank()); withContext(dispatcher) { dao.delete(id) } }
    override suspend fun record(execution: SmartRuleExecutionEntity) = withContext(dispatcher) { dao.insertExecution(execution) }
}
