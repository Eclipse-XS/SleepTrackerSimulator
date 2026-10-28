package com.example.sleeptrackersimulator.smart

import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.ml.ActivityClass
import com.example.sleeptrackersimulator.ml.ActivityPrediction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BackgroundAutomationCoordinatorTest {

    private class FakeSmartRuleRepository(
        private val rules: List<SmartRule>,
    ) : SmartRuleRepository {
        override fun observeAll(): Flow<List<SmartRule>> = flowOf(rules)
        override fun observeEnabled(): Flow<List<SmartRule>> = flowOf(rules.filter { it.enabled })
        override fun observeRecentExecutions(limit: Int): Flow<List<SmartRuleExecutionEntity>> = flowOf(emptyList())
        override suspend fun save(rule: SmartRule) {}
        override suspend fun delete(id: String) {}
        override suspend fun record(execution: SmartRuleExecutionEntity) {}
        override suspend fun updateOutcome(executionId: String, result: String, failureReason: String?, actuatorStatus: String, bleStatus: String, cloudStatus: String) {}
        override suspend fun updateCloudStatus(executionId: String, status: String) {}
    }

    private class FakeSmartRuleEventCoordinator : SmartRuleEventCoordinator {
        val prepared = mutableListOf<SmartRuleExecutionEntity>()
        val finalized = mutableListOf<String>()

        override suspend fun prepare(execution: SmartRuleExecutionEntity, draftPayload: SmartRuleEventPayload): RuleEventPreparation {
            prepared.add(execution)
            return RuleEventPreparation(execution, true)
        }

        override suspend fun markActionInProgress(executionId: String) {}

        override suspend fun finalizeEvent(
            executionId: String,
            result: String,
            failureReason: String?,
            actuatorStatus: String,
            bleStatus: String,
            finalPayload: SmartRuleEventPayload,
        ): RuleEventFinalization {
            finalized.add(executionId)
            return RuleEventFinalization("SYNCED")
        }

        override suspend fun reconcile(createdBefore: Long): RuleEventRecoveryReport {
            return RuleEventRecoveryReport(0, 0, 0)
        }
    }

    @Test
    fun testSensorSampleTriggersVibrationRule() = runTest {
        val rule = SmartRule(
            id = "rule-accel-x",
            name = "High X Motion Alert",
            enabled = true,
            source = RuleSource.ACCELEROMETER_X,
            operator = RuleOperator.GREATER_THAN,
            threshold = 5.0f,
            targetClass = null,
            action = RuleAction.VIBRATION_PULSE,
            cooldownMillis = 1000L,
            requiredConsecutiveMatches = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        val vibrationCount = AtomicInteger(0)
        val eventCoordinator = FakeSmartRuleEventCoordinator()
        val coordinator = DefaultBackgroundAutomationCoordinator(
            repository = FakeSmartRuleRepository(listOf(rule)),
            eventCoordinator = eventCoordinator,
            vibration = {
                vibrationCount.incrementAndGet()
                "VIBRATION_EXECUTED"
            },
            bleAlert = { "BLE_OK" },
        )

        val highXSample = RawSensorSample(
            sampleId = 1L,
            sessionId = "s1",
            sourceId = "accel",
            elapsedRealtimeNanos = 100L,
            utcTimestampMillis = 1000L,
            x = 8.5f,
            y = 0.0f,
            z = 9.81f,
            sensorAccuracy = 3,
            sourceKind = SourceKind.PHONE_REAL,
        )

        coordinator.evaluateSensorSample(highXSample, "s1")

        assertEquals(1, vibrationCount.get())
        assertEquals(1, eventCoordinator.prepared.size)
        assertEquals("rule-accel-x", eventCoordinator.prepared.first().ruleId)
    }

    @Test
    fun testPredictionTriggersBleAlertRule() = runTest {
        val rule = SmartRule(
            id = "rule-ml-active",
            name = "Active Sleep Wearable Alert",
            enabled = true,
            source = RuleSource.ML_CLASS,
            operator = RuleOperator.EQUALS,
            threshold = null,
            targetClass = "ACTIVE",
            action = RuleAction.MOCK_BLE_ALERT,
            cooldownMillis = 1000L,
            requiredConsecutiveMatches = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        val bleAlertCount = AtomicInteger(0)
        val eventCoordinator = FakeSmartRuleEventCoordinator()
        val coordinator = DefaultBackgroundAutomationCoordinator(
            repository = FakeSmartRuleRepository(listOf(rule)),
            eventCoordinator = eventCoordinator,
            vibration = { "VIBRATION_OK" },
            bleAlert = {
                bleAlertCount.incrementAndGet()
                "BLE_ALERT_SENT"
            },
        )

        val activePrediction = ActivityPrediction(
            predictionId = "pred-active-1",
            windowEndTimestampNanos = 200L,
            activity = ActivityClass.ACTIVE,
            confidence = 0.94f,
            probabilities = mapOf(ActivityClass.ACTIVE to 0.94f),
            inferenceNanos = 5000L,
        )

        coordinator.evaluatePrediction(activePrediction, "s1")

        assertEquals(1, bleAlertCount.get())
        assertEquals(1, eventCoordinator.prepared.size)
        assertEquals("rule-ml-active", eventCoordinator.prepared.first().ruleId)
    }
}
