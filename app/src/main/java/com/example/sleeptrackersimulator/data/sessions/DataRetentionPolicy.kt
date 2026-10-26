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
    private val rawSamplesRetentionDays: Int = 7,
    private val featuresRetentionDays: Int = 30,
    private val predictionsRetentionDays: Int = 30,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun pruneExpiredData(): PruneResult = withContext(dispatcher) {
        val now = System.currentTimeMillis()
        val rawCutoff = now - (rawSamplesRetentionDays * 24 * 60 * 60 * 1000L)
        val featuresCutoff = now - (featuresRetentionDays * 24 * 60 * 60 * 1000L)
        val predictionsCutoff = now - (predictionsRetentionDays * 24 * 60 * 60 * 1000L)

        val deletedSensorSamples = sensorSampleDao.deleteOldSamples(rawCutoff)
        val deletedHeartRateSamples = heartRateSampleDao.deleteOldSamples(rawCutoff)
        val deletedWindows = featureWindowDao.deleteOldWindows(featuresCutoff)
        val deletedPredictions = predictionDao.deleteOldPredictions(predictionsCutoff)

        PruneResult(
            deletedSensorSamples = deletedSensorSamples,
            deletedHeartRateSamples = deletedHeartRateSamples,
            deletedFeatureWindows = deletedWindows,
            deletedPredictions = deletedPredictions,
        )
    }
}

data class PruneResult(
    val deletedSensorSamples: Int,
    val deletedHeartRateSamples: Int,
    val deletedFeatureWindows: Int,
    val deletedPredictions: Int,
)
