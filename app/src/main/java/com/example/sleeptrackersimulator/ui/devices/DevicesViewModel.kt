package com.example.sleeptrackersimulator.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.data.ble.BleMode
import com.example.sleeptrackersimulator.data.ble.SwitchingDeviceTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val phoneSensorAvailable: Boolean = true,
    val mode: BleMode = BleMode.REAL,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val heartRate: Int? = null,
    val lastPacketUtcMillis: Long? = null,
    val connectedDevice: DeviceInfo? = null,
    val discoveredDevices: List<DeviceInfo> = emptyList(),
    val errorMessage: String? = null,
)

class DevicesViewModel(private val transport: SwitchingDeviceTransport) : ViewModel() {
    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(transport.mode, transport.connectionState, transport.connectedDevice) { mode, state, device ->
                Triple(mode, state, device)
            }.collect { (mode, state, device) ->
                _uiState.update { it.copy(mode = mode, connectionState = state, connectedDevice = device) }
            }
        }
        viewModelScope.launch {
            transport.observeDiscoveredDevices().collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }
        viewModelScope.launch { transport.heartRateStream.collect(::onHeartRate) }
    }

    fun selectMode(mode: BleMode) = launchOperation("Switch BLE mode") { transport.selectMode(mode) }
    fun startScan() = launchResult("Scan") { transport.startScan() }
    fun connectDevice(device: DeviceInfo) = launchResult("Connect") { transport.connect(device.deviceId) }
    fun disconnectDevice() = launchResult("Disconnect") { transport.disconnect() }
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    private fun onHeartRate(sample: HeartRateSample) {
        _uiState.update { it.copy(heartRate = sample.bpm, lastPacketUtcMillis = sample.utcTimestampMillis) }
    }

    private fun launchOperation(name: String, operation: suspend () -> Unit) {
        viewModelScope.launch { runCatching { operation() }.onFailure { setError(name, it) } }
    }

    private fun launchResult(name: String, operation: suspend () -> Result<Unit>) {
        viewModelScope.launch { operation().onFailure { setError(name, it) } }
    }

    private fun setError(operation: String, error: Throwable) {
        _uiState.update { it.copy(errorMessage = "$operation failed: ${error.message ?: error.javaClass.simpleName}") }
    }

    companion object {
        fun factory(transport: SwitchingDeviceTransport): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DevicesViewModel(transport) as T
        }
    }
}
