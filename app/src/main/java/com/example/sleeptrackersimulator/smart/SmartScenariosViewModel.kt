package com.example.sleeptrackersimulator.smart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.ml.ActivityPrediction
import com.example.sleeptrackersimulator.cloud.CloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.NoOpCloudRecoveryScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

data class SmartScenariosUiState(
    val rules: List<SmartRule> = emptyList(),
    val recentExecutions: List<SmartRuleExecutionEntity> = emptyList(),
    val visibleAlert: String? = null,
    val lastError: String? = null,
)

/**
 * Pure UI state holder for Smart Scenarios management.
 * All runtime rule evaluations and physical actuations are centralized
 * exclusively in [BackgroundAutomationCoordinator] to eliminate architectural
 * dualities and prevent duplicate rule triggers.
 */
class SmartScenariosViewModel(
    private val repository: SmartRuleRepository,
    private val eventCoordinator: SmartRuleEventCoordinator,
    private val recoveryScheduler: CloudRecoveryScheduler = NoOpCloudRecoveryScheduler,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val localState = MutableStateFlow(SmartScenariosUiState())

    val state: StateFlow<SmartScenariosUiState> = combine(
        repository.observeAll(),
        repository.observeRecentExecutions(10),
        localState,
    ) { rules, executions, local ->
        local.copy(rules = rules, recentExecutions = executions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartScenariosUiState())

    init {
        val startupBoundary = wallClock()
        viewModelScope.launch {
            runCatching { eventCoordinator.reconcile(startupBoundary) }
                .onSuccess { report -> if (report.recovered > 0) recoveryScheduler.schedule() }
                .onFailure { error ->
                    localState.update { it.copy(lastError = error.message ?: "Execution recovery failed") }
                }
        }
    }

    fun save(rule: SmartRule) = viewModelScope.launch {
        runCatching { repository.save(rule) }
            .onSuccess { localState.update { it.copy(lastError = null) } }
            .onFailure { error -> localState.update { it.copy(lastError = error.message ?: "Rule save failed") } }
    }

    fun setEnabled(rule: SmartRule, enabled: Boolean) = save(rule.copy(enabled = enabled, updatedAt = wallClock()))

    fun delete(rule: SmartRule) = viewModelScope.launch {
        runCatching { repository.delete(rule.id) }
            .onFailure { error -> localState.update { it.copy(lastError = error.message ?: "Rule delete failed") } }
    }

    fun dismissVisibleAlert() = localState.update { it.copy(visibleAlert = null) }

    companion object {
        fun factory(
            analyses: Flow<MovementAnalysis> = emptyFlow(),
            predictions: Flow<ActivityPrediction> = emptyFlow(),
            repository: SmartRuleRepository,
            vibration: () -> String = { "MOCK" },
            bleAlert: (String) -> String = { "MOCK" },
            eventCoordinator: SmartRuleEventCoordinator,
            recoveryScheduler: CloudRecoveryScheduler = NoOpCloudRecoveryScheduler,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SmartScenariosViewModel(
                    repository = repository,
                    eventCoordinator = eventCoordinator,
                    recoveryScheduler = recoveryScheduler,
                ) as T
        }
    }
}
