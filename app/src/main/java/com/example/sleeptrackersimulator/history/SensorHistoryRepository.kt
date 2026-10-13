package com.example.sleeptrackersimulator.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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
        return withContext(ioDispatcher) {
            dao.insert(SensorRecord(timestamp = timestamp, sensorValue = sensorValue, actionTriggered = actionTriggered))
        }
    }

    override fun observeRecent(sinceInclusive: Long, limit: Int): Flow<List<SensorRecord>> =
        dao.observeRecent(sinceInclusive.coerceAtLeast(1), limit.coerceIn(1, MAX_QUERY_LIMIT))

    override suspend fun recordsBetween(startInclusive: Long, endInclusive: Long): List<SensorRecord> {
        require(startInclusive > 0 && endInclusive >= startInclusive) { "Invalid time interval" }
        return withContext(ioDispatcher) { dao.recordsBetween(startInclusive, endInclusive) }
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
