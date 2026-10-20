package com.example.sleeptrackersimulator.e2e

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleOperationResult
import com.example.sleeptrackersimulator.ble.MockBleConnector
import com.example.sleeptrackersimulator.ble.MockBleScanner
import com.example.sleeptrackersimulator.cloud.ActiveCloudSession
import com.example.sleeptrackersimulator.cloud.FIREBASE_DATABASE_URL
import com.example.sleeptrackersimulator.cloud.FirebaseAnonymousAuthRepository
import com.example.sleeptrackersimulator.cloud.FirebaseCloudRepository
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.SmartRuleEventPayload
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import com.example.sleeptrackersimulator.smart.RoomSmartRuleEventCoordinator
import com.example.sleeptrackersimulator.smart.RuleAction
import com.example.sleeptrackersimulator.smart.RuleOperator
import com.example.sleeptrackersimulator.smart.RuleSource
import com.example.sleeptrackersimulator.smart.SmartRule
import com.example.sleeptrackersimulator.smart.SmartRuleEngine
import com.example.sleeptrackersimulator.smart.SmartRuleExecutionEntity
import com.example.sleeptrackersimulator.smart.SmartRuleInput
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class CorrelatedPipelineE2ETest {
    @Test fun sensorThroughTfliteRuleRoomBleOutboxAndFirebaseUsesOneEventId() {
        if (InstrumentationRegistry.getArguments().getString("firebaseE2E") != "true") return
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val auth = FirebaseAuth.getInstance().apply { useEmulator("10.0.2.2", 9099) }
        val firebase = FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL).apply { useEmulator("10.0.2.2", 9000) }
        val uid = FirebaseAnonymousAuthRepository(auth).ensureAuthenticated()
        val sessionId = "e2e-${UUID.randomUUID()}"
        val executionId = UUID.randomUUID().toString()
        val database = Room.inMemoryDatabaseBuilder(context, SleepTrackerDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ble = MockBleConnector(
            scanner = MockBleScanner(discoveryDelayMillis = 1), scope = scope,
            connectionDelayMillis = 1, notificationIntervalMillis = 60_000,
        )
        try {
            ble.startScan()
            val device = (ble.state.first { it is BleConnectionState.DeviceFound } as BleConnectionState.DeviceFound).devices.single()
            ble.connect(device)
            ble.state.first { it is BleConnectionState.Connected }

            val timestampNanos = 9_000_000L
            val window = Array(20) { index ->
                val wave = sin(index * 1.7).toFloat() * 2.8f
                floatArrayOf(wave, -wave * .6f, 9.81f + wave * .4f)
            }
            val prediction = ActivityClassifier(context).use { it.classify(window, timestampNanos) }
            val rule = SmartRule(
                id = "rule-active", name = "Active movement alert", enabled = true,
                source = RuleSource.ML_CLASS, operator = RuleOperator.EQUALS, threshold = null,
                targetClass = "ACTIVE", action = RuleAction.MOCK_BLE_ALERT, cooldownMillis = 0,
                requiredConsecutiveMatches = 1, createdAt = 1, updatedAt = 1,
            )
            val movement = MovementAnalysis(SensorSample(0f, 0f, 9.81f, timestampNanos), 2.8f, MovementState.MOVEMENT)
            val decision = SmartRuleEngine().evaluate(rule, SmartRuleInput(movement, prediction), 1, prediction.predictionId)
            assertTrue(decision.triggered)

            val store = RoomCloudStateStore(database.cloudOutboxDao())
            store.saveActiveSession(ActiveCloudSession(sessionId, 1, uid))
            val coordinator = RoomSmartRuleEventCoordinator(database.smartRuleEventCoordinatorDao())
            val initial = payload(executionId, decision.sourceValue, timestampNanos, prediction.predictionId, prediction.confidence, "PENDING")
            coordinator.prepare(
                SmartRuleExecutionEntity(
                    executionId, rule.id, 1, decision.sourceValue, rule.action.name, "PENDING", null,
                    rule.name, sessionId, timestampNanos, prediction.predictionId, cloudStatus = "PREPARING",
                    triggerSource = rule.source.name, mlClass = prediction.activity.name, mlConfidence = prediction.confidence,
                ),
                initial,
            )
            coordinator.markActionInProgress(executionId)
            val ack = (ble.sendAlert(executionId) as BleOperationResult.Success).message
            val finalized = payload(executionId, decision.sourceValue, timestampNanos, prediction.predictionId, prediction.confidence, ack)
            coordinator.finalizeEvent(executionId, "SUCCESS", null, "NOT_REQUESTED", ack, finalized)

            val operation = requireNotNull(store.peek())
            assertEquals("rule-$executionId", operation.operationId)
            val cloud = FirebaseCloudRepository(firebase)
            cloud.createSession(uid, sessionId, "sleep-tracker-simulator", 1)
            cloud.uploadRuleEvent(uid, sessionId, requireNotNull(operation.ruleEvent))
            database.smartRuleDao().updateCloudStatus(executionId, "SYNCED")
            store.removeFirst()
            cloud.uploadRuleEvent(uid, sessionId, finalized) // idempotent remote retry

            val remote = firebase.reference.child("users/$uid/sessions/$sessionId/ruleEvents/$executionId").get().await()
            assertEquals(executionId, remote.child("eventId").getValue(String::class.java))
            assertEquals(ack, remote.child("bleDeliveryResult").getValue(String::class.java))
            assertEquals(1L, ble.alertState.value.revision)
            assertEquals(0, store.pendingCount)
            assertEquals("SYNCED", database.smartRuleDao().observeRecentExecutions(1).first().single().cloudStatus)
            Log.i("E2E_EVIDENCE", "executionId=$executionId ruleId=${rule.id} sensorNs=$timestampNanos predictionId=${prediction.predictionId} bleAck=$ack outboxId=rule-$executionId firebase=/users/$uid/sessions/$sessionId/ruleEvents/$executionId cloud=SYNCED")
        } finally {
            runCatching { firebase.reference.child("users/$uid/sessions/$sessionId").removeValue().await() }
            ble.close()
            scope.cancel()
            database.close()
        }
        }
    }

    private fun payload(id: String, sourceValue: String, sensorNs: Long, predictionId: String, confidence: Float, ble: String) =
        SmartRuleEventPayload(
            eventId = id, ruleId = "rule-active", ruleName = "Active movement alert", timestamp = 1,
            source = RuleSource.ML_CLASS.name, sourceValue = sourceValue, sensorTimestampNanos = sensorNs,
            predictionId = predictionId, mlClass = "ACTIVE", mlConfidence = confidence,
            requestedAction = RuleAction.MOCK_BLE_ALERT.name, actuatorResult = "NOT_REQUESTED", bleDeliveryResult = ble,
        )
}
