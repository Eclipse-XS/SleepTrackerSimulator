package com.example.sleeptrackersimulator.actuator

interface SmartActuator {
    val vibratorAvailable: Boolean
    val flashlightAvailable: Boolean

    fun triggerVibrationPulse(): ActuatorResult
    fun setPersistentVibration(enabled: Boolean): ActuatorResult
    fun setFlashlightEnabled(enabled: Boolean): ActuatorResult
    fun stopAll()
}
