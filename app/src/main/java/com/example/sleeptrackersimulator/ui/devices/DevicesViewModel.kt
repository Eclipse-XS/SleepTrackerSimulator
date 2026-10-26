package com.example.sleeptrackersimulator.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.IBleConnector
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DevicesUiState(
    val phoneSensorAvailable: Boolean = true,
    val bleState: BleConnectionState = BleConnectionState.Idle,
    val heartRate: Int? = null,
    val isDemoBandActive: Boolean = false,
    val discoveredDevices: List<DeviceInfo> = emptyList(),
)

class DevicesViewModel(
    private val bleConnector: IBleConnector,
) : ViewModel() {

    private val _isDemoBandActive = MutableStateFlow(false)

    val uiState: StateFlow<DevicesUiState> = combine(
        bleConnector.state,
        bleConnector.heartRate,
        _isDemoBandActive,
    ) { bleState, hr, demoActive ->
        DevicesUiState(
            phoneSensorAvailable = true,
            bleState = bleState,
            heartRate = if (bleState is BleConnectionState.Connected) hr?.beatsPerMinute else null,
            isDemoBandActive = demoActive,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DevicesUiState(),
    )

    fun toggleDemoBand() {
        val willActivate = !_isDemoBandActive.value
        _isDemoBandActive.value = willActivate
        if (willActivate) {
            bleConnector.startScan()
        } else {
            bleConnector.disconnect()
        }
    }

    fun connectDevice(device: BleDevice = BleDevice("Virtual Sleep Band", "00:11:22:33:AA:BB")) {
        bleConnector.connect(device)
    }

    fun disconnectDevice() {
        bleConnector.disconnect()
    }

    companion object {
        fun factory(bleConnector: IBleConnector): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DevicesViewModel(bleConnector) as T
                }
            }
    }
}
