package com.example.sleeptrackersimulator.actuator

interface SmartActuator {
    val vibratorAvailable: Boolean
    val flashlightAvailable: Boolean
    val amplitudeControlAvailable: Boolean get() = false
    val observedFlashlightEnabled: kotlinx.coroutines.flow.StateFlow<Boolean?>? get() = null

    fun triggerVibrationPulse(): ActuatorResult
    fun setPersistentVibration(enabled: Boolean): ActuatorResult
    fun setFlashlightEnabled(enabled: Boolean): ActuatorResult
    fun stopAll()
}
