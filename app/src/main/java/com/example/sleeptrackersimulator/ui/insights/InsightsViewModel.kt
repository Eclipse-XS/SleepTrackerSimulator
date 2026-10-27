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
    val summaries: List<SessionSummary> = emptyList(),
    val averageLowMotionFraction: Float = 0.0f,
    val medianDurationMs: Long = 0L,
    val comparableSessionCount: Int = 0,
    val baseline: PersonalBaseline? = null,
    val latestComparison: BaselineComparison? = null,
    val isQualityGateSatisfied: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class InsightsViewModel(
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    init {
        loadInsights()
    }

    fun refresh() = loadInsights()

    private fun loadInsights() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            runCatching {
            val summaries = analyticsRepository.getAllSummaries()
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
                summaries = summaries,
                averageLowMotionFraction = avgLowMotion,
                medianDurationMs = medianDuration,
                comparableSessionCount = comparable.size,
                baseline = baseline,
                latestComparison = comparison,
                isQualityGateSatisfied = comparable.size >= 3,
                isLoading = false,
            )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "Unable to load history",
                )
            }
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
