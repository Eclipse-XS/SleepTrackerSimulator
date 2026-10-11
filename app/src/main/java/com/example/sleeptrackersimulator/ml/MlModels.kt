package com.example.sleeptrackersimulator.ml

enum class MovementClass { STILL, MICRO_MOVEMENT, PRONOUNCED_MOVEMENT }

data class MlPrediction(
    val predictedClass: MovementClass,
    val probabilities: List<Float>,
    val inferenceMillis: Double,
    val timestampNanos: Long,
) {
    val confidence: Float get() = probabilities[predictedClass.ordinal]
}

sealed interface MlUiState {
    data object Loading : MlUiState
    data class Collecting(val samplesCollected: Int, val requiredSamples: Int) : MlUiState
    data class Ready(val prediction: MlPrediction) : MlUiState
    data class Error(val message: String) : MlUiState
    data object Unavailable : MlUiState
}
