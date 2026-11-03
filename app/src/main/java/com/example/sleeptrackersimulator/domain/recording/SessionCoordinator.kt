package com.example.sleeptrackersimulator.domain.recording

import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SourceMode
import kotlinx.coroutines.flow.StateFlow

enum class LiveInferenceStatus { UNAVAILABLE, COLLECTING, READY, PAUSED }

data class LiveInferenceState(
    val status: LiveInferenceStatus = LiveInferenceStatus.UNAVAILABLE,
    val activity: ActivityClass? = null,
    val confidence: Float? = null,
    val inferenceMode: com.example.sleeptrackersimulator.ml.InferenceMode? = null,
    val modelId: String? = null,
    val fallbackReason: String? = null,
    val qualityFlags: Set<String> = emptySet(),
    val lastPredictionUtcMillis: Long? = null,
    val collectedSamples: Int = 0,
    val requiredSamples: Int = 20,
)

/**
 * Top-level session orchestrator responsible for managing session lifecycle,
 * coordinating sensor ingestion, ML processing, Room persistence, and recovery.
 */
interface SessionCoordinator {
    val activeSession: StateFlow<RecordingSession?>
    val sessionStatus: StateFlow<SessionStatus>
    val liveInference: StateFlow<LiveInferenceState>

    suspend fun startSession(
        sourceMode: SourceMode = SourceMode.PHONE_ONLY,
        placement: DevicePlacement = DevicePlacement.MATTRESS,
        samplingHz: Int = 20,
    ): Result<RecordingSession>

    suspend fun pauseSession(): Result<Unit>
    suspend fun resumeSession(): Result<Unit>
    suspend fun stopSession(): Result<RecordingSession>
    suspend fun failSession(reason: String, droppedSamples: Long = 0): Result<RecordingSession>
    suspend fun reportDroppedSamples(count: Long)
    suspend fun recoverSession(sessionId: String): Result<RecordingSession>
    suspend fun discardSession(): Result<Unit>

    suspend fun ingestSensorEvent(
        sourceId: String,
        elapsedNanos: Long,
        x: Float,
        y: Float,
        z: Float,
        accuracy: Int,
        sourceKind: com.example.sleeptrackersimulator.core.model.SourceKind = com.example.sleeptrackersimulator.core.model.SourceKind.PHONE_REAL,
    )

    suspend fun ingestHeartRateSample(
        deviceId: String,
        bpm: Int,
        utcTimestampMillis: Long = System.currentTimeMillis(),
        timingUncertaintyMillis: Long = 0,
        sourceKind: com.example.sleeptrackersimulator.core.model.SourceKind = com.example.sleeptrackersimulator.core.model.SourceKind.BLE_UNVERIFIED,
    )
}
