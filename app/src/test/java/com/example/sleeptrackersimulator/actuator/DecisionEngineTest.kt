package com.example.sleeptrackersimulator.actuator

import com.example.sleeptrackersimulator.core.model.MovementState
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionEngineTest {
    private val engine = DecisionEngine(movementThreshold = 0.65f)

    @Test
    fun `below threshold does nothing`() {
        assertEquals(Decision.NONE, engine.evaluate(.64f, MovementState.MOVEMENT))
    }

    @Test
    fun `threshold crossing triggers alert`() {
        engine.evaluate(.4f, MovementState.MOVEMENT)
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.8f, MovementState.MOVEMENT))
    }

    @Test
    fun `staying above threshold does not spam`() {
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.8f, MovementState.MOVEMENT))
        assertEquals(Decision.NONE, engine.evaluate(.9f, MovementState.MOVEMENT))
    }

    @Test
    fun `dropping below threshold rearms engine`() {
        engine.evaluate(.8f, MovementState.MOVEMENT)
        engine.evaluate(.2f, MovementState.STILL)
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.8f, MovementState.MOVEMENT))
    }

    @Test
    fun `second crossing triggers again`() {
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.7f, MovementState.MOVEMENT))
        assertEquals(Decision.NONE, engine.evaluate(.8f, MovementState.MOVEMENT))
        assertEquals(Decision.NONE, engine.evaluate(.1f, MovementState.STILL))
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.7f, MovementState.MOVEMENT))
    }

    @Test
    fun `exact threshold triggers because semantics are greater than or equal`() {
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.65f, MovementState.MOVEMENT))
    }

    @Test
    fun `reset restores initial armed state`() {
        engine.evaluate(.8f, MovementState.MOVEMENT)
        engine.reset()
        assertEquals(Decision.TRIGGER_MOVEMENT_ALERT, engine.evaluate(.8f, MovementState.MOVEMENT))
    }
}
