package com.example.sleeptrackersimulator.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.history.SensorHistoryRepository
import com.example.sleeptrackersimulator.history.SensorRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags

data class AnalyticsUiState(
    val records: List<SensorRecord> = emptyList(),
    val recordCount: Int = 0,
    val actionCount: Int = 0,
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(
    repository: SensorHistoryRepository,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private var lastLoggedPointBucket = -1
    private val windowStart = flow {
        while (true) {
            emit((clock() - WINDOW_MILLIS).coerceAtLeast(1L))
            delay(WINDOW_REFRESH_MILLIS)
        }
    }

    val state = windowStart.flatMapLatest { sinceInclusive ->
        combine(
            repository.observeRecent(sinceInclusive, MAX_CHART_POINTS),
            repository.observeCount(sinceInclusive),
            repository.observeActionCount(sinceInclusive),
        ) { records, count, actionCount ->
            AnalyticsUiState(
                records = records,
                recordCount = count,
                actionCount = actionCount,
                loading = false,
            )
        }
    }.onStart {
        DiagnosticLog.event(DiagnosticTags.ANALYTICS, "loadAnalytics", DiagnosticResult.STARTED, details = mapOf("maxChartPoints" to MAX_CHART_POINTS))
    }.onEach { analytics ->
        val bucket = analytics.records.size / LOG_POINT_BUCKET_SIZE
        if (bucket != lastLoggedPointBucket || analytics.records.size == MAX_CHART_POINTS) {
            lastLoggedPointBucket = bucket
            DiagnosticLog.event(DiagnosticTags.ANALYTICS, "updateAnalytics", DiagnosticResult.SUCCESS, details = mapOf("displayedPoints" to analytics.records.size, "recordCount" to analytics.recordCount, "actionCount" to analytics.actionCount))
        }
    }.catch { error ->
        DiagnosticLog.event(DiagnosticTags.ANALYTICS, "loadAnalytics", DiagnosticResult.FAILED, details = mapOf("reason" to error.javaClass.simpleName), error = error)
        emit(
            AnalyticsUiState(
                loading = false,
                errorMessage = error.message ?: "Local database read failed",
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AnalyticsUiState(),
    )

    companion object {
        const val MAX_CHART_POINTS = 50
        const val WINDOW_MILLIS = 60 * 60 * 1_000L
        const val WINDOW_REFRESH_MILLIS = 60_000L
        private const val LOG_POINT_BUCKET_SIZE = 10

        fun factory(repository: SensorHistoryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AnalyticsViewModel::class.java))
                    return AnalyticsViewModel(repository) as T
                }
            }
    }
}
