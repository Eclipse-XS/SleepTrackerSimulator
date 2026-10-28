package com.example.sleeptrackersimulator.domain.analytics

import com.example.sleeptrackersimulator.core.model.BaselineComparison
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.PersonalBaseline
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceMode
import kotlinx.coroutines.flow.Flow

interface AnalyticsRepository {
    fun observeRecentSessions(limit: Int = 30): Flow<List<RecordingSession>>
    suspend fun getSessionSummary(sessionId: String): SessionSummary?
    suspend fun getRecentSummaries(days: Int = 30): List<SessionSummary>
    suspend fun getAllSummaries(): List<SessionSummary>
    fun observeAllSummaries(): Flow<List<SessionSummary>>

    suspend fun getPersonalBaseline(
        sourceMode: SourceMode = SourceMode.PHONE_ONLY,
        placement: DevicePlacement = DevicePlacement.MATTRESS,
    ): PersonalBaseline?

    suspend fun recalculateBaseline(
        sourceMode: SourceMode = SourceMode.PHONE_ONLY,
        placement: DevicePlacement = DevicePlacement.MATTRESS,
    ): PersonalBaseline?

    suspend fun compareSessionWithBaseline(sessionId: String): BaselineComparison?
}
