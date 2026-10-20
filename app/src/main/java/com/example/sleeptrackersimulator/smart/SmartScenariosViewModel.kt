package com.example.sleeptrackersimulator.smart

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.ml.ActivityPrediction
import com.example.sleeptrackersimulator.cloud.NoOpSmartRuleCloudEventSink
import com.example.sleeptrackersimulator.cloud.SmartRuleCloudEventSink
import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

data class SmartScenariosUiState(
    val rules: List<SmartRule> = emptyList(),
    val recentExecutions: List<SmartRuleExecutionEntity> = emptyList(),
    val visibleAlert: String? = null,
    val lastError: String? = null,
)

class SmartScenariosViewModel(
    analyses: Flow<MovementAnalysis>,
    predictions: Flow<ActivityPrediction>,
    private val repository: SmartRuleRepository,
    private val vibration: () -> String,
    private val bleAlert: (String) -> String,
    private val cloudEvents: SmartRuleCloudEventSink = NoOpSmartRuleCloudEventSink,
    private val engine: SmartRuleEngine = SmartRuleEngine(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private sealed interface EvaluationEvent {
        data class Sensor(val analysis: MovementAnalysis) : EvaluationEvent
        data class Prediction(val prediction: ActivityPrediction) : EvaluationEvent
    }

    private val localState = MutableStateFlow(SmartScenariosUiState())
    private val enabledRules = repository.observeEnabled()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val state: StateFlow<SmartScenariosUiState> = combine(
        repository.observeAll(), repository.observeRecentExecutions(10), localState,
    ) { rules, executions, local -> local.copy(rules = rules, recentExecutions = executions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartScenariosUiState())

    init {
        val events = Channel<EvaluationEvent>(Channel.BUFFERED)
        viewModelScope.launch(dispatcher) { analyses.collect { events.send(EvaluationEvent.Sensor(it)) } }
        viewModelScope.launch(dispatcher) { predictions.collect { events.send(EvaluationEvent.Prediction(it)) } }
        viewModelScope.launch(dispatcher) {
            var latestMovement: MovementAnalysis? = null
            for (event in events) {
                when (event) {
                    is EvaluationEvent.Sensor -> latestMovement = event.analysis
                    is EvaluationEvent.Prediction -> Unit
                }
                val movement = latestMovement ?: continue
                val prediction = (event as? EvaluationEvent.Prediction)?.prediction
                val source = if (event is EvaluationEvent.Sensor) RuleSource.ML_CLASS else null
                val inputId = when (event) {
                    is EvaluationEvent.Sensor -> "sensor-${event.analysis.sample.timestampNanos}"
                    is EvaluationEvent.Prediction -> event.prediction.predictionId
                }
                enabledRules.value.asSequence()
                    .filter { rule -> if (source == null) rule.source == RuleSource.ML_CLASS else rule.source != source }
                    .forEach { rule ->
                        val decision = engine.evaluate(rule, SmartRuleInput(movement, prediction), elapsedClock(), inputId)
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
        val executionId = UUID.randomUUID().toString()
        val sessionId = cloudEvents.activeSessionId()
        try {
            repository.record(
                SmartRuleExecutionEntity(
                    executionId = executionId, ruleId = decision.rule.id,
                    timestamp = wallClock(), sourceValue = decision.sourceValue,
                    action = decision.rule.action.name, result = "PENDING", failureReason = null,
                    ruleNameSnapshot = decision.rule.name,
                    sessionId = sessionId,
                    sensorTimestampNanos = decision.sensorTimestampNanos,
                    predictionId = decision.prediction?.predictionId,
                ),
            )
        } catch (error: Exception) {
            localState.update { it.copy(lastError = error.message ?: "Execution intent persistence failed") }
            return
        }
        var actuatorStatus = "NOT_REQUESTED"
        var bleStatus = "NOT_REQUESTED"
        var failure: String? = null
        when (decision.rule.action) {
            RuleAction.VIBRATION_PULSE -> actuatorStatus = vibration()
            RuleAction.MOCK_BLE_ALERT -> bleStatus = bleAlert(executionId)
            RuleAction.VISIBLE_ALERT -> localState.update { it.copy(visibleAlert = decision.rule.name) }
            RuleAction.CRITICAL_ALERT -> {
                actuatorStatus = vibration()
                bleStatus = bleAlert(executionId)
                localState.update { it.copy(visibleAlert = decision.rule.name) }
            }
        }
        val outcomes = listOf(actuatorStatus, bleStatus).filterNot { it == "NOT_REQUESTED" }
        if (outcomes.any { it.startsWith("ERROR") || it == "NOT_CONNECTED" || it == "APP_NOT_IN_FOREGROUND" }) {
            failure = outcomes.joinToString("; ")
        }
        val cloudResult = cloudEvents.enqueue(
            SmartRuleEventPayload(
                eventId = executionId,
                ruleId = decision.rule.id,
                ruleName = decision.rule.name,
                timestamp = wallClock(),
                source = decision.rule.source.name,
                sourceValue = decision.sourceValue,
                sensorTimestampNanos = decision.sensorTimestampNanos,
                predictionId = decision.prediction?.predictionId,
                mlClass = decision.prediction?.activity?.name,
                mlConfidence = decision.prediction?.confidence,
                requestedAction = decision.rule.action.name,
                actuatorResult = actuatorStatus,
                bleDeliveryResult = bleStatus,
            ),
        )
        val combinedFailure = listOfNotNull(failure, cloudResult.error).joinToString("; ").ifBlank { null }
        runCatching {
            repository.updateOutcome(
                executionId,
                if (failure == null) "SUCCESS" else "FAILED",
                combinedFailure,
                actuatorStatus,
                bleStatus,
                cloudResult.status,
            )
        }.onFailure { error -> localState.update { it.copy(lastError = error.message ?: "Execution outcome persistence failed") } }
    }

    companion object {
        fun factory(
            analyses: Flow<MovementAnalysis>, predictions: Flow<ActivityPrediction>, repository: SmartRuleRepository,
            vibration: () -> String, bleAlert: (String) -> String,
            cloudEvents: SmartRuleCloudEventSink = NoOpSmartRuleCloudEventSink,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SmartScenariosViewModel(analyses, predictions, repository, vibration, bleAlert, cloudEvents) as T
        }
    }
}
