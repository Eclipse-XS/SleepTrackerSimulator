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
        assertFalse(engine.evaluate(rule, input(11f), 1, "1").triggered)
        assertFalse(engine.evaluate(rule, input(11f), 2, "2").triggered)
        assertTrue(engine.evaluate(rule, input(11f), 3, "3").triggered)
        assertFalse(engine.evaluate(rule, input(12f), 10_000, "4").triggered)
        assertFalse(engine.evaluate(rule, input(9f), 10_001, "5").triggered)
        assertFalse(engine.evaluate(rule, input(11f), 10_002, "6").triggered)
        assertFalse(engine.evaluate(rule, input(11f), 10_003, "7").triggered)
        assertTrue(engine.evaluate(rule, input(11f), 10_004, "8").triggered)
    }

    @Test fun `strict threshold does not match equality`() {
        assertFalse(SmartRuleEngine().evaluate(numeric(required = 1), input(10f), 1, "1").matched)
    }

    @Test fun `ml class rule uses real prediction class`() {
        val rule = numeric(required = 1).copy(source = RuleSource.ML_CLASS, operator = RuleOperator.EQUALS, threshold = null, targetClass = "ACTIVE")
        val prediction = ActivityPrediction(
            predictionId = "prediction-1",
            windowEndTimestampNanos = 1L,
            activity = ActivityClass.ACTIVE,
            confidence = .9f,
            probabilities = emptyMap(),
            inferenceNanos = 1L,
        )
        assertTrue(SmartRuleEngine().evaluate(rule, input(0f, prediction), 1, prediction.predictionId).triggered)
    }

    @Test fun `same physical input cannot advance consecutive count twice`() {
        val engine = SmartRuleEngine(); val rule = numeric(required = 2)
        assertFalse(engine.evaluate(rule, input(11f), 1, "sensor-1").triggered)
        assertFalse(engine.evaluate(rule, input(11f), 2, "sensor-1").triggered)
        assertTrue(engine.evaluate(rule, input(11f), 3, "sensor-2").triggered)
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
