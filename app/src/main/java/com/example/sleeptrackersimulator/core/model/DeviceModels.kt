package com.example.sleeptrackersimulator.core.model

import java.util.UUID

enum class DeviceCapability {
    HEART_RATE,
    ACCELEROMETER,
    STEP_COUNT,
    BATTERY_LEVEL,
    VIBRATION_ALERT,
    REMOTE_COMMANDS;
}

enum class DeviceConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    UNSUPPORTED;

    val isConnected: Boolean
        get() = this == CONNECTED

    val isBusy: Boolean
        get() = this == SCANNING || this == CONNECTING || this == RECONNECTING
}

data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val sourceKind: SourceKind,
    val capabilities: Set<DeviceCapability>,
    val rssi: Int? = null,
    val batteryLevel: Int? = null,
    val lastSeenUtcMillis: Long = System.currentTimeMillis(),
)

data class DeviceCommand(
    val commandId: String = UUID.randomUUID().toString(),
    val targetDeviceId: String,
    val action: String,
    val payload: ByteArray? = null,
    val timestampUtcMillis: Long = System.currentTimeMillis(),
    val expiresAtUtcMillis: Long = System.currentTimeMillis() + 60_000L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceCommand
        return commandId == other.commandId
    }

    override fun hashCode(): Int = commandId.hashCode()
}

data class CommandResult(
    val commandId: String,
    val success: Boolean,
    val statusMessage: String,
    val executionDurationNs: Long = 0,
    val executedAtUtcMillis: Long = System.currentTimeMillis(),
)
