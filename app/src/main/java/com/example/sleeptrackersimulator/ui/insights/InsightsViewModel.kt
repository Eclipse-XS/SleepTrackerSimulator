package com.example.sleeptrackersimulator.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.BaselineComparison
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.PersonalBaseline
import com.example.sleeptrackersimulator.core.model.SessionSummary
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.domain.analytics.AnalyticsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

enum class InsightsMode { REAL, DEMO }

data class InsightsMetrics(
    val sessionCount: Int = 0,
    val averageDurationMs: Long = 0L,
    val averageLowMotionFraction: Float = 0f,
    val averageMovementEpisodes: Float = 0f,
    val averageHeartRateBpm: Float? = null,
    val averageCoverageFraction: Float = 0f,
    val nightlyTrend: List<SessionSummary> = emptyList(),
)

data class InsightsUiState(
    val summaries: List<SessionSummary> = emptyList(),
    val selectedMode: InsightsMode = InsightsMode.REAL,
    val realMetrics: InsightsMetrics = InsightsMetrics(),
    val demoMetrics: InsightsMetrics = InsightsMetrics(),
    val excludedUnverifiedCount: Int = 0,
    val comparableSessionCount: Int = 0,
    val baseline: PersonalBaseline? = null,
    val latestComparison: BaselineComparison? = null,
    val isQualityGateSatisfied: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedMetrics: InsightsMetrics get() = if (selectedMode == InsightsMode.DEMO) demoMetrics else realMetrics
}

internal fun calculateMetrics(summaries: List<SessionSummary>): InsightsMetrics {
    if (summaries.isEmpty()) return InsightsMetrics()
    val heartRates = summaries.mapNotNull { it.averageHeartRateBpm }
    return InsightsMetrics(
        sessionCount = summaries.size,
        averageDurationMs = summaries.map { it.observationDurationMs }.average().toLong(),
        averageLowMotionFraction = summaries.map { it.lowMotionFraction }.average().toFloat(),
        averageMovementEpisodes = summaries.map { it.movementEpisodeCount }.average().toFloat(),
        averageHeartRateBpm = heartRates.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        averageCoverageFraction = summaries.map { it.dataCoverageFraction }.average().toFloat(),
        nightlyTrend = summaries.sortedBy { it.calculatedAtUtcMillis },
    )
}

class InsightsViewModel(private val analyticsRepository: AnalyticsRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(InsightsUiState(isLoading = true))
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()
    private var observationJob: Job? = null

    init { observeInsights() }

    fun selectMode(mode: InsightsMode) { _uiState.value = _uiState.value.copy(selectedMode = mode) }
    fun refresh() = observeInsights()

    private fun observeInsights() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            analyticsRepository.observeAllSummaries()
                .catch { error -> _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.message ?: "Unable to load history") }
                .collectLatest { summaries ->
                    val real = summaries.filter { it.sourceKind.isRealData }
                    val demo = summaries.filter { it.sourceKind.isSimulated }
                    val comparable = real.filter { it.isComparableWithBaseline }
                    val baseline = analyticsRepository.getPersonalBaseline(SourceMode.PHONE_ONLY, DevicePlacement.MATTRESS)
                    val comparison = comparable.firstOrNull()?.let { baseline?.compare(it) }
                    _uiState.value = _uiState.value.copy(
                        summaries = summaries,
                        realMetrics = calculateMetrics(real),
                        demoMetrics = calculateMetrics(demo),
                        excludedUnverifiedCount = summaries.size - real.size - demo.size,
                        comparableSessionCount = comparable.size,
                        baseline = baseline,
                        latestComparison = comparison,
                        isQualityGateSatisfied = comparable.size >= 3,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
        }
    }

    companion object {
        fun factory(analyticsRepository: AnalyticsRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = InsightsViewModel(analyticsRepository) as T
        }
    }
}
