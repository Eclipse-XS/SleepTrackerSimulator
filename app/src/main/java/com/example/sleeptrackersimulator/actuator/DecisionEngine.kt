package com.example.sleeptrackersimulator.actuator

import com.example.sleeptrackersimulator.core.model.MovementState

enum class Decision { NONE, TRIGGER_MOVEMENT_ALERT }

class DecisionEngine(
    val movementThreshold: Float = DEFAULT_MOVEMENT_ALERT_THRESHOLD,
) {
    private var aboveThreshold = false

    init { require(movementThreshold >= 0f) }

    fun evaluate(movementIntensity: Float, movementState: MovementState): Decision {
        val currentlyAbove = movementState == MovementState.MOVEMENT &&
            movementIntensity >= movementThreshold
        val decision = if (currentlyAbove && !aboveThreshold) {
            Decision.TRIGGER_MOVEMENT_ALERT
        } else {
            Decision.NONE
        }
        aboveThreshold = currentlyAbove
        return decision
    }

    fun reset() {
        aboveThreshold = false
    }

    companion object {
        const val DEFAULT_MOVEMENT_ALERT_THRESHOLD = 0.65f
    }
}
