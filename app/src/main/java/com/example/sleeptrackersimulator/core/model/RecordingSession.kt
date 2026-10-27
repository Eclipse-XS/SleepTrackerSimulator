package com.example.sleeptrackersimulator.core.model

import java.util.UUID

/**
 * State machine for recording sessions.
 * At any point in time, at most ONE session can be in an active state.
 */
enum class SessionStatus {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    INTERRUPTED,
    RECOVERABLE,
    FINALIZING,
    COMPLETED,
    FAILED;

    val isActive: Boolean
        get() = this == PREPARING || this == RECORDING || this == PAUSED

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED

    fun canTransitionTo(next: SessionStatus): Boolean = when (this) {
        IDLE -> next == PREPARING || next == RECORDING
        PREPARING -> next == RECORDING || next == FAILED || next == IDLE
        RECORDING -> next == RECORDING || next == PAUSED || next == FINALIZING || next == INTERRUPTED || next == FAILED
        PAUSED -> next == RECORDING || next == FINALIZING || next == INTERRUPTED || next == FAILED
        INTERRUPTED -> next == RECORDING || next == RECOVERABLE || next == FINALIZING || next == FAILED
        RECOVERABLE -> next == RECORDING || next == FINALIZING || next == FAILED
        FINALIZING -> next == COMPLETED || next == FAILED
        COMPLETED, FAILED -> next == IDLE
    }
}

/**
 * Operational mode defining which source is active for recording.
 */
enum class SourceMode {
    PHONE_ONLY,
    WEARABLE_ONLY,
    COMBINED,
    DEMO,
    REPLAY;

    val requiresPhoneSensor: Boolean
        get() = this == PHONE_ONLY || this == COMBINED

    val requiresBleConnection: Boolean
        get() = this == WEARABLE_ONLY || this == COMBINED

    val isSynthetic: Boolean
        get() = this == DEMO || this == REPLAY
}

enum class SessionQualityStatus {
    EXCELLENT,
    GOOD,
    DEGRADED,
    INSUFFICIENT;

    companion object {
        fun fromCoverage(coverageFraction: Float): SessionQualityStatus = when {
            coverageFraction >= 0.95f -> EXCELLENT
            coverageFraction >= 0.85f -> GOOD
            coverageFraction >= 0.60f -> DEGRADED
            else -> INSUFFICIENT
        }
    }
}

/**
 * Core domain representation of a sleep/movement observation session.
 */
data class RecordingSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val startUtcMillis: Long,
    val endUtcMillis: Long? = null,
    val startElapsedRealtimeNanos: Long,
    val status: SessionStatus = SessionStatus.PREPARING,
    val sourceMode: SourceMode = SourceMode.PHONE_ONLY,
    val devicePlacement: DevicePlacement = DevicePlacement.MATTRESS,
    val requestedSamplingHz: Int = 20,
    val modelVersion: String? = null,
    val dataCoverageFraction: Float = 1.0f,
    val qualityStatus: SessionQualityStatus = SessionQualityStatus.EXCELLENT,
    val interruptionReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val durationMillis: Long
        get() = (endUtcMillis ?: System.currentTimeMillis()) - startUtcMillis

    val isCompleted: Boolean
        get() = status == SessionStatus.COMPLETED

    fun withStatus(newStatus: SessionStatus, interruptionReason: String? = null): RecordingSession {
        require(status.canTransitionTo(newStatus)) {
            "Illegal session state transition from $status to $newStatus for session $sessionId"
        }
        return copy(
            status = newStatus,
            interruptionReason = interruptionReason ?: this.interruptionReason,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun completed(endUtcMillis: Long = System.currentTimeMillis(), coverageFraction: Float = dataCoverageFraction): RecordingSession {
        return copy(
            endUtcMillis = endUtcMillis,
            status = SessionStatus.COMPLETED,
            dataCoverageFraction = coverageFraction,
            qualityStatus = SessionQualityStatus.fromCoverage(coverageFraction),
            updatedAt = System.currentTimeMillis(),
        )
    }
}
