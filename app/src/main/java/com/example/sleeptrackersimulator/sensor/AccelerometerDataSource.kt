package com.example.sleeptrackersimulator.sensor

import com.example.sleeptrackersimulator.core.model.SensorSample
import kotlinx.coroutines.flow.Flow

sealed interface AccelerometerEvent {
    data class Measurement(val sample: SensorSample) : AccelerometerEvent
    data object Unavailable : AccelerometerEvent
    data class Error(val message: String) : AccelerometerEvent
}

interface AccelerometerDataSource {
    /**
     * Registers the Android listener when collected and unregisters it immediately when collection stops.
     */
    fun observe(): Flow<AccelerometerEvent>
}
