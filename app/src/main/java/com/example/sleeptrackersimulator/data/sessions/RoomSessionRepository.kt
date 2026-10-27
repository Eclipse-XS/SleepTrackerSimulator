package com.example.sleeptrackersimulator.data.sessions

import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.toEntity
import com.example.sleeptrackersimulator.core.model.BaselineComparison
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.PersonalBaseline
import com.example.sleeptrackersimulator.core.model.Prediction
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
import com.example.sleeptrackersimulator.domain.recording.SessionRepository
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSessionRepository(
    private val sessionDao: SessionDao,
    private val sensorSampleDao: SensorSampleDao,
    private val heartRateSampleDao: HeartRateSampleDao,
    private val featureWindowDao: FeatureWindowDao,
    private val predictionDao: PredictionDao,
    private val sessionSummaryDao: SessionSummaryDao,
    private val personalBaselineDao: PersonalBaselineDao,
    private val deviceDao: DeviceDao,
    private val database: SleepTrackerDatabase? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository, AnalyticsRepository {

    override fun observeActiveSession(): Flow<RecordingSession?> {
        return sessionDao.observeActiveSession().map { it?.toDomain() }
    }

    override suspend fun getActiveSession(): RecordingSession? = withContext(dispatcher) {
        sessionDao.getActiveSession()?.toDomain()
    }

    override fun observeAllSessions(): Flow<List<RecordingSession>> {
        return sessionDao.observeAllSessions().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getSessionById(sessionId: String): RecordingSession? = withContext(dispatcher) {
        sessionDao.getSessionById(sessionId)?.toDomain()
    }

    override suspend fun saveSession(session: RecordingSession) = withContext(dispatcher) {
        sessionDao.insertSession(RecordingSessionEntity.fromDomain(session))
    }

    override suspend fun updateSession(session: RecordingSession) = withContext(dispatcher) {
        sessionDao.updateSession(RecordingSessionEntity.fromDomain(session))
    }

    override suspend fun deleteSession(sessionId: String) = withContext(dispatcher) {
        sessionDao.deleteSession(sessionId)
    }

    override suspend fun insertSamplesBatch(samples: List<RawSensorSample>) = withContext(dispatcher) {
        if (samples.isNotEmpty()) {
            sensorSampleDao.insertBatch(samples.map { SensorSampleEntity.fromDomain(it) })
        }
    }

    override suspend fun getSamplesForSession(sessionId: String): List<RawSensorSample> = withContext(dispatcher) {
        sensorSampleDao.getSamplesForSession(sessionId).map { it.toDomain() }
    }

    override suspend fun insertHeartRateSamples(samples: List<HeartRateSample>) = withContext(dispatcher) {
        if (samples.isNotEmpty()) {
            heartRateSampleDao.insertBatch(samples.map { HeartRateSampleEntity.fromDomain(it) })
        }
    }

    override suspend fun getHeartRateForSession(sessionId: String): List<HeartRateSample> = withContext(dispatcher) {
        heartRateSampleDao.getSamplesForSession(sessionId).map { it.toDomain() }
    }

    override suspend fun insertFeatureWindows(windows: List<FeatureWindow>) = withContext(dispatcher) {
        if (windows.isNotEmpty()) {
            featureWindowDao.insertBatch(windows.map { FeatureWindowEntity.fromDomain(it) })
        }
    }

    override suspend fun getFeatureWindows(sessionId: String): List<FeatureWindow> = withContext(dispatcher) {
        featureWindowDao.getWindowsForSession(sessionId).map { it.toDomain() }
    }

    override suspend fun insertPredictions(predictions: List<Prediction>) = withContext(dispatcher) {
        if (predictions.isNotEmpty()) {
            predictionDao.insertBatch(predictions.map { PredictionEntity.fromDomain(it) })
        }
    }

    override suspend fun getPredictionsForSession(sessionId: String): List<Prediction> = withContext(dispatcher) {
        predictionDao.getPredictionsForSession(sessionId).map { it.toDomain() }
    }

    override suspend fun saveSessionSummary(summary: SessionSummary) = withContext(dispatcher) {
        sessionSummaryDao.insertSummary(SessionSummaryEntity.fromDomain(summary))
    }

    override suspend fun saveSessionSummaryAndOutbox(
        summary: SessionSummary,
        completedSession: RecordingSession,
        outboxOperation: com.example.sleeptrackersimulator.cloud.PendingCloudOperation?,
    ): Boolean = withContext(dispatcher) {
        if (database != null) {
            database.withTransaction {
                sessionSummaryDao.insertSummary(SessionSummaryEntity.fromDomain(summary))
                sessionDao.updateSession(RecordingSessionEntity.fromDomain(completedSession))
                if (outboxOperation != null) {
                    database.cloudOutboxDao().enqueue(
                        outboxOperation.toEntity(),
                        RoomCloudStateStore.DEFAULT_MAXIMUM_OPERATIONS,
                    )
                }
            }
            true
        } else {
            sessionSummaryDao.insertSummary(SessionSummaryEntity.fromDomain(summary))
            sessionDao.updateSession(RecordingSessionEntity.fromDomain(completedSession))
            false
        }
    }

    override suspend fun getSessionSummary(sessionId: String): SessionSummary? = withContext(dispatcher) {
        sessionSummaryDao.getSummaryBySessionId(sessionId)?.toDomain()
    }

    // AnalyticsRepository implementation
    override fun observeRecentSessions(limit: Int): Flow<List<RecordingSession>> {
        return sessionDao.observeAllSessions().map { list -> list.take(limit).map { it.toDomain() } }
    }

    override suspend fun getRecentSummaries(days: Int): List<SessionSummary> = withContext(dispatcher) {
        val sinceUtc = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        sessionSummaryDao.getSummariesSince(sinceUtc).map { it.toDomain() }
    }

    override suspend fun getAllSummaries(): List<SessionSummary> = withContext(dispatcher) {
        sessionSummaryDao.getRecentSummaries(limit = MAX_ANALYTICS_SUMMARIES).map { it.toDomain() }
    }

    override suspend fun getPersonalBaseline(
        sourceMode: SourceMode,
        placement: DevicePlacement,
    ): PersonalBaseline? = withContext(dispatcher) {
        personalBaselineDao.getActiveBaseline(sourceMode.name, placement.name)?.toDomain()
    }

    override suspend fun recalculateBaseline(
        sourceMode: SourceMode,
        placement: DevicePlacement,
    ): PersonalBaseline? = withContext(dispatcher) {
        val summaries = sessionSummaryDao.getRecentSummaries(limit = 60)
            .map { it.toDomain() }
            .filter { it.isComparableWithBaseline && it.devicePlacement == placement }

        if (summaries.size < 3) return@withContext null // Need at least 3 for preliminary baseline

        val lowMotionValues = summaries.map { it.lowMotionFraction }.sorted()
        val median = lowMotionValues[lowMotionValues.size / 2]

        // Calculate MAD (Median Absolute Deviation)
        val absoluteDeviations = lowMotionValues.map { kotlin.math.abs(it - median) }.sorted()
        val mad = absoluteDeviations[absoluteDeviations.size / 2]

        val movementRates = summaries.map { it.movementRatePerHour }.sorted()
        val medianMovementRate = movementRates[movementRates.size / 2]

        val baseline = PersonalBaseline(
            baselineId = "baseline-${sourceMode.name}-${placement.name}",
            sourceMode = sourceMode,
            placement = placement,
            compatibleSessionCount = summaries.size,
            medianLowMotionFraction = median,
            madLowMotionFraction = mad,
            medianMovementRatePerHour = medianMovementRate,
            validFromUtcMillis = System.currentTimeMillis(),
        )

        personalBaselineDao.insertBaseline(PersonalBaselineEntity.fromDomain(baseline))
        baseline
    }

    override suspend fun compareSessionWithBaseline(sessionId: String): BaselineComparison? = withContext(dispatcher) {
        val summary = getSessionSummary(sessionId) ?: return@withContext null
        val baseline = getPersonalBaseline(
            sourceMode = SourceMode.PHONE_ONLY,
            placement = summary.devicePlacement,
        ) ?: return@withContext null

        baseline.compare(summary)
    }
}

private const val MAX_ANALYTICS_SUMMARIES = 10_000
