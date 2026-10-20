package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.flow.StateFlow

interface IBleConnector {
    val state: StateFlow<BleConnectionState>
    val logs: StateFlow<List<String>>
    val heartRate: StateFlow<HeartRateMeasurement?>
    val heartRateHistory: StateFlow<List<Int>>
    val notificationsEnabled: StateFlow<Boolean>
    val alertState: StateFlow<DeviceAlertState>
    fun startScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun readBodySensorLocation(): BleOperationResult
    fun writeCommand(command: String): BleOperationResult
    fun sendAlert(eventId: String): BleOperationResult
    /** Lab-compatible textual transport contract. */
    fun sendData(data: String): BleOperationResult = writeCommand(data)
    fun close()
}

data class DeviceAlertState(
    val active: Boolean = false,
    val eventId: String? = null,
    val processedAtNanos: Long = 0L,
    val revision: Long = 0L,
)
