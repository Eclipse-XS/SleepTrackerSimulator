package com.example.sleeptrackersimulator.ui.dashboard

import com.example.sleeptrackersimulator.core.model.MovementAnalysis

sealed interface SensorUiState {
    data object Initializing : SensorUiState
    data class Active(val analysis: MovementAnalysis) : SensorUiState
    data object Unavailable : SensorUiState
    data class Error(val message: String) : SensorUiState
}
