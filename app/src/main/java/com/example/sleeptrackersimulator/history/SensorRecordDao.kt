package com.example.sleeptrackersimulator.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorRecordDao {
    @Insert
    suspend fun insert(record: SensorRecord): Long

    @Query(
        """SELECT * FROM (
            SELECT * FROM sensor_records
            WHERE timestamp >= :sinceInclusive
            ORDER BY timestamp DESC, id DESC
            LIMIT :limit
        ) ORDER BY timestamp ASC, id ASC""",
    )
    fun observeRecent(sinceInclusive: Long, limit: Int): Flow<List<SensorRecord>>

    @Query(
        """SELECT * FROM sensor_records
            WHERE timestamp BETWEEN :startInclusive AND :endInclusive
            ORDER BY timestamp ASC, id ASC""",
    )
    suspend fun recordsBetween(startInclusive: Long, endInclusive: Long): List<SensorRecord>

    @Query("SELECT COUNT(*) FROM sensor_records WHERE timestamp >= :sinceInclusive")
    fun observeCount(sinceInclusive: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM sensor_records WHERE timestamp >= :sinceInclusive AND actionTriggered = 1")
    fun observeActionCount(sinceInclusive: Long): Flow<Int>

    @Query("DELETE FROM sensor_records WHERE timestamp < :beforeExclusive")
    suspend fun deleteOlderThan(beforeExclusive: Long): Int
}
