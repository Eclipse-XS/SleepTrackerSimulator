@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.sleeptrackersimulator.ui.insights

import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.PersonalBaseline
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class InsightsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun demoAndRealMetricsNeverMixAndRefreshFromFlow() = runTest(dispatcher) {
        val real = summary("real", SourceKind.PHONE_REAL, 0.8f)
        val demo = summary("demo", SourceKind.SIMULATED, 0.5f)
        val unverified = summary("unknown", SourceKind.BLE_UNVERIFIED, 0.1f)
        val repository = FakeAnalyticsRepository(listOf(real, demo, unverified))
        val viewModel = InsightsViewModel(repository)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.realMetrics.sessionCount)
        assertEquals(1, viewModel.uiState.value.demoMetrics.sessionCount)
        assertEquals(1, viewModel.uiState.value.excludedUnverifiedCount)
        assertEquals(0.8f, viewModel.uiState.value.realMetrics.averageLowMotionFraction)
        assertEquals(0.5f, viewModel.uiState.value.demoMetrics.averageLowMotionFraction)

        repository.summaries.value = listOf(demo, summary("demo-2", SourceKind.SIMULATED, 0.7f))
        advanceUntilIdle()
        viewModel.selectMode(InsightsMode.DEMO)
        assertEquals(2, viewModel.uiState.value.selectedMetrics.sessionCount)
        assertEquals(0, viewModel.uiState.value.realMetrics.sessionCount)
        assertFalse(viewModel.uiState.value.isQualityGateSatisfied)
    }

    @Test fun oneRealSessionHasDescriptiveMetricsWithoutBaseline() = runTest(dispatcher) {
        val repository = FakeAnalyticsRepository(listOf(summary("real", SourceKind.PHONE_REAL, 0.75f)))
        val viewModel = InsightsViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.realMetrics.sessionCount)
        assertEquals(0.75f, viewModel.uiState.value.realMetrics.averageLowMotionFraction)
        assertFalse(viewModel.uiState.value.isQualityGateSatisfied)
    }

    private fun summary(id: String, source: SourceKind, lowMotion: Float) = SessionSummary(
        sessionId = id,
        observationDurationMs = 8 * 3_600_000L,
        validDurationMs = 8 * 3_600_000L,
        dataCoverageFraction = 0.95f,
        lowMotionFraction = lowMotion,
        movementEpisodeCount = 12,
        movementRatePerHour = 1.5f,
        averageHeartRateBpm = 62,
        sourceKind = source,
    )

    private class FakeAnalyticsRepository(initial: List<SessionSummary>) : AnalyticsRepository {
        val summaries = MutableStateFlow(initial)
        override fun observeAllSummaries(): Flow<List<SessionSummary>> = summaries
        override suspend fun getAllSummaries() = summaries.value
        override suspend fun getRecentSummaries(days: Int) = summaries.value
        override fun observeRecentSessions(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.example.sleeptrackersimulator.core.model.RecordingSession>())
        override suspend fun getSessionSummary(sessionId: String) = summaries.value.firstOrNull { it.sessionId == sessionId }
        override suspend fun getPersonalBaseline(sourceMode: SourceMode, placement: DevicePlacement): PersonalBaseline? = null
        override suspend fun recalculateBaseline(sourceMode: SourceMode, placement: DevicePlacement): PersonalBaseline? = null
        override suspend fun compareSessionWithBaseline(sessionId: String) = null
    }
}
