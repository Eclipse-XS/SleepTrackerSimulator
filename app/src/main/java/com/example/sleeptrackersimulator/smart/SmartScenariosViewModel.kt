package com.example.sleeptrackersimulator.smart

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ml.MlUiState
import com.example.sleeptrackersimulator.ui.dashboard.SensorUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SmartScenariosUiState(
    val rules: List<SmartRule> = emptyList(),
    val recentExecutions: List<SmartRuleExecutionEntity> = emptyList(),
    val visibleAlert: String? = null,
    val lastError: String? = null,
)

class SmartScenariosViewModel(
    sensorState: StateFlow<SensorUiState>,
    mlState: StateFlow<MlUiState>,
    private val repository: SmartRuleRepository,
    private val vibration: () -> String,
    private val bleAlert: () -> String,
    private val engine: SmartRuleEngine = SmartRuleEngine(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val localState = MutableStateFlow(SmartScenariosUiState())
    val state: StateFlow<SmartScenariosUiState> = combine(
        repository.observeAll(), repository.observeRecentExecutions(10), localState,
    ) { rules, executions, local -> local.copy(rules = rules, recentExecutions = executions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartScenariosUiState())

    init {
        viewModelScope.launch(dispatcher) {
            combine(sensorState, mlState, repository.observeEnabled()) { sensor, ml, rules -> Triple(sensor, ml, rules) }
                .collect { (sensor, ml, rules) ->
                    val movement = (sensor as? SensorUiState.Active)?.analysis ?: return@collect
                    val prediction = (ml as? MlUiState.Ready)?.prediction
                    val input = SmartRuleInput(movement, prediction)
                    rules.forEach { rule ->
                        val decision = engine.evaluate(rule, input, elapsedClock())
                        if (decision.triggered) execute(decision)
                    }
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
        runCatching { repository.delete(rule.id); engine.remove(rule.id) }
            .onFailure { error -> localState.update { it.copy(lastError = error.message ?: "Rule delete failed") } }
    }
    fun dismissVisibleAlert() = localState.update { it.copy(visibleAlert = null) }

    private suspend fun execute(decision: SmartRuleDecision) {
        val outcomes = mutableListOf<String>()
        var failure: String? = null
        when (decision.rule.action) {
            RuleAction.VIBRATION_PULSE -> outcomes += vibration()
            RuleAction.MOCK_BLE_ALERT -> outcomes += bleAlert()
            RuleAction.VISIBLE_ALERT -> localState.update { it.copy(visibleAlert = decision.rule.name) }
            RuleAction.CRITICAL_ALERT -> {
                outcomes += vibration()
                outcomes += bleAlert()
                localState.update { it.copy(visibleAlert = decision.rule.name) }
            }
        }
        if (outcomes.any { it.startsWith("ERROR") || it == "NOT_CONNECTED" || it == "APP_NOT_IN_FOREGROUND" }) {
            failure = outcomes.joinToString("; ")
        }
        runCatching {
            repository.record(
                SmartRuleExecutionEntity(
                    executionId = UUID.randomUUID().toString(), ruleId = decision.rule.id,
                    timestamp = wallClock(), sourceValue = decision.sourceValue,
                    action = decision.rule.action.name, result = if (failure == null) "SUCCESS" else "FAILED",
                    failureReason = failure,
                ),
            )
        }.onFailure { error -> localState.update { it.copy(lastError = error.message ?: "Execution persistence failed") } }
    }

    companion object {
        fun factory(
            sensorState: StateFlow<SensorUiState>, mlState: StateFlow<MlUiState>, repository: SmartRuleRepository,
            vibration: () -> String, bleAlert: () -> String,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SmartScenariosViewModel(sensorState, mlState, repository, vibration, bleAlert) as T
        }
    }
}
