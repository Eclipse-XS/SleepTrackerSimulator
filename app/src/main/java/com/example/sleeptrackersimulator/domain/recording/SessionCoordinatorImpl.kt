package com.example.sleeptrackersimulator.domain.recording

import com.example.sleeptrackersimulator.cloud.AuthRepository
import com.example.sleeptrackersimulator.cloud.CloudRepository
import com.example.sleeptrackersimulator.cloud.SessionSummaryPayload
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.ClockAnchor
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.Prediction
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionQualityStatus
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.domain.signalprocessing.FeatureExtractor
import com.example.sleeptrackersimulator.domain.signalprocessing.SampleValidator
import com.example.sleeptrackersimulator.domain.signalprocessing.SlidingWindowSegmenter
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class SessionCoordinatorImpl(
    private val sessionRepository: SessionRepository,
    private val activityClassifier: ActivityClassifier? = null,
    private val sampleValidator: SampleValidator = SampleValidator(),
    private val cloudRepository: CloudRepository? = null,
    private val authRepository: AuthRepository? = null,
    private val cloudStateStore: com.example.sleeptrackersimulator.cloud.CloudStateStore? = null,
    private val cloudRecoveryScheduler: com.example.sleeptrackersimulator.cloud.CloudRecoveryScheduler? = null,
    private val modelSelectionEngine: com.example.sleeptrackersimulator.ml.ModelSelectionEngine =
        com.example.sleeptrackersimulator.ml.DefaultModelSelectionEngine(activityClassifier),
    private val automationCoordinator: com.example.sleeptrackersimulator.smart.BackgroundAutomationCoordinator? = null,
    private val dataRetentionPolicy: com.example.sleeptrackersimulator.data.sessions.DataRetentionPolicy? = null,
    private val cloudSyncConsentRepository: com.example.sleeptrackersimulator.cloud.CloudSyncConsentRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val initialization = CompletableDeferred<Unit>()

    private val _activeSession = MutableStateFlow<RecordingSession?>(null)
    override val activeSession: StateFlow<RecordingSession?> = _activeSession.asStateFlow()

    private val _sessionStatus = MutableStateFlow(SessionStatus.IDLE)
    override val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()

    private var currentClockAnchor: ClockAnchor? = null
    private val windowSegmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)
    private val pendingSamples = mutableListOf<RawSensorSample>()
    private var latestHeartRateSample: HeartRateSample? = null

    // O(1) statistical counters avoiding unbounded heap growth during long recordings
    private var totalPredictionsCount = 0
    private var restPredictionsCount = 0
    private var restlessPredictionsCount = 0
    private var activePredictionsCount = 0
    private var movementEpisodeCount = 0
    private var lastWasMovement = false
    private var droppedSamplesCount = 0L
    private var totalPausedDurationMs = 0L
    private var lastPauseTimestampMs: Long? = null

    init {
        // Check for existing active or interrupted session on initialization
        scope.launch {
            try {
                val existing = sessionRepository.getActiveSession()
                if (existing != null) {
                    // A fresh coordinator means the application process was recreated. The
                    // non-sticky recording service cannot still own sensor collection, so
                    // presenting the persisted row as RECORDING would fabricate continuity.
                    val restored = if (existing.status == SessionStatus.RECORDING || existing.status == SessionStatus.PAUSED) {
                        existing.withStatus(
                            SessionStatus.INTERRUPTED,
                            "Recording process was restarted; persisted samples were preserved",
                        ).also { sessionRepository.updateSession(it) }
                    } else {
                        existing
                    }
                    _activeSession.value = restored
                    _sessionStatus.value = restored.status
                    reconstructCounters(restored.sessionId)
                }
            } finally {
                initialization.complete(Unit)
            }
        }
    }

    override suspend fun startSession(
        sourceMode: SourceMode,
        placement: DevicePlacement,
        samplingHz: Int,
    ): Result<RecordingSession> {
        initialization.await()
        return mutex.withLock {
        withContext(dispatcher) {
            val existing = _activeSession.value
            if (existing != null && existing.status.isActive) {
                return@withContext Result.failure(
                    IllegalStateException("A session is already active: ${existing.sessionId}"),
                )
            }

            val nowUtc = System.currentTimeMillis()
            val nowElapsed = runCatching { android.os.SystemClock.elapsedRealtimeNanos() }.getOrDefault(System.nanoTime())
            currentClockAnchor = ClockAnchor(nowElapsed, nowUtc)

            val session = RecordingSession(
                sessionId = UUID.randomUUID().toString(),
                startUtcMillis = nowUtc,
                startElapsedRealtimeNanos = nowElapsed,
                status = SessionStatus.RECORDING,
                sourceMode = sourceMode,
                devicePlacement = placement,
                requestedSamplingHz = samplingHz,
                modelVersion = activityClassifier?.let { com.example.sleeptrackersimulator.ml.DeployedModelIds.ACCELEROMETER },
                dataCoverageFraction = 1.0f,
                qualityStatus = SessionQualityStatus.EXCELLENT,
            )

            windowSegmenter.clear()
            pendingSamples.clear()
            latestHeartRateSample = null
            totalPredictionsCount = 0
            restPredictionsCount = 0
            restlessPredictionsCount = 0
            activePredictionsCount = 0
            movementEpisodeCount = 0
            lastWasMovement = false
            droppedSamplesCount = 0L
            totalPausedDurationMs = 0L
            lastPauseTimestampMs = null
            automationCoordinator?.resetSessionState()

            sessionRepository.saveSession(session)
            _activeSession.value = session
            _sessionStatus.value = SessionStatus.RECORDING

            Result.success(session)
        }
        }
    }

    override suspend fun ingestSensorEvent(
        sourceId: String,
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
        accuracy: Int,
        sourceKind: SourceKind,
    ) = mutex.withLock {
        val session = _activeSession.value ?: return@withLock
        if (session.status != SessionStatus.RECORDING) return@withLock

        val anchor = currentClockAnchor ?: ClockAnchor.current().also { currentClockAnchor = it }
        val utcMillis = anchor.toUtcMillis(elapsedNanos)

        val validation = sampleValidator.validate(
            sessionId = session.sessionId,
            sourceId = sourceId,
            elapsedNanos = elapsedNanos,
            utcMillis = utcMillis,
            x = x,
            y = y,
            z = z,
            accuracy = accuracy,
            sourceKind = sourceKind,
        )

        when (validation) {
            is com.example.sleeptrackersimulator.domain.signalprocessing.SampleValidationResult.Valid -> {
                val sample = validation.sample
                pendingSamples.add(sample)

                // Flush samples batch every 50 samples
                if (pendingSamples.size >= 50) {
                    val batch = pendingSamples.toList()
                    pendingSamples.clear()
                    sessionRepository.insertSamplesBatch(batch)
                }

                // Process windows
                val windows = windowSegmenter.addSample(sample)
                for (w in windows) {
                    processWindow(w, session)
                }

                // Evaluate background automations for physical sensor inputs
                automationCoordinator?.evaluateSensorSample(sample, session.sessionId)
            }
            is com.example.sleeptrackersimulator.domain.signalprocessing.SampleValidationResult.Invalid -> {
                // Invalid input is an intentional rejection, but it still reduces observed
                // coverage and must therefore participate in loss accounting.
                droppedSamplesCount++
            }
        }
    }

    override suspend fun ingestHeartRateSample(
        deviceId: String,
        bpm: Int,
        utcTimestampMillis: Long,
        timingUncertaintyMillis: Long,
        sourceKind: SourceKind,
    ) = mutex.withLock {
        val session = _activeSession.value ?: return@withLock
        if (session.status != SessionStatus.RECORDING) return@withLock

        val sample = HeartRateSample(
            sessionId = session.sessionId,
            deviceId = deviceId,
            bpm = bpm,
            utcTimestampMillis = utcTimestampMillis,
            timingUncertaintyMillis = timingUncertaintyMillis,
            sourceKind = sourceKind,
        )
        latestHeartRateSample = sample
        sessionRepository.insertHeartRateSamples(listOf(sample))
    }

    private suspend fun processWindow(samples: List<RawSensorSample>, session: RecordingSession) {
        val featureWindow = FeatureExtractor.extractFeatures(samples, expectedSampleCount = 20)
        sessionRepository.insertFeatureWindows(listOf(featureWindow))

        // Robust Model Selection Engine (TFLite -> Fallback -> Insufficient Data)
        val selection = modelSelectionEngine.selectAndClassify(
            samples = samples,
            recentHeartRate = latestHeartRateSample,
            windowEndTimestampNanos = featureWindow.endElapsedNanos,
        )

        val prediction = Prediction(
            predictionId = selection.predictionId,
            windowId = featureWindow.windowId,
            sessionId = session.sessionId,
            modelId = selection.modelId,
            predictedClass = selection.activity,
            probabilities = selection.probabilities,
            confidence = selection.confidence,
            inferenceDurationNs = selection.inferenceDurationNs,
            timestampMillis = featureWindow.endUtcMillis,
        )

        totalPredictionsCount++
        when (selection.activity) {
            ActivityClass.REST -> restPredictionsCount++
            ActivityClass.RESTLESS -> restlessPredictionsCount++
            ActivityClass.ACTIVE -> activePredictionsCount++
            else -> {}
        }
        if (selection.activity.isMovement) {
            if (!lastWasMovement) {
                movementEpisodeCount++
                lastWasMovement = true
            }
        } else {
            lastWasMovement = false
        }

        sessionRepository.insertPredictions(listOf(prediction))

        // Trigger ML-based background automations
        val predictionForAutomation = if (selection.activity != ActivityClass.UNCERTAIN) com.example.sleeptrackersimulator.ml.ActivityPrediction(
            predictionId = prediction.predictionId,
            windowEndTimestampNanos = featureWindow.endElapsedNanos,
            activity = when (selection.activity) {
                ActivityClass.REST -> com.example.sleeptrackersimulator.ml.ActivityClass.REST
                ActivityClass.RESTLESS -> com.example.sleeptrackersimulator.ml.ActivityClass.RESTLESS
                ActivityClass.ACTIVE -> com.example.sleeptrackersimulator.ml.ActivityClass.ACTIVE
                ActivityClass.UNCERTAIN -> error("UNCERTAIN predictions must not enter class-based automation")
            },
            confidence = selection.confidence,
            probabilities = mapOf(
                com.example.sleeptrackersimulator.ml.ActivityClass.REST to (selection.probabilities[ActivityClass.REST] ?: 0f),
                com.example.sleeptrackersimulator.ml.ActivityClass.RESTLESS to (selection.probabilities[ActivityClass.RESTLESS] ?: 0f),
                com.example.sleeptrackersimulator.ml.ActivityClass.ACTIVE to (selection.probabilities[ActivityClass.ACTIVE] ?: 0f),
            ),
            inferenceNanos = selection.inferenceDurationNs,
        ) else null
        predictionForAutomation?.let { automationCoordinator?.evaluatePrediction(it, session.sessionId) }
    }

    override suspend fun pauseSession(): Result<Unit> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active session"))
            if (session.status != SessionStatus.RECORDING) {
                return@withContext Result.failure(IllegalStateException("Cannot pause from status ${session.status}"))
            }

            // Flush remaining samples before pausing
            if (pendingSamples.isNotEmpty()) {
                sessionRepository.insertSamplesBatch(pendingSamples.toList())
                pendingSamples.clear()
            }

            val paused = session.withStatus(SessionStatus.PAUSED)
            lastPauseTimestampMs = System.currentTimeMillis()
            sessionRepository.updateSession(paused)
            _activeSession.value = paused
            _sessionStatus.value = SessionStatus.PAUSED
            Result.success(Unit)
        }
    }

    override suspend fun resumeSession(): Result<Unit> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active session"))
            if (session.status != SessionStatus.PAUSED) {
                return@withContext Result.failure(IllegalStateException("Cannot resume from status ${session.status}"))
            }

            val pauseStart = lastPauseTimestampMs
            if (pauseStart != null) {
                totalPausedDurationMs += (System.currentTimeMillis() - pauseStart).coerceAtLeast(0L)
                lastPauseTimestampMs = null
            }

            val resumed = session.withStatus(SessionStatus.RECORDING)
            sessionRepository.updateSession(resumed)
            _activeSession.value = resumed
            _sessionStatus.value = SessionStatus.RECORDING
            Result.success(Unit)
        }
    }

    override suspend fun stopSession(): Result<RecordingSession> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active session"))

            // Flush remaining samples
            if (pendingSamples.isNotEmpty()) {
                sessionRepository.insertSamplesBatch(pendingSamples.toList())
                pendingSamples.clear()
            }

            val endUtc = System.currentTimeMillis()
            val pauseStart = lastPauseTimestampMs
            if (pauseStart != null) {
                totalPausedDurationMs += (endUtc - pauseStart).coerceAtLeast(0L)
                lastPauseTimestampMs = null
            }
            val activeDurationMs = maxOf(1000L, endUtc - session.startUtcMillis - totalPausedDurationMs)
            val observationDurationMs = activeDurationMs

            // If counters are 0, reconstruct from Room to handle recovery from process death
            if (totalPredictionsCount == 0) {
                reconstructCounters(session.sessionId)
            }

            val windows = sessionRepository.getFeatureWindows(session.sessionId)
            val total = totalPredictionsCount
            val lowMotionCount = restPredictionsCount
            val restlessCount = restlessPredictionsCount
            val activeCount = activePredictionsCount
            val movementEpisodes = movementEpisodeCount

            val lowMotionFraction = if (total > 0) {
                lowMotionCount.toFloat() / total
            } else 0.0f

            val hours = maxOf(0.016f, observationDurationMs / 3_600_000.0f)
            val movementRatePerHour = movementEpisodes / hours

            val validWindowsCount = windows.count { it.qualityScore >= 0.5f }
            val windowCoverage = if (windows.isNotEmpty()) {
                validWindowsCount.toFloat() / windows.size
            } else 0.0f
            val persistedSamples = windows.sumOf { it.sampleCount }.toLong() + pendingSamples.size
            val totalSampleDenominator = persistedSamples + droppedSamplesCount
            val ingestionCoverage = if (totalSampleDenominator > 0L && persistedSamples > 0L) {
                persistedSamples.toFloat() / totalSampleDenominator.toFloat()
            } else 0.0f
            val coverageFraction = if (windows.isEmpty() || persistedSamples == 0L) {
                0.0f
            } else {
                (windowCoverage * ingestionCoverage).coerceIn(0f, 1f)
            }
            val hrSamples = sessionRepository.getHeartRateForSession(session.sessionId)
            val meanHr = if (hrSamples.isNotEmpty()) hrSamples.map { it.bpm }.average().toInt() else null
            val containsSyntheticHeartRate = hrSamples.any { it.sourceKind.isSimulated || it.sourceKind.isReplay }
            val summarySource = when {
                session.sourceMode.isSynthetic || containsSyntheticHeartRate -> SourceKind.SIMULATED
                session.sourceMode == SourceMode.PHONE_ONLY -> SourceKind.PHONE_REAL
                hrSamples.any { it.sourceKind == SourceKind.BLE_REAL } -> SourceKind.BLE_REAL
                session.sourceMode.requiresBleConnection -> SourceKind.BLE_UNVERIFIED
                else -> SourceKind.PHONE_REAL
            }

            val summary = SessionSummary(
                sessionId = session.sessionId,
                observationDurationMs = observationDurationMs,
                validDurationMs = (coverageFraction * observationDurationMs).toLong(),
                dataCoverageFraction = coverageFraction,
                lowMotionFraction = lowMotionFraction,
                movementEpisodeCount = movementEpisodes,
                movementRatePerHour = movementRatePerHour,
                averageHeartRateBpm = meanHr,
                sourceKind = summarySource,
                devicePlacement = session.devicePlacement,
                modelVersion = session.modelVersion ?: "heuristic-baseline",
                calculatedAtUtcMillis = endUtc,
            )

            val completed = session.completed(endUtcMillis = endUtc, coverageFraction = coverageFraction)

            // Offline-first cloud synchronization
            val uid = authRepository?.currentUid
            val totalPreds = maxOf(1, total)

            val payload = SessionSummaryPayload(
                sessionId = summary.sessionId,
                startedAt = session.startUtcMillis,
                endedAt = endUtc,
                observationDurationMs = summary.observationDurationMs,
                lowMotionFraction = summary.lowMotionFraction,
                restlessFraction = restlessCount.toFloat() / totalPreds,
                activeFraction = activeCount.toFloat() / totalPreds,
                meanHeartRateBpm = meanHr,
                sourceMode = session.sourceMode.name,
                placement = summary.devicePlacement.name,
                qualityStatus = session.qualityStatus.name,
                schemaVersion = 2,
            )

            val isSyncConsented = cloudSyncConsentRepository?.isCloudSyncEnabled?.value ?: true
            val outboxOp = if (isSyncConsented && cloudStateStore != null && !summary.sourceKind.isSimulated && !summary.sourceKind.isReplay) {
                com.example.sleeptrackersimulator.cloud.PendingCloudOperation(
                    operationId = "summary-${summary.sessionId}",
                    type = com.example.sleeptrackersimulator.cloud.PendingOperationType.UPLOAD_SESSION_SUMMARY,
                    sessionId = summary.sessionId,
                    summary = payload,
                    createdAt = endUtc,
                )
            } else null

            // ATOMIC SAVE: SessionSummary + Session completion + Cloud Outbox enqueue in one SQLite transaction
            val outboxSavedAtomically = sessionRepository.saveSessionSummaryAndOutbox(summary, completed, outboxOp)

            val store = cloudStateStore
            if (outboxOp != null) {
                if (!outboxSavedAtomically && store != null) {
                    runCatching { store.enqueue(outboxOp) }
                }
                cloudRecoveryScheduler?.schedule()
            } else if (isSyncConsented && uid != null && cloudRepository != null && !summary.sourceKind.isSimulated && !summary.sourceKind.isReplay) {
                runCatching {
                    cloudRepository.uploadSessionSummary(uid, payload)
                }
            }

            _activeSession.value = null
            _sessionStatus.value = SessionStatus.IDLE
            automationCoordinator?.resetSessionState()

            // Asynchronously prune expired data (samples > 7d, windows/preds > 30d) without blocking stop
            scope.launch {
                runCatching { dataRetentionPolicy?.pruneExpiredData(activeSessionId = null) }
            }

            Result.success(completed)
        }
    }

    override suspend fun reportDroppedSamples(count: Long) = mutex.withLock {
        droppedSamplesCount = count.coerceAtLeast(0L)
    }

    override suspend fun failSession(reason: String, droppedSamples: Long): Result<RecordingSession> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active session"))
            if (pendingSamples.isNotEmpty()) {
                sessionRepository.insertSamplesBatch(pendingSamples.toList())
                pendingSamples.clear()
            }
            droppedSamplesCount = droppedSamples.coerceAtLeast(0L)
            val failed = session.copy(
                endUtcMillis = System.currentTimeMillis(),
                status = SessionStatus.FAILED,
                qualityStatus = SessionQualityStatus.INSUFFICIENT,
                interruptionReason = reason,
                updatedAt = System.currentTimeMillis(),
            )
            sessionRepository.updateSession(failed)
            _activeSession.value = failed
            _sessionStatus.value = SessionStatus.FAILED
            automationCoordinator?.resetSessionState()
            Result.success(failed)
        }
    }

    override suspend fun recoverSession(sessionId: String): Result<RecordingSession> = mutex.withLock {
        withContext(dispatcher) {
            val session = sessionRepository.getSessionById(sessionId)
                ?: return@withContext Result.failure(IllegalArgumentException("Session $sessionId not found"))

            val recovered = if (session.status == SessionStatus.RECORDING) {
                session
            } else {
                session.withStatus(SessionStatus.RECORDING)
            }
            sessionRepository.updateSession(recovered)
            _activeSession.value = recovered
            _sessionStatus.value = SessionStatus.RECORDING
            reconstructCounters(sessionId)
            Result.success(recovered)
        }
    }

    private suspend fun reconstructCounters(sessionId: String) {
        val dbPreds = sessionRepository.getPredictionsForSession(sessionId).sortedBy { it.timestampMillis }
        totalPredictionsCount = dbPreds.size
        restPredictionsCount = dbPreds.count { it.predictedClass == ActivityClass.REST }
        restlessPredictionsCount = dbPreds.count { it.predictedClass == ActivityClass.RESTLESS }
        activePredictionsCount = dbPreds.count { it.predictedClass == ActivityClass.ACTIVE }

        var epCount = 0
        var inMove = false
        for (p in dbPreds) {
            if (p.predictedClass.isMovement) {
                if (!inMove) {
                    epCount++
                    inMove = true
                }
            } else {
                inMove = false
            }
        }
        movementEpisodeCount = epCount
        lastWasMovement = inMove
    }

    override suspend fun discardSession(): Result<Unit> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value ?: return@withContext Result.success(Unit)
            sessionRepository.deleteSession(session.sessionId)
            pendingSamples.clear()
            totalPredictionsCount = 0
            restPredictionsCount = 0
            restlessPredictionsCount = 0
            activePredictionsCount = 0
            movementEpisodeCount = 0
            lastWasMovement = false
            _activeSession.value = null
            _sessionStatus.value = SessionStatus.IDLE
            automationCoordinator?.resetSessionState()
            Result.success(Unit)
        }
    }
}
