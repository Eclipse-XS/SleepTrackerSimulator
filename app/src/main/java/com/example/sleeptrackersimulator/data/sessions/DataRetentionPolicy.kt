package com.example.sleeptrackersimulator.data.sessions

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data retention policy enforcing local storage quotas and privacy bounds:
 * - Raw samples: retained for 7 days by default
 * - Feature windows: retained for 30 days
 * - Predictions: retained for 30 days
 * - Summaries: retained until explicitly deleted by user
 */
class DataRetentionPolicy(
    private val sensorSampleDao: SensorSampleDao,
    private val heartRateSampleDao: HeartRateSampleDao,
    private val featureWindowDao: FeatureWindowDao,
    private val predictionDao: PredictionDao,
    private val sessionDao: SessionDao? = null,
    val rawSamplesRetentionDays: Int = 7,
    val featuresRetentionDays: Int = 30,
    val predictionsRetentionDays: Int = 30,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun pruneExpiredData(
        activeSessionId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): PruneResult = withContext(dispatcher) {
        val effectiveActiveSessionId = activeSessionId ?: sessionDao?.getActiveSession()?.sessionId
        val rawCutoff = now - (rawSamplesRetentionDays * 24 * 60 * 60 * 1000L)
        val featuresCutoff = now - (featuresRetentionDays * 24 * 60 * 60 * 1000L)
        val predictionsCutoff = now - (predictionsRetentionDays * 24 * 60 * 60 * 1000L)

        val deletedSensorSamples = sensorSampleDao.deleteOldSamples(rawCutoff, effectiveActiveSessionId)
        val deletedHeartRateSamples = heartRateSampleDao.deleteOldSamples(rawCutoff, effectiveActiveSessionId)
        val deletedWindows = featureWindowDao.deleteOldWindows(featuresCutoff, effectiveActiveSessionId)
        val deletedPredictions = predictionDao.deleteOldPredictions(predictionsCutoff, effectiveActiveSessionId)

        PruneResult(
            deletedSensorSamples = deletedSensorSamples,
            deletedHeartRateSamples = deletedHeartRateSamples,
            deletedFeatureWindows = deletedWindows,
            deletedPredictions = deletedPredictions,
            rawCutoffUtcMillis = rawCutoff,
            featuresCutoffUtcMillis = featuresCutoff,
            predictionsCutoffUtcMillis = predictionsCutoff,
        )
    }
}

data class PruneResult(
    val deletedSensorSamples: Int,
    val deletedHeartRateSamples: Int,
    val deletedFeatureWindows: Int,
    val deletedPredictions: Int,
    val rawCutoffUtcMillis: Long = 0L,
    val featuresCutoffUtcMillis: Long = 0L,
    val predictionsCutoffUtcMillis: Long = 0L,
)
