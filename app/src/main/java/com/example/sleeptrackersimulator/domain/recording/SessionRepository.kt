package com.example.sleeptrackersimulator.domain.recording

import com.example.sleeptrackersimulator.core.model.FeatureWindow
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.Prediction
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionSummary
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun observeActiveSession(): Flow<RecordingSession?>
    suspend fun getActiveSession(): RecordingSession?
    fun observeAllSessions(): Flow<List<RecordingSession>>
    suspend fun getSessionById(sessionId: String): RecordingSession?

    suspend fun saveSession(session: RecordingSession)
    suspend fun updateSession(session: RecordingSession)
    suspend fun deleteSession(sessionId: String)

    suspend fun insertSamplesBatch(samples: List<RawSensorSample>)
    suspend fun getSamplesForSession(sessionId: String): List<RawSensorSample>

    suspend fun insertHeartRateSamples(samples: List<HeartRateSample>)
    suspend fun getHeartRateForSession(sessionId: String): List<HeartRateSample>

    suspend fun insertFeatureWindows(windows: List<FeatureWindow>)
    suspend fun getFeatureWindows(sessionId: String): List<FeatureWindow>

    suspend fun insertPredictions(predictions: List<Prediction>)
    suspend fun getPredictionsForSession(sessionId: String): List<Prediction>

    suspend fun saveSessionSummary(summary: SessionSummary)
    suspend fun saveSessionSummaryAndOutbox(
        summary: SessionSummary,
        completedSession: RecordingSession,
        outboxOperation: com.example.sleeptrackersimulator.cloud.PendingCloudOperation?,
    ): Boolean {
        saveSessionSummary(summary)
        updateSession(completedSession)
        return false
    }
    suspend fun getSessionSummary(sessionId: String): SessionSummary?
    fun observeAllSummaries(): Flow<List<SessionSummary>>
}
