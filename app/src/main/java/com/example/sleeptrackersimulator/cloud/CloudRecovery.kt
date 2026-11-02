package com.example.sleeptrackersimulator.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.sleeptrackersimulator.smart.RoomSmartRuleEventCoordinator
import java.util.concurrent.TimeUnit

fun interface CloudRecoveryScheduler {
    fun schedule()
}

object NoOpCloudRecoveryScheduler : CloudRecoveryScheduler {
    override fun schedule() = Unit
}

class WorkManagerCloudRecoveryScheduler(context: Context) : CloudRecoveryScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<CloudOutboxWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // REPLACE closes the enqueue-vs-worker-finish race: the newest persisted operation
        // always owns a scheduled successor. Firebase keys keep interrupted retries idempotent.
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object { const val UNIQUE_WORK_NAME = "cloud-outbox-delivery" }
}

/** Serializes foreground and WorkManager drainers in the default application process. */
object CloudDrainCoordinator {
    val mutex = Mutex()
}

class CloudOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = CloudDrainCoordinator.mutex.withLock {
        val consentRepository = SharedPreferencesCloudSyncConsentRepository(applicationContext)
        val consentEnabled = consentRepository.isCloudSyncEnabled.value
        val database = SleepTrackerDatabase.getInstance(applicationContext)
        val store = RoomCloudStateStore(
            dao = database.cloudOutboxDao(),
            legacyFile = applicationContext.filesDir.resolve("cloud/cloud-state.json"),
        )
        if (!consentEnabled) {
            store.purgeUnconsentedUploads()
            if (store.pendingCount == 0) {
                return@withLock Result.success()
            }
        }

        try {
            RoomSmartRuleEventCoordinator(database.smartRuleEventCoordinatorDao())
                .reconcile(System.currentTimeMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock Result.retry()
        }
        CloudOutboxDrainer.drain(
            store = store,
            repository = FirebaseCloudRepository(),
            authRepository = FirebaseAnonymousAuthRepository(),
            isConsentEnabled = consentEnabled,
            statusUpdater = { eventId -> database.smartRuleDao().updateCloudStatus(eventId, "SYNCED") },
        )
    }
}

object CloudOutboxDrainer {
    suspend fun drain(
        store: CloudStateStore,
        repository: CloudRepository,
        authRepository: AuthRepository,
        isConsentEnabled: Boolean = true,
        statusUpdater: suspend (String) -> Unit = {},
    ): Result {
        val uid = try {
            authRepository.ensureAuthenticated()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudOperationException) {
            return if (failure.retriable) Result.retry() else Result.failure()
        } catch (_: Exception) {
            return Result.retry()
        }

        // 1. Drain pending DELETE_SESSION operations at the head of the outbox first.
        // Data erasure (Right to be Forgotten) is an independent privacy right and
        // must execute even if upload consent is revoked, without resurrecting sessions.
        while (true) {
            val operation = store.peek() ?: break
            if (operation.type == PendingOperationType.DELETE_SESSION) {
                try {
                    repository.deleteSession(uid, operation.sessionId)
                    store.removeFirst()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: CloudOperationException) {
                    runCatching { store.recordFirstFailure(failure.message ?: failure.type.name, !failure.retriable) }
                    return if (failure.retriable) Result.retry() else Result.failure()
                } catch (_: Exception) {
                    return Result.retry()
                }
            } else {
                break
            }
        }

        if (store.pendingCount == 0) return Result.success()

        // 2. If upload consent is disabled, any remaining non-deletion operations must be purged
        if (!isConsentEnabled) {
            store.purgeUnconsentedUploads()
            return Result.success()
        }

        val session = try {
            store.activeSession()
        } catch (_: Exception) {
            return Result.retry()
        }

        // Session summaries are self-contained terminal artifacts. They remain deliverable
        // after monitoring has ended and therefore must not depend on activeSession().
        if (session == null) {
            return try {
                while (true) {
                    val operation = store.peek() ?: break
                    if (operation.deliveryState == DeliveryState.PERMANENT_FAILURE) return Result.failure()
                    when (operation.type) {
                        PendingOperationType.UPLOAD_SESSION_SUMMARY -> {
                            repository.uploadSessionSummary(uid, requireNotNull(operation.summary))
                            store.removeFirst()
                        }
                        PendingOperationType.DELETE_SESSION -> {
                            repository.deleteSession(uid, operation.sessionId)
                            store.removeFirst()
                        }
                        else -> {
                            store.recordFirstFailure("Operation ${operation.type} requires active session metadata", true)
                            return Result.failure()
                        }
                    }
                }
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudOperationException) {
                runCatching { store.recordFirstFailure(failure.message ?: failure.type.name, !failure.retriable) }
                if (failure.retriable) Result.retry() else Result.failure()
            } catch (_: Exception) {
                Result.retry()
            }
        }

        if (session.ownerUid.isNotBlank() && session.ownerUid != uid) {
            runCatching { store.recordFirstFailure("Pending data belongs to another Firebase UID", true) }
            return Result.failure()
        }
        if (session.ownerUid.isBlank()) {
            try {
                store.saveActiveSession(session.copy(ownerUid = uid))
            } catch (_: Exception) {
                return Result.retry()
            }
        }

        return try {
            repository.createSession(uid, session.sessionId, CLOUD_DEVICE_ID, session.startedAt)
            while (true) {
                val operation = store.peek() ?: break
                if (operation.deliveryState == DeliveryState.PERMANENT_FAILURE) return Result.failure()
                when (operation.type) {
                    PendingOperationType.UPLOAD_MEASUREMENT -> {
                        repository.uploadMeasurement(uid, operation.sessionId, requireNotNull(operation.payload))
                        store.removeFirst()
                    }
                    PendingOperationType.UPLOAD_RULE_EVENT -> {
                        val event = requireNotNull(operation.ruleEvent)
                        repository.uploadRuleEvent(uid, operation.sessionId, event)
                        statusUpdater(event.eventId)
                        store.removeFirst()
                    }
                    PendingOperationType.UPLOAD_SESSION_SUMMARY -> {
                        val summary = requireNotNull(operation.summary)
                        repository.uploadSessionSummary(uid, summary)
                        store.removeFirst()
                    }
                    PendingOperationType.END_SESSION -> {
                        repository.endSession(uid, operation.sessionId, requireNotNull(operation.endedAt))
                        store.completeSessionEnd(operation.sessionId)
                        runCatching { repository.applyRetention(uid, null, System.currentTimeMillis()) }
                    }
                    PendingOperationType.DELETE_SESSION -> {
                        repository.deleteSession(uid, operation.sessionId)
                        store.removeFirst()
                    }
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudOperationException) {
            runCatching { store.recordFirstFailure(failure.message ?: failure.type.name, !failure.retriable) }
            if (failure.retriable) Result.retry() else Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
