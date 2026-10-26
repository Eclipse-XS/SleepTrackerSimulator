package com.example.sleeptrackersimulator.domain.replay

import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.smart.RuleAction
import com.example.sleeptrackersimulator.smart.RuleOperator
import com.example.sleeptrackersimulator.smart.RuleSource
import com.example.sleeptrackersimulator.smart.SmartRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayEngineTest {

    private val replayEngine = ReplayEngine()

    @Test
    fun replayProcessesWindowsAndEvaluatesSmartRules() = runTest {
        val samples = mutableListOf<RawSensorSample>()

        // Window 1: Stillness (20 samples)
        for (i in 0 until 20) {
            samples.add(
                RawSensorSample(
                    sampleId = i.toLong(),
                    sessionId = "test-session",
                    sourceId = "phone",
                    elapsedRealtimeNanos = i * 50_000_000L,
                    utcTimestampMillis = 1000L + i * 50L,
                    x = 0.01f,
                    y = 0.02f,
                    z = 9.81f,
                    sourceKind = SourceKind.PHONE_REAL,
                ),
            )
        }

        // Window 2: Heavy movement (20 samples)
        for (i in 20 until 40) {
            val sign = if (i % 2 == 0) 1.0f else -1.0f
            samples.add(
                RawSensorSample(
                    sampleId = i.toLong(),
                    sessionId = "test-session",
                    sourceId = "phone",
                    elapsedRealtimeNanos = i * 50_000_000L,
                    utcTimestampMillis = 1000L + i * 50L,
                    x = sign * 2.5f,
                    y = sign * 1.8f,
                    z = 9.81f + sign * 1.5f,
                    sourceKind = SourceKind.PHONE_REAL,
                ),
            )
        }

        // Window 3: Heavy movement (20 samples)
        for (i in 40 until 60) {
            val sign = if (i % 2 == 0) 1.0f else -1.0f
            samples.add(
                RawSensorSample(
                    sampleId = i.toLong(),
                    sessionId = "test-session",
                    sourceId = "phone",
                    elapsedRealtimeNanos = i * 50_000_000L,
                    utcTimestampMillis = 1000L + i * 50L,
                    x = sign * 3.0f,
                    y = sign * 2.0f,
                    z = 9.81f,
                    sourceKind = SourceKind.PHONE_REAL,
                ),
            )
        }

        val rule = SmartRule(
            id = "rule-intense-movement",
            name = "Vigorous Movement Detection",
            enabled = true,
            source = RuleSource.MOVEMENT_INTENSITY,
            operator = RuleOperator.GREATER_THAN,
            threshold = 1.0f,
            targetClass = null,
            action = RuleAction.VIBRATION_PULSE,
            cooldownMillis = 0L,
            requiredConsecutiveMatches = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        val report = replayEngine.replay(samples, listOf(rule), speed = ReplaySpeed.INSTANT)

        assertEquals(60, report.totalSamplesProcessed)
        assertEquals(3, report.windowsGenerated)
        assertTrue("Expected rule to trigger on intense movement windows", report.rulesTriggeredCount >= 1)
        assertEquals("rule-intense-movement", report.triggeredDecisions.first().rule.id)
    }
}
