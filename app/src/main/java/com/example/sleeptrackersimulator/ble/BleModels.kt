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

data class HeartRateMeasurement(val beatsPerMinute: Int, val sequence: Long)

enum class DeviceCommand { START_STREAM, STOP_STREAM, REQUEST_STATUS }

sealed interface BleOperationResult {
    data class Success(val message: String) : BleOperationResult
    data class Error(val message: String) : BleOperationResult
}

object HeartRateProfile {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    val SERVICE_UUID: UUID = standardUuid("180d")
    val MEASUREMENT_UUID: UUID = standardUuid("2a37")
    val BODY_SENSOR_LOCATION_UUID: UUID = standardUuid("2a38")
    const val SERVICE_SHORT_ID = "180D"
    const val MEASUREMENT_SHORT_ID = "2A37"
    const val BODY_SENSOR_LOCATION_SHORT_ID = "2A38"

    private fun standardUuid(shortId: String): UUID = UUID.fromString("0000$shortId$BASE_SUFFIX")
}
