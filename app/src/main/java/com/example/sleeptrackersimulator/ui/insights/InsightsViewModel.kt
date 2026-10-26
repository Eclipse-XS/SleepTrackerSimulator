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
import kotlinx.coroutines.launch

data class InsightsUiState(
    val timeRangeDays: Int = 7,
    val summaries: List<SessionSummary> = emptyList(),
    val averageLowMotionFraction: Float = 0.0f,
    val medianDurationMs: Long = 0L,
    val comparableSessionCount: Int = 0,
    val baseline: PersonalBaseline? = null,
    val latestComparison: BaselineComparison? = null,
    val isQualityGateSatisfied: Boolean = false,
    val isLoading: Boolean = false,
)

class InsightsViewModel(
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    init {
        loadInsights(7)
    }

    fun setTimeRange(days: Int) {
        loadInsights(days)
    }

    private fun loadInsights(days: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, timeRangeDays = days)

            val summaries = analyticsRepository.getRecentSummaries(days)
            val comparable = summaries.filter { it.isComparableWithBaseline }

            val avgLowMotion = if (comparable.isNotEmpty()) {
                comparable.map { it.lowMotionFraction }.average().toFloat()
            } else 0.0f

            val durations = comparable.map { it.observationDurationMs }.sorted()
            val medianDuration = if (durations.isNotEmpty()) durations[durations.size / 2] else 0L

            val baseline = analyticsRepository.getPersonalBaseline(
                sourceMode = SourceMode.PHONE_ONLY,
                placement = DevicePlacement.MATTRESS,
            )

            val latestSummary = comparable.firstOrNull()
            val comparison = latestSummary?.let { s -> baseline?.compare(s) }

            _uiState.value = InsightsUiState(
                timeRangeDays = days,
                summaries = summaries,
                averageLowMotionFraction = avgLowMotion,
                medianDurationMs = medianDuration,
                comparableSessionCount = comparable.size,
                baseline = baseline,
                latestComparison = comparison,
                isQualityGateSatisfied = comparable.size >= 3,
                isLoading = false,
            )
        }
    }

    companion object {
        fun factory(analyticsRepository: AnalyticsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return InsightsViewModel(analyticsRepository) as T
                }
            }
    }
}
