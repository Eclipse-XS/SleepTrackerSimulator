package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.IBleConnector
import kotlinx.coroutines.flow.StateFlow

class BleViewModel(private val connector: IBleConnector) : ViewModel() {
    val state: StateFlow<BleConnectionState> = connector.state
    val logs: StateFlow<List<String>> = connector.logs
    val heartRate = connector.heartRate
    val heartRateHistory = connector.heartRateHistory
    val notificationsEnabled = connector.notificationsEnabled

    fun startScan() = connector.startScan()
    fun connect(device: BleDevice) = connector.connect(device)
    fun disconnect() = connector.disconnect()
    fun readBodySensorLocation() = connector.readBodySensorLocation()
    fun writeCommand(command: String) = connector.writeCommand(command.trim())

    override fun onCleared() {
        connector.close()
        super.onCleared()
    }

    companion object {
        fun factory(connector: IBleConnector): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(BleViewModel::class.java))
                    return BleViewModel(connector) as T
                }
            }
    }
}
