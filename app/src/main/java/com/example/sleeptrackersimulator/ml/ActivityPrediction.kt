package com.example.sleeptrackersimulator.ml

enum class ActivityClass { REST, RESTLESS, ACTIVE }

data class ActivityPrediction(
    val activity: ActivityClass,
    val confidence: Float,
    val probabilities: Map<ActivityClass, Float>,
    val inferenceNanos: Long,
)

sealed interface MlUiState {
    data object Collecting : MlUiState
    data class Ready(val prediction: ActivityPrediction) : MlUiState
    data class Error(val message: String) : MlUiState
}
