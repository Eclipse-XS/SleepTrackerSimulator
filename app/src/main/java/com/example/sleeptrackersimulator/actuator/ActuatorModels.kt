package com.example.sleeptrackersimulator.actuator

data class RemoteControlState(
    val vibrationEnabled: Boolean = false,
    val flashlightEnabled: Boolean = false,
)

sealed interface RemoteControlEvent {
    data class Updated(val controls: RemoteControlState) : RemoteControlEvent
    data class Error(val message: String) : RemoteControlEvent
}

enum class ActuatorOperationStatus { SUCCESS, UNAVAILABLE, ERROR }

data class ActuatorResult(
    val status: ActuatorOperationStatus,
    val message: String,
) {
    val successful: Boolean get() = status == ActuatorOperationStatus.SUCCESS
}

enum class TriggerSource { LOCAL_AUTOMATIC, MANUAL, REMOTE }

enum class ActuatorAction {
    NONE,
    VIBRATION_PULSE,
    VIBRATION_ENABLED,
    VIBRATION_DISABLED,
    FLASHLIGHT_ENABLED,
    FLASHLIGHT_DISABLED,
}

data class ActuatorUiState(
    val vibratorAvailable: Boolean = false,
    val vibrationEnabled: Boolean = false,
    val flashlightAvailable: Boolean = false,
    val flashlightEnabled: Boolean = false,
    val automaticControlEnabled: Boolean = true,
    val remoteListenerConnected: Boolean = false,
    val lastTriggerSource: TriggerSource? = null,
    val lastActionTriggered: ActuatorAction = ActuatorAction.NONE,
    val lastError: String? = null,
)
