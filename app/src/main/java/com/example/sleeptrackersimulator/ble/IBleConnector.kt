package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.flow.StateFlow

interface IBleConnector {
    val state: StateFlow<BleConnectionState>
    val logs: StateFlow<List<String>>
    fun startScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun sendData(data: String)
    fun close()
}
