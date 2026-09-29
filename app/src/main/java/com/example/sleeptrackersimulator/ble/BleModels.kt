package com.example.sleeptrackersimulator.ble

import java.util.UUID

data class BleDevice(val name: String, val address: String)

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data class DeviceFound(val devices: List<BleDevice>) : BleConnectionState
    data class Connecting(val device: BleDevice) : BleConnectionState
    data class Connected(val device: BleDevice) : BleConnectionState
    data class Disconnected(val device: BleDevice? = null) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

object BleProfile {
    val SERVICE_UUID: UUID = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
}
