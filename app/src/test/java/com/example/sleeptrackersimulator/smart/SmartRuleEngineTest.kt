package com.example.sleeptrackersimulator.smart

import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.ml.ActivityClass
import com.example.sleeptrackersimulator.ml.ActivityPrediction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRuleEngineTest {
    @Test fun `x greater than 10 requires three matches and triggers once until rearmed`() {
        val engine = SmartRuleEngine(); val rule = numeric(required = 3)
        assertFalse(engine.evaluate(rule, input(11f), 1).triggered)
        assertFalse(engine.evaluate(rule, input(11f), 2).triggered)
        assertTrue(engine.evaluate(rule, input(11f), 3).triggered)
        assertFalse(engine.evaluate(rule, input(12f), 10_000).triggered)
        assertFalse(engine.evaluate(rule, input(9f), 10_001).triggered)
        assertFalse(engine.evaluate(rule, input(11f), 10_002).triggered)
        assertFalse(engine.evaluate(rule, input(11f), 10_003).triggered)
        assertTrue(engine.evaluate(rule, input(11f), 10_004).triggered)
    }

    @Test fun `strict threshold does not match equality`() {
        assertFalse(SmartRuleEngine().evaluate(numeric(required = 1), input(10f), 1).matched)
    }

    @Test fun `ml class rule uses real prediction class`() {
        val rule = numeric(required = 1).copy(source = RuleSource.ML_CLASS, operator = RuleOperator.EQUALS, threshold = null, targetClass = "ACTIVE")
        val prediction = ActivityPrediction(ActivityClass.ACTIVE, .9f, emptyMap(), 1)
        assertTrue(SmartRuleEngine().evaluate(rule, input(0f, prediction), 1).triggered)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid categorical numeric combination is rejected`() {
        numeric(1).copy(source = RuleSource.ML_CLASS, targetClass = "ACTIVE").validate()
    }

    private fun numeric(required: Int) = SmartRule(
        "r", "X alert", true, RuleSource.ACCELEROMETER_X, RuleOperator.GREATER_THAN, 10f, null,
        RuleAction.VIBRATION_PULSE, 5_000, required, 1, 1,
    )
    private fun input(x: Float, prediction: ActivityPrediction? = null) = SmartRuleInput(
        MovementAnalysis(SensorSample(x, 0f, 9.81f, 1), 0f, MovementState.STILL), prediction,
    )
}
