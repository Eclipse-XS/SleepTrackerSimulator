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
    private val activityClassifier: ActivityClassifier?,
    private val sampleValidator: SampleValidator = SampleValidator(),
    private val cloudRepository: CloudRepository? = null,
    private val authRepository: AuthRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    private val _activeSession = MutableStateFlow<RecordingSession?>(null)
    override val activeSession: StateFlow<RecordingSession?> = _activeSession.asStateFlow()

    private val _sessionStatus = MutableStateFlow(SessionStatus.IDLE)
    override val sessionStatus: StateFlow<SessionStatus> = _sessionStatus.asStateFlow()

    private var currentClockAnchor: ClockAnchor? = null
    private val windowSegmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)
    private val pendingSamples = mutableListOf<RawSensorSample>()
    private val predictionsHistory = mutableListOf<Prediction>()

    init {
        // Check for existing active or interrupted session on initialization
        scope.launch {
            val existing = sessionRepository.getActiveSession()
            if (existing != null) {
                _activeSession.value = existing
                _sessionStatus.value = existing.status
            }
        }
    }

    override suspend fun startSession(
        sourceMode: SourceMode,
        placement: DevicePlacement,
        samplingHz: Int,
    ): Result<RecordingSession> = mutex.withLock {
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
                modelVersion = activityClassifier?.let { "activity-v2.1" },
                dataCoverageFraction = 1.0f,
                qualityStatus = SessionQualityStatus.EXCELLENT,
            )

            windowSegmenter.clear()
            pendingSamples.clear()
            predictionsHistory.clear()

            sessionRepository.saveSession(session)
            _activeSession.value = session
            _sessionStatus.value = SessionStatus.RECORDING

            Result.success(session)
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
            }
            is com.example.sleeptrackersimulator.domain.signalprocessing.SampleValidationResult.Invalid -> {
                // Drop corrupted sample silently or report to quality metrics
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
        sessionRepository.insertHeartRateSamples(listOf(sample))
    }

    private suspend fun processWindow(samples: List<RawSensorSample>, session: RecordingSession) {
        val featureWindow = FeatureExtractor.extractFeatures(samples, expectedSampleCount = 20)
        sessionRepository.insertFeatureWindows(listOf(featureWindow))

        // On-device ML classification
        if (activityClassifier != null) {
            val inputWindow = Array(20) { i ->
                floatArrayOf(samples[i].x, samples[i].y, samples[i].z)
            }
            val result = activityClassifier.classify(inputWindow)
            val durationNs = result.inferenceNanos

            val activityClass = when (result.activity.name) {
                "REST" -> ActivityClass.REST
                "RESTLESS" -> ActivityClass.RESTLESS
                "ACTIVE" -> ActivityClass.ACTIVE
                else -> ActivityClass.UNCERTAIN
            }

            val domainProbabilities = result.probabilities.mapKeys { (k, _) ->
                ActivityClass.fromString(k.name)
            }

            val prediction = Prediction(
                windowId = featureWindow.windowId,
                sessionId = session.sessionId,
                modelId = "activity-v2.1",
                predictedClass = activityClass,
                probabilities = domainProbabilities,
                confidence = result.confidence,
                inferenceDurationNs = durationNs,
                timestampMillis = featureWindow.endUtcMillis,
            )

            predictionsHistory.add(prediction)
            sessionRepository.insertPredictions(listOf(prediction))
        }
    }

    override suspend fun pauseSession(): Result<Unit> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value
                ?: return@withContext Result.failure(IllegalStateException("No active session"))
            if (session.status != SessionStatus.RECORDING) {
                return@withContext Result.failure(IllegalStateException("Cannot pause from status ${session.status}"))
            }

            val paused = session.withStatus(SessionStatus.PAUSED)
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
            val observationDurationMs = maxOf(1000L, endUtc - session.startUtcMillis)

            // Compute summary metrics
            val windows = sessionRepository.getFeatureWindows(session.sessionId)
            val predictions = if (predictionsHistory.isNotEmpty()) predictionsHistory.toList()
            else sessionRepository.getPredictionsForSession(session.sessionId)

            val lowMotionCount = predictions.count { it.predictedClass == ActivityClass.REST }
            val lowMotionFraction = if (predictions.isNotEmpty()) {
                lowMotionCount.toFloat() / predictions.size
            } else 1.0f

            // Calculate movement episode transitions
            var movementEpisodes = 0
            var inMovement = false
            for (p in predictions) {
                if (p.predictedClass.isMovement) {
                    if (!inMovement) {
                        movementEpisodes++
                        inMovement = true
                    }
                } else {
                    inMovement = false
                }
            }

            val hours = maxOf(0.016f, observationDurationMs / 3_600_000.0f)
            val movementRatePerHour = movementEpisodes / hours

            val validWindowsCount = windows.count { it.qualityScore >= 0.5f }
            val coverageFraction = if (windows.isNotEmpty()) {
                validWindowsCount.toFloat() / windows.size
            } else 1.0f

            val summary = SessionSummary(
                sessionId = session.sessionId,
                observationDurationMs = observationDurationMs,
                validDurationMs = (coverageFraction * observationDurationMs).toLong(),
                dataCoverageFraction = coverageFraction,
                lowMotionFraction = lowMotionFraction,
                movementEpisodeCount = movementEpisodes,
                movementRatePerHour = movementRatePerHour,
                sourceKind = when (session.sourceMode) {
                    SourceMode.PHONE_ONLY -> SourceKind.PHONE_REAL
                    SourceMode.WEARABLE_ONLY -> SourceKind.BLE_REAL
                    SourceMode.COMBINED -> SourceKind.PHONE_REAL
                    SourceMode.DEMO -> SourceKind.SIMULATED
                    SourceMode.REPLAY -> SourceKind.REPLAY
                },
                devicePlacement = session.devicePlacement,
                modelVersion = session.modelVersion ?: "heuristic-baseline",
                calculatedAtUtcMillis = endUtc,
            )

            sessionRepository.saveSessionSummary(summary)

            val completed = session.completed(endUtcMillis = endUtc, coverageFraction = coverageFraction)
            sessionRepository.updateSession(completed)

            // Optional upload to cloud if authenticated and cloud repository available
            val uid = authRepository?.currentUid
            if (uid != null && cloudRepository != null) {
                runCatching {
                    val restlessCount = predictions.count { it.predictedClass == ActivityClass.RESTLESS }
                    val activeCount = predictions.count { it.predictedClass == ActivityClass.ACTIVE }
                    val total = maxOf(1, predictions.size)
                    val hrSamples = sessionRepository.getHeartRateForSession(session.sessionId)
                    val meanHr = if (hrSamples.isNotEmpty()) hrSamples.map { it.bpm }.average().toInt() else null

                    val payload = SessionSummaryPayload(
                        sessionId = summary.sessionId,
                        startedAt = session.startUtcMillis,
                        endedAt = endUtc,
                        observationDurationMs = summary.observationDurationMs,
                        lowMotionFraction = summary.lowMotionFraction,
                        restlessFraction = restlessCount.toFloat() / total,
                        activeFraction = activeCount.toFloat() / total,
                        meanHeartRateBpm = meanHr,
                        sourceMode = session.sourceMode.name,
                        placement = summary.devicePlacement.name,
                        qualityStatus = session.qualityStatus.name,
                        schemaVersion = 2,
                    )
                    cloudRepository.uploadSessionSummary(uid, payload)
                }
            }

            _activeSession.value = null
            _sessionStatus.value = SessionStatus.IDLE

            Result.success(completed)
        }
    }

    override suspend fun recoverSession(sessionId: String): Result<RecordingSession> = mutex.withLock {
        withContext(dispatcher) {
            val session = sessionRepository.getSessionById(sessionId)
                ?: return@withContext Result.failure(IllegalArgumentException("Session $sessionId not found"))

            val recovered = session.withStatus(SessionStatus.RECORDING)
            sessionRepository.updateSession(recovered)
            _activeSession.value = recovered
            _sessionStatus.value = SessionStatus.RECORDING
            Result.success(recovered)
        }
    }

    override suspend fun discardSession(): Result<Unit> = mutex.withLock {
        withContext(dispatcher) {
            val session = _activeSession.value ?: return@withContext Result.success(Unit)
            sessionRepository.deleteSession(session.sessionId)
            pendingSamples.clear()
            predictionsHistory.clear()
            _activeSession.value = null
            _sessionStatus.value = SessionStatus.IDLE
            Result.success(Unit)
        }
    }
}
