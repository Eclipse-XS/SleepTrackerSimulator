package com.example.sleeptrackersimulator.domain.replay

import com.example.sleeptrackersimulator.core.model.RawSensorSample
import com.example.sleeptrackersimulator.domain.signalprocessing.FeatureExtractor
import com.example.sleeptrackersimulator.domain.signalprocessing.SlidingWindowSegmenter
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import com.example.sleeptrackersimulator.smart.SmartRule
import com.example.sleeptrackersimulator.smart.SmartRuleDecision
import com.example.sleeptrackersimulator.smart.SmartRuleEngine
import com.example.sleeptrackersimulator.smart.SmartRuleInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ReplayReport(
    val totalSamplesProcessed: Int,
    val windowsGenerated: Int,
    val predictionsCount: Int,
    val rulesTriggeredCount: Int,
    val triggeredDecisions: List<SmartRuleDecision>,
    val executionDurationMillis: Long,
)

enum class ReplaySpeed(val speedMultiplier: Double) {
    INSTANT(0.0), // Fast-forward, no delay
    SPEED_10X(10.0),
    REALTIME(1.0),
}

/**
 * Deterministic offline replay engine to test signals, ML classification,
 * and Smart Automation rules without requiring physical sensor hardware.
 */
class ReplayEngine(
    private val activityClassifier: ActivityClassifier? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun replay(
        samples: List<RawSensorSample>,
        rules: List<SmartRule> = emptyList(),
        speed: ReplaySpeed = ReplaySpeed.INSTANT,
    ): ReplayReport = withContext(dispatcher) {
        val startedAt = System.currentTimeMillis()
        val windowSegmenter = SlidingWindowSegmenter(windowSize = 20, stride = 20)
        val ruleEngine = SmartRuleEngine()

        val triggeredDecisions = mutableListOf<SmartRuleDecision>()
        var windowCount = 0
        var predictionCount = 0

        var previousSampleTimestamp = samples.firstOrNull()?.elapsedRealtimeNanos ?: 0L

        for ((index, sample) in samples.withIndex()) {
            if (speed != ReplaySpeed.INSTANT && speed.speedMultiplier > 0.0 && index > 0) {
                val deltaNanos = sample.elapsedRealtimeNanos - previousSampleTimestamp
                val delayMillis = ((deltaNanos / 1_000_000.0) / speed.speedMultiplier).toLong()
                if (delayMillis in 1..2000) {
                    delay(delayMillis)
                }
            }
            previousSampleTimestamp = sample.elapsedRealtimeNanos

            val completedWindows = windowSegmenter.addSample(sample)
            for (window in completedWindows) {
                windowCount++
                val featureWindow = FeatureExtractor.extractFeatures(window, expectedSampleCount = 20)

                // Optional ML classification
                val prediction = if (activityClassifier != null) {
                    val inputWindow = Array(20) { i ->
                        floatArrayOf(window[i].x, window[i].y, window[i].z)
                    }
                    activityClassifier.classify(inputWindow, window.last().elapsedRealtimeNanos).also {
                        predictionCount++
                    }
                } else null

                // Smart rule evaluation
                val dummyMovement = com.example.sleeptrackersimulator.core.model.MovementAnalysis(
                    sample = com.example.sleeptrackersimulator.core.model.SensorSample(
                        x = sample.x,
                        y = sample.y,
                        z = sample.z,
                        timestampNanos = sample.elapsedRealtimeNanos,
                    ),
                    motionIntensity = featureWindow.motionRms,
                    state = if (featureWindow.motionRms < 0.2f) {
                        com.example.sleeptrackersimulator.core.model.MovementState.STILL
                    } else {
                        com.example.sleeptrackersimulator.core.model.MovementState.MOVEMENT
                    },
                )

                val input = SmartRuleInput(
                    movement = dummyMovement,
                    prediction = prediction,
                )

                for (rule in rules) {
                    val decision = ruleEngine.evaluate(
                        rule = rule,
                        input = input,
                        nowElapsedMillis = featureWindow.startUtcMillis,
                        inputId = "replay-w-$windowCount-${rule.id}",
                    )
                    if (decision.triggered) {
                        triggeredDecisions.add(decision)
                    }
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startedAt

        ReplayReport(
            totalSamplesProcessed = samples.size,
            windowsGenerated = windowCount,
            predictionsCount = predictionCount,
            rulesTriggeredCount = triggeredDecisions.size,
            triggeredDecisions = triggeredDecisions,
            executionDurationMillis = elapsed,
        )
    }
}
