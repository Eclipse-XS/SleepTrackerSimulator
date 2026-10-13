package com.example.sleeptrackersimulator.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

interface SensorHistoryRepository {
    suspend fun addMeasurement(timestamp: Long, sensorValue: Float, actionTriggered: Boolean): Long
    fun observeRecent(sinceInclusive: Long, limit: Int): Flow<List<SensorRecord>>
    suspend fun recordsBetween(startInclusive: Long, endInclusive: Long): List<SensorRecord>
    fun observeCount(sinceInclusive: Long): Flow<Int>
    fun observeActionCount(sinceInclusive: Long): Flow<Int>
    suspend fun deleteOlderThan(beforeExclusive: Long): Int
}

class RoomSensorHistoryRepository(
    private val dao: SensorRecordDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SensorHistoryRepository {
    override suspend fun addMeasurement(
        timestamp: Long,
        sensorValue: Float,
        actionTriggered: Boolean,
    ): Long {
        require(timestamp > 0) { "Timestamp must be a positive Unix time" }
        require(sensorValue.isFinite() && sensorValue >= 0f) { "Sensor value must be finite and non-negative" }
        return try {
            withContext(ioDispatcher) {
                dao.insert(SensorRecord(timestamp = timestamp, sensorValue = sensorValue, actionTriggered = actionTriggered))
            }.also { rowId ->
                DiagnosticLog.event(DiagnosticTags.ROOM, "insertMeasurement", DiagnosticResult.SUCCESS, details = mapOf("rowId" to rowId, "actionTriggered" to actionTriggered))
            }
        } catch (error: Exception) {
            DiagnosticLog.event(DiagnosticTags.ROOM, "insertMeasurement", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
            throw error
        }
    }

    override fun observeRecent(sinceInclusive: Long, limit: Int): Flow<List<SensorRecord>> =
        dao.observeRecent(sinceInclusive.coerceAtLeast(1), limit.coerceIn(1, MAX_QUERY_LIMIT))
            .onStart { DiagnosticLog.event(DiagnosticTags.ROOM, "observeRecent", DiagnosticResult.STARTED, details = mapOf("limit" to limit.coerceIn(1, MAX_QUERY_LIMIT))) }
            .catch { error ->
                DiagnosticLog.event(DiagnosticTags.ROOM, "observeRecent", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
                throw error
            }

    override suspend fun recordsBetween(startInclusive: Long, endInclusive: Long): List<SensorRecord> {
        require(startInclusive > 0 && endInclusive >= startInclusive) { "Invalid time interval" }
        return try {
            withContext(ioDispatcher) { dao.recordsBetween(startInclusive, endInclusive) }.also { records ->
                DiagnosticLog.event(DiagnosticTags.ROOM, "getRecords", DiagnosticResult.SUCCESS, details = mapOf("recordCount" to records.size))
            }
        } catch (error: Exception) {
            DiagnosticLog.event(DiagnosticTags.ROOM, "getRecords", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
            throw error
        }
    }

    override fun observeCount(sinceInclusive: Long): Flow<Int> = dao.observeCount(sinceInclusive.coerceAtLeast(1))

    override fun observeActionCount(sinceInclusive: Long): Flow<Int> =
        dao.observeActionCount(sinceInclusive.coerceAtLeast(1))

    override suspend fun deleteOlderThan(beforeExclusive: Long): Int {
        require(beforeExclusive > 0) { "Retention boundary must be positive" }
        return withContext(ioDispatcher) { dao.deleteOlderThan(beforeExclusive) }
    }

    companion object {
        const val MAX_QUERY_LIMIT = 500
    }
}
