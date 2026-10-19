package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.flow.StateFlow

interface IBleConnector {
    val state: StateFlow<BleConnectionState>
    val logs: StateFlow<List<String>>
    val heartRate: StateFlow<HeartRateMeasurement?>
    val heartRateHistory: StateFlow<List<Int>>
    val notificationsEnabled: StateFlow<Boolean>
    fun startScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun readBodySensorLocation(): BleOperationResult
    fun writeCommand(command: String): BleOperationResult
    /** Lab-compatible textual transport contract. */
    fun sendData(data: String): BleOperationResult = writeCommand(data)
    fun close()
}
