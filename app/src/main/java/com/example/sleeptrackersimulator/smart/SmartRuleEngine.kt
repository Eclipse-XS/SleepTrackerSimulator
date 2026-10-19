package com.example.sleeptrackersimulator.smart

import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.ml.ActivityPrediction

data class SmartRuleInput(val movement: MovementAnalysis, val prediction: ActivityPrediction?)
data class SmartRuleDecision(val rule: SmartRule, val matched: Boolean, val triggered: Boolean, val sourceValue: String, val reason: String)

class SmartRuleEngine {
    private data class RuntimeState(var consecutive: Int = 0, var armed: Boolean = true, var lastTriggeredAt: Long = Long.MIN_VALUE)
    private val states = mutableMapOf<String, RuntimeState>()

    fun evaluate(rule: SmartRule, input: SmartRuleInput, nowElapsedMillis: Long): SmartRuleDecision {
        rule.validate()
        val value = when (rule.source) {
            RuleSource.ACCELEROMETER_X -> input.movement.sample.x
            RuleSource.ACCELEROMETER_Y -> input.movement.sample.y
            RuleSource.ACCELEROMETER_Z -> input.movement.sample.z
            RuleSource.MOVEMENT_INTENSITY -> input.movement.motionIntensity
            RuleSource.ML_CLASS -> null
        }
        val sourceValue = value?.toString() ?: input.prediction?.activity?.name.orEmpty()
        val matched = if (rule.source == RuleSource.ML_CLASS) {
            input.prediction?.activity?.name == rule.targetClass
        } else compare(requireNotNull(value), requireNotNull(rule.threshold), rule.operator)
        val state = states.getOrPut(rule.id) { RuntimeState() }
        if (!rule.enabled || !matched) {
            state.consecutive = 0
            state.armed = true
            return SmartRuleDecision(rule, matched, false, sourceValue, if (!rule.enabled) "disabled" else "condition_false_rearmed")
        }
        state.consecutive++
        if (state.consecutive < rule.requiredConsecutiveMatches) return SmartRuleDecision(rule, true, false, sourceValue, "awaiting_consecutive_matches")
        val cooldownPassed = state.lastTriggeredAt == Long.MIN_VALUE || nowElapsedMillis - state.lastTriggeredAt >= rule.cooldownMillis
        if (!state.armed) return SmartRuleDecision(rule, true, false, sourceValue, "already_triggered_until_rearm")
        if (!cooldownPassed) return SmartRuleDecision(rule, true, false, sourceValue, "cooldown")
        state.armed = false
        state.lastTriggeredAt = nowElapsedMillis
        return SmartRuleDecision(rule, true, true, sourceValue, "triggered")
    }

    fun remove(ruleId: String) { states.remove(ruleId) }
    fun clear() = states.clear()

    private fun compare(value: Float, threshold: Float, operator: RuleOperator) = when (operator) {
        RuleOperator.GREATER_THAN -> value > threshold
        RuleOperator.GREATER_OR_EQUAL -> value >= threshold
        RuleOperator.LESS_THAN -> value < threshold
        RuleOperator.LESS_OR_EQUAL -> value <= threshold
        RuleOperator.EQUALS -> false
    }
}
