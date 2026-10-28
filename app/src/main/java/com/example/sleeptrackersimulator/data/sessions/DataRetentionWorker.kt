package com.example.sleeptrackersimulator.data.sessions

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sleeptrackersimulator.app.SleepTrackerApplication
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import java.util.concurrent.TimeUnit

/**
 * Scheduled background worker executing Room and Cloud data retention policies.
 * Runs once every 24 hours to enforce privacy quotas and prevent unbounded SQLite storage growth.
 */
class DataRetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SleepTrackerApplication ?: return Result.failure()
        return try {
            val activeSessionId = app.container.sessionCoordinator.activeSession.value?.sessionId
            val result = app.container.dataRetentionPolicy.pruneExpiredData(activeSessionId = activeSessionId)

            DiagnosticLog.event(
                tag = DiagnosticTags.LIFECYCLE,
                operation = "data_retention_prune",
                result = DiagnosticResult.SUCCESS,
                details = mapOf(
                    "deletedSensorSamples" to result.deletedSensorSamples,
                    "deletedHeartRateSamples" to result.deletedHeartRateSamples,
                    "deletedFeatureWindows" to result.deletedFeatureWindows,
                    "deletedPredictions" to result.deletedPredictions,
                ),
            )

            // Cloud retention cleanup if authenticated
            val ownerUid = app.container.authRepository.currentUid
            if (!ownerUid.isNullOrBlank()) {
                runCatching {
                    app.container.cloudRepository.applyRetention(
                        ownerUid = ownerUid,
                        activeSessionId = activeSessionId,
                        now = System.currentTimeMillis(),
                    )
                }
            }

            Result.success()
        } catch (e: Exception) {
            DiagnosticLog.event(
                tag = DiagnosticTags.LIFECYCLE,
                operation = "data_retention_prune",
                result = DiagnosticResult.FAILED,
                error = e,
            )
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sleepsense-data-retention-prune"

        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<DataRetentionWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
        }
    }
}
