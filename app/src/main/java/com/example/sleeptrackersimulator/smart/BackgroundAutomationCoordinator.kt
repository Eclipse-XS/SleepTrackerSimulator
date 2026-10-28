package com.example.sleeptrackersimulator.smart

import android.os.SystemClock
import com.example.sleeptrackersimulator.cloud.CloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.NoOpCloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import com.example.sleeptrackersimulator.ml.ActivityPrediction
import com.example.sleeptrackersimulator.sensor.MovementClassifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface BackgroundAutomationCoordinator {
    suspend fun evaluateSensorSample(sample: RawSensorSample, sessionId: String? = null)
    suspend fun evaluatePrediction(prediction: ActivityPrediction, sessionId: String? = null)
}

class DefaultBackgroundAutomationCoordinator(
    private val repository: SmartRuleRepository,
    private val eventCoordinator: SmartRuleEventCoordinator,
    private val vibration: () -> String,
    private val bleAlert: (String) -> String,
    private val recoveryScheduler: CloudRecoveryScheduler = NoOpCloudRecoveryScheduler,
    private val engine: SmartRuleEngine = SmartRuleEngine(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(System.currentTimeMillis())
    },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BackgroundAutomationCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val executionMutex = Mutex()
    private val movementClassifier = MovementClassifier()

    private val enabledRules = repository.observeEnabled()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @Volatile
    private var latestMovement: MovementAnalysis? = null

    override suspend fun evaluateSensorSample(sample: RawSensorSample, sessionId: String?) {
        val converted = SensorSample(
            x = sample.x,
            y = sample.y,
            z = sample.z,
            timestampNanos = sample.elapsedRealtimeNanos,
        )
        val movement = movementClassifier.analyze(converted)
        latestMovement = movement

        val rules = enabledRules.value.filter { it.source != RuleSource.ML_CLASS }
        if (rules.isEmpty()) return

        val inputId = "sensor-${sample.elapsedRealtimeNanos}"
        val nowElapsed = elapsedClock()
        val ruleInput = SmartRuleInput(movement = movement, prediction = null)

        for (rule in rules) {
            val decision = engine.evaluate(rule, ruleInput, nowElapsed, inputId)
            if (decision.triggered) {
                executeDecision(decision, sessionId)
            }
        }
    }

    override suspend fun evaluatePrediction(prediction: ActivityPrediction, sessionId: String?) {
        val rules = enabledRules.value.filter { it.source == RuleSource.ML_CLASS }
        if (rules.isEmpty()) return

        val movement = latestMovement ?: MovementAnalysis(
            sample = SensorSample(x = 0f, y = 0f, z = 9.81f, timestampNanos = prediction.windowEndTimestampNanos),
            motionIntensity = 0f,
            state = MovementState.STILL,
        )
        val inputId = prediction.predictionId
        val nowElapsed = elapsedClock()
        val ruleInput = SmartRuleInput(movement = movement, prediction = prediction)

        for (rule in rules) {
            val decision = engine.evaluate(rule, ruleInput, nowElapsed, inputId)
            if (decision.triggered) {
                executeDecision(decision, sessionId)
            }
        }
    }

    private suspend fun executeDecision(decision: SmartRuleDecision, sessionId: String?) = executionMutex.withLock {
        val executionId = UUID.randomUUID().toString()
        val eventTimestamp = wallClock()
        val draftPayload = SmartRuleEventPayload(
            eventId = executionId,
            ruleId = decision.rule.id,
            ruleName = decision.rule.name,
            timestamp = eventTimestamp,
            source = decision.rule.source.name,
            sourceValue = decision.sourceValue,
            sensorTimestampNanos = decision.sensorTimestampNanos,
            predictionId = decision.prediction?.predictionId,
            mlClass = decision.prediction?.activity?.name,
            mlConfidence = decision.prediction?.confidence,
            requestedAction = decision.rule.action.name,
            actuatorResult = "PENDING",
            bleDeliveryResult = "PENDING",
        )

        try {
            eventCoordinator.prepare(
                SmartRuleExecutionEntity(
                    executionId = executionId,
                    ruleId = decision.rule.id,
                    timestamp = eventTimestamp,
                    sourceValue = decision.sourceValue,
                    action = decision.rule.action.name,
                    result = "PENDING",
                    failureReason = null,
                    ruleNameSnapshot = decision.rule.name,
                    sessionId = sessionId,
                    sensorTimestampNanos = decision.sensorTimestampNanos,
                    predictionId = decision.prediction?.predictionId,
                    triggerSource = decision.rule.source.name,
                    mlClass = decision.prediction?.activity?.name,
                    mlConfidence = decision.prediction?.confidence,
                ),
                draftPayload,
            )
            eventCoordinator.markActionInProgress(executionId)
        } catch (e: Exception) {
            DiagnosticLog.event(
                tag = DiagnosticTags.LIFECYCLE,
                operation = "smart_rule_prepare_failed",
                result = DiagnosticResult.FAILED,
                error = e,
            )
            return@withLock
        }

        var actuatorStatus = "NOT_REQUESTED"
        var bleStatus = "NOT_REQUESTED"
        var failure: String? = null

        when (decision.rule.action) {
            RuleAction.VIBRATION_PULSE -> {
                actuatorStatus = vibration()
            }
            RuleAction.MOCK_BLE_ALERT -> {
                bleStatus = bleAlert(executionId)
            }
            RuleAction.VISIBLE_ALERT -> {
                // UI state will observe Room execution record
            }
            RuleAction.CRITICAL_ALERT -> {
                actuatorStatus = vibration()
                bleStatus = bleAlert(executionId)
            }
        }

        val outcomes = listOf(actuatorStatus, bleStatus).filterNot { it == "NOT_REQUESTED" }
        if (outcomes.any { it.startsWith("ERROR") || it == "NOT_CONNECTED" || it == "APP_NOT_IN_FOREGROUND" }) {
            failure = outcomes.joinToString("; ")
        }

        val finalPayload = draftPayload.copy(
            actuatorResult = actuatorStatus,
            bleDeliveryResult = bleStatus,
        )

        runCatching {
            eventCoordinator.finalizeEvent(
                executionId = executionId,
                result = if (failure == null) "SUCCESS" else "FAILED",
                failureReason = failure,
                actuatorStatus = actuatorStatus,
                bleStatus = bleStatus,
                finalPayload = finalPayload,
            )
        }.onSuccess { finalized ->
            if (finalized.cloudStatus == "PENDING") {
                recoveryScheduler.schedule()
            }
        }.onFailure { error ->
            DiagnosticLog.event(
                tag = DiagnosticTags.LIFECYCLE,
                operation = "smart_rule_finalize_failed",
                result = DiagnosticResult.FAILED,
                error = error,
            )
        }
    }
}
