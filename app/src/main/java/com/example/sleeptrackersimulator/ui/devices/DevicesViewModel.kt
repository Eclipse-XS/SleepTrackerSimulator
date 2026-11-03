package com.example.sleeptrackersimulator.ui.devices

import android.content.Context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.data.ble.BleMode
import com.example.sleeptrackersimulator.data.ble.SwitchingDeviceTransport
import com.example.sleeptrackersimulator.data.ble.WebSocketWearableTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.URI

data class DevicesUiState(
    val phoneSensorAvailable: Boolean = true,
    val mode: BleMode = BleMode.REAL,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val heartRate: Int? = null,
    val lastPacketUtcMillis: Long? = null,
    val isHeartRateStreaming: Boolean = false,
    val connectedDevice: DeviceInfo? = null,
    val discoveredDevices: List<DeviceInfo> = emptyList(),
    val errorMessage: String? = null,
    val virtualEndpoint: String = "ws://10.0.2.2:8765",
    val endpointValidationError: String? = null,
    val endpointSavedMessage: String? = null,
)

class DevicesViewModel(
    private val transport: SwitchingDeviceTransport,
    private val preferences: android.content.SharedPreferences,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState

    init {
        _uiState.update { it.copy(virtualEndpoint = preferences.getString(PREF_ENDPOINT, DEFAULT_VIRTUAL_ENDPOINT) ?: DEFAULT_VIRTUAL_ENDPOINT) }
        viewModelScope.launch {
            combine(transport.mode, transport.connectionState, transport.connectedDevice) { mode, state, device ->
                Triple(mode, state, device)
            }.collect { (mode, state, device) ->
                _uiState.update { current -> current.copy(
                    mode = mode,
                    connectionState = state,
                    connectedDevice = device,
                    heartRate = if (state == DeviceConnectionState.CONNECTED) current.heartRate else null,
                    lastPacketUtcMillis = if (state == DeviceConnectionState.CONNECTED) current.lastPacketUtcMillis else null,
                    isHeartRateStreaming = state == DeviceConnectionState.CONNECTED && current.isHeartRateStreaming,
                ) }
            }
        }
        viewModelScope.launch {
            transport.observeDiscoveredDevices().collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }
        viewModelScope.launch { transport.heartRateStream.collect(::onHeartRate) }
        viewModelScope.launch {
            transport.virtualConnectionFailure.collect { failure ->
                if (transport.mode.value == BleMode.VIRTUAL_WEBSOCKET) {
                    _uiState.update { it.copy(errorMessage = failure) }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                _uiState.update { state -> state.copy(
                    isHeartRateStreaming = state.connectionState == DeviceConnectionState.CONNECTED &&
                        state.lastPacketUtcMillis?.let { now - it in 0..STREAM_FRESHNESS_MILLIS } == true,
                ) }
                delay(1000L)
            }
        }
    }

    fun selectMode(mode: BleMode) = launchOperation("Switch BLE mode") { transport.selectMode(mode) }
    fun startScan() = launchResult("Scan") { transport.startScan() }
    fun connectDevice(device: DeviceInfo) = launchResult("Connect") { transport.connect(device.deviceId) }
    fun disconnectDevice() = launchResult("Disconnect") { transport.disconnect() }
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    fun updateVirtualEndpointDraft(value: String) {
        _uiState.update { it.copy(virtualEndpoint = value, endpointValidationError = null, endpointSavedMessage = null) }
    }

    fun saveVirtualEndpoint() {
        val raw = _uiState.value.virtualEndpoint.trim()
        val endpoint = validateEndpoint(raw).getOrElse { error ->
            _uiState.update { it.copy(endpointValidationError = error.message, endpointSavedMessage = null) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(endpointValidationError = null, endpointSavedMessage = null) }
            runCatching {
                transport.replaceVirtualWebSocket(WebSocketWearableTransport(endpoint))
                preferences.edit().putString(PREF_ENDPOINT, endpoint.toString()).apply()
                transport.selectMode(BleMode.VIRTUAL_WEBSOCKET)
            }.onSuccess {
                _uiState.update { it.copy(virtualEndpoint = endpoint.toString(), endpointSavedMessage = "Endpoint saved. Scan to verify the independent app.") }
            }.onFailure { setError("Save endpoint", it) }
        }
    }

    private fun onHeartRate(sample: HeartRateSample) {
        _uiState.update { it.copy(heartRate = sample.bpm, lastPacketUtcMillis = sample.utcTimestampMillis, isHeartRateStreaming = true) }
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
        const val STREAM_FRESHNESS_MILLIS = 10_000L
        const val DEFAULT_VIRTUAL_ENDPOINT = "ws://10.0.2.2:8765"
        private const val PREF_ENDPOINT = "virtual_websocket_endpoint"

        internal fun validateEndpoint(raw: String): Result<URI> = runCatching {
            val uri = URI.create(raw)
            require(uri.scheme == "ws") { "Only ws:// endpoints are supported for isolated development networks" }
            require(!uri.host.isNullOrBlank()) { "Endpoint must include a host" }
            require(uri.port in 1..65_535) { "Endpoint must include a port from 1 to 65535" }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Credentials, query and fragment are not allowed" }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Endpoint path must be empty" }
            uri
        }

        fun factory(context: Context, transport: SwitchingDeviceTransport): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DevicesViewModel(
                transport,
                context.applicationContext.getSharedPreferences("wearable_transport", Context.MODE_PRIVATE),
            ) as T
        }
    }
}
