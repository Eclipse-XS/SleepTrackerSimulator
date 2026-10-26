package com.example.sleeptrackersimulator.core.model

import java.util.UUID

/**
 * Standard classification labels for movement and rest states.
 */
enum class ActivityClass {
    REST,
    RESTLESS,
    ACTIVE,
    UNCERTAIN;

    val isMovement: Boolean
        get() = this == RESTLESS || this == ACTIVE

    val isRest: Boolean
        get() = this == REST

    companion object {
        fun fromString(name: String?): ActivityClass =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNCERTAIN
    }
}

/**
 * Result of executing on-device inference on a feature window.
 */
data class Prediction(
    val predictionId: String = UUID.randomUUID().toString(),
    val windowId: String,
    val sessionId: String,
    val modelId: String,
    val predictedClass: ActivityClass,
    val probabilities: Map<ActivityClass, Float>,
    val confidence: Float,
    val inferenceDurationNs: Long,
    val timestampMillis: Long,
)

/**
 * Registry metadata defining a validated on-device ML model.
 */
data class ModelVersion(
    val modelId: String,
    val sha256Hex: String,
    val inputShape: List<Int>,
    val samplingRateHz: Int,
    val preprocessingVersion: Int,
    val classes: List<ActivityClass>,
    val description: String,
)
