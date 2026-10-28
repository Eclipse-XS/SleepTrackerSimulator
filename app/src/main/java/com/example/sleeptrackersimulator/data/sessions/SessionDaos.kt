package com.example.sleeptrackersimulator.data.sessions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: RecordingSessionEntity)

    @Update
    suspend fun updateSession(session: RecordingSessionEntity)

    @Query("SELECT * FROM recording_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE status IN ('PREPARING', 'RECORDING', 'PAUSED') ORDER BY startUtcMillis DESC LIMIT 1")
    fun observeActiveSession(): Flow<RecordingSessionEntity?>

    @Query("SELECT * FROM recording_sessions WHERE status IN ('PREPARING', 'RECORDING', 'PAUSED') ORDER BY startUtcMillis DESC LIMIT 1")
    suspend fun getActiveSession(): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions ORDER BY startUtcMillis DESC")
    fun observeAllSessions(): Flow<List<RecordingSessionEntity>>

    @Query("DELETE FROM recording_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM recording_sessions WHERE sourceMode = 'DEMO' AND sessionId LIKE 'demo-v1-%'")
    suspend fun deleteDemoSessions(): Int

    @Query("SELECT COUNT(*) FROM recording_sessions WHERE sourceMode = 'DEMO' AND sessionId LIKE 'demo-v1-%'")
    suspend fun countDemoSessions(): Int

    @Query("SELECT * FROM recording_sessions WHERE sourceMode = 'DEMO' OR sessionId LIKE 'demo-v1-%' ORDER BY startUtcMillis ASC")
    suspend fun getDemoSessions(): List<RecordingSessionEntity>

    @Query("UPDATE recording_sessions SET status = :status, updatedAt = :updatedAt WHERE sessionId = :sessionId")
    suspend fun updateStatus(sessionId: String, status: String, updatedAt: Long)
}

@Dao
interface SensorSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(samples: List<SensorSampleEntity>)

    @Query("SELECT * FROM sensor_samples WHERE sessionId = :sessionId ORDER BY utcTimestampMillis ASC")
    suspend fun getSamplesForSession(sessionId: String): List<SensorSampleEntity>

    @Query("SELECT COUNT(*) FROM sensor_samples WHERE sessionId = :sessionId")
    suspend fun countSamplesForSession(sessionId: String): Int

    @Query("DELETE FROM sensor_samples WHERE utcTimestampMillis < :olderThanUtcMillis")
    suspend fun deleteOldSamples(olderThanUtcMillis: Long): Int
}

@Dao
interface HeartRateSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(samples: List<HeartRateSampleEntity>)

    @Query("SELECT * FROM heart_rate_samples WHERE sessionId = :sessionId ORDER BY utcTimestampMillis ASC")
    suspend fun getSamplesForSession(sessionId: String): List<HeartRateSampleEntity>

    @Query("DELETE FROM heart_rate_samples WHERE utcTimestampMillis < :olderThanUtcMillis")
    suspend fun deleteOldSamples(olderThanUtcMillis: Long): Int
}

@Dao
interface FeatureWindowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(windows: List<FeatureWindowEntity>)

    @Query("SELECT * FROM feature_windows WHERE sessionId = :sessionId ORDER BY startUtcMillis ASC")
    suspend fun getWindowsForSession(sessionId: String): List<FeatureWindowEntity>

    @Query("DELETE FROM feature_windows WHERE startUtcMillis < :olderThanUtcMillis")
    suspend fun deleteOldWindows(olderThanUtcMillis: Long): Int
}

@Dao
interface PredictionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(predictions: List<PredictionEntity>)

    @Query("SELECT * FROM predictions WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    suspend fun getPredictionsForSession(sessionId: String): List<PredictionEntity>

    @Query("DELETE FROM predictions WHERE timestampMillis < :olderThanUtcMillis")
    suspend fun deleteOldPredictions(olderThanUtcMillis: Long): Int
}

@Dao
interface SessionSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: SessionSummaryEntity)

    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun getSummaryBySessionId(sessionId: String): SessionSummaryEntity?

    @Query("SELECT * FROM session_summaries ORDER BY calculatedAtUtcMillis DESC LIMIT :limit")
    suspend fun getRecentSummaries(limit: Int): List<SessionSummaryEntity>

    @Query("SELECT * FROM session_summaries ORDER BY calculatedAtUtcMillis DESC LIMIT :limit")
    fun observeRecentSummaries(limit: Int): Flow<List<SessionSummaryEntity>>

    @Query("SELECT * FROM session_summaries WHERE sessionId LIKE 'demo-v1-%' ORDER BY calculatedAtUtcMillis ASC")
    suspend fun getDemoSummaries(): List<SessionSummaryEntity>

    @Query("SELECT * FROM session_summaries WHERE calculatedAtUtcMillis >= :sinceUtcMillis ORDER BY calculatedAtUtcMillis DESC")
    suspend fun getSummariesSince(sinceUtcMillis: Long): List<SessionSummaryEntity>
}

@Dao
interface PersonalBaselineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaseline(baseline: PersonalBaselineEntity)

    @Query("SELECT * FROM personal_baselines WHERE sourceMode = :sourceMode AND placement = :placement AND (validUntilUtcMillis IS NULL OR validUntilUtcMillis > :nowUtcMillis) ORDER BY validFromUtcMillis DESC LIMIT 1")
    suspend fun getActiveBaseline(sourceMode: String, placement: String, nowUtcMillis: Long = System.currentTimeMillis()): PersonalBaselineEntity?

    @Query("DELETE FROM personal_baselines WHERE sourceMode = :sourceMode AND placement = :placement")
    suspend fun deleteBaselines(sourceMode: String, placement: String)
}

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity)

    @Query("SELECT * FROM devices ORDER BY lastSeenUtcMillis DESC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId")
    suspend fun getDevice(deviceId: String): DeviceEntity?
}
