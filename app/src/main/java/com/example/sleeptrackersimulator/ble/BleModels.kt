package com.example.sleeptrackersimulator.ble

import java.util.UUID

data class BleDevice(
    val name: String,
    val address: String,
    val type: String = "Heart Rate Sensor",
)

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data class DeviceFound(val devices: List<BleDevice>) : BleConnectionState
    data class Connecting(val device: BleDevice) : BleConnectionState
    data class Connected(val device: BleDevice) : BleConnectionState
    data class Disconnected(val device: BleDevice? = null) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

data class HeartRateMeasurement(
    val beatsPerMinute: Int,
    val sequence: Long,
    val timestampNanos: Long = System.nanoTime(),
)

enum class DeviceCommand { START_STREAM, STOP_STREAM, REQUEST_STATUS, ALERT }

sealed interface BleOperationResult {
    data class Success(val message: String) : BleOperationResult
    data class Error(val message: String) : BleOperationResult
}

object HeartRateProfile {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    val SERVICE_UUID: UUID = standardUuid("180d")
    val MEASUREMENT_UUID: UUID = standardUuid("2a37")
    val BODY_SENSOR_LOCATION_UUID: UUID = standardUuid("2a38")
    val BATTERY_SERVICE_UUID: UUID = standardUuid("180f")
    val BATTERY_LEVEL_UUID: UUID = standardUuid("2a19")
    val CCCD_UUID: UUID = standardUuid("2902")

    /** Custom writable command characteristic (Proprietary vendor UUID; not standard SIG). */
    val COMMAND_UUID: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")

    const val SERVICE_SHORT_ID = "180D"
    const val MEASUREMENT_SHORT_ID = "2A37"
    const val BODY_SENSOR_LOCATION_SHORT_ID = "2A38"
    const val BATTERY_SERVICE_SHORT_ID = "180F"
    const val BATTERY_LEVEL_SHORT_ID = "2A19"
    const val CCCD_SHORT_ID = "2902"
    const val COMMAND_ID = "7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e"

    private fun standardUuid(shortId: String): UUID = UUID.fromString("0000$shortId$BASE_SUFFIX")
}
