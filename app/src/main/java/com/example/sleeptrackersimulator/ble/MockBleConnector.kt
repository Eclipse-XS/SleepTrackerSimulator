package com.example.sleeptrackersimulator.ble

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MockBleConnector(
    private val scanner: MockBleScanner = MockBleScanner(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val connectionDelayMillis: Long = 750L,
    private val logOutput: (String, String) -> Unit = { tag, msg -> Log.d(tag, msg) },
) : IBleConnector {

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var operationJob: Job? = null

    override fun startScan() {
        if (_state.value is BleConnectionState.Scanning) return
        operationJob?.cancel()
        _state.value = BleConnectionState.Scanning
        log("SCAN started")
        operationJob = scope.launch {
            try {
                val devices = scanner.scan()
                _state.value = BleConnectionState.DeviceFound(devices)
                devices.forEach { log("FOUND ${it.name} [${it.address}]") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = BleConnectionState.Error(error.message ?: "BLE scan failed")
                log("ERROR scan failed")
            }
        }
    }

    override fun connect(device: BleDevice) {
        val currentState = _state.value
        val found = (currentState as? BleConnectionState.DeviceFound)?.devices.orEmpty()
        if (device !in found) return
        operationJob?.cancel()
        _state.value = BleConnectionState.Connecting(device)
        log("CONNECTING ${device.name}")
        operationJob = scope.launch {
            delay(connectionDelayMillis)
            _state.value = BleConnectionState.Connected(device)
            log("CONNECTED ${device.name}")
            log("NOTIFY ${BleProfile.STATUS_CHARACTERISTIC_UUID}: STATUS:READY")
        }
    }

    override fun disconnect() {
        val current = _state.value
        if (current is BleConnectionState.Disconnected || current is BleConnectionState.Idle) {
            return
        }
        operationJob?.cancel()
        val device = when (current) {
            is BleConnectionState.Connected -> current.device
            is BleConnectionState.Connecting -> current.device
            else -> null
        }
        _state.value = BleConnectionState.Disconnected(device)
        log("DISCONNECTED${device?.let { " ${it.name}" }.orEmpty()}")
    }

    override fun sendData(data: String) {
        if (_state.value !is BleConnectionState.Connected || data.isBlank()) return
        val payload = data.trim()
        log("TX ${BleProfile.COMMAND_CHARACTERISTIC_UUID}: $payload")
        log("RX ${BleProfile.STATUS_CHARACTERISTIC_UUID}: ACK:$payload")
    }

    override fun close() {
        operationJob?.cancel()
        scope.cancel()
    }

    private fun log(message: String) {
        try {
            logOutput(TAG, message)
        } catch (_: RuntimeException) {
            // JVM unit test fallback when android.util.Log is not mocked
        }
        _logs.update { (it + message).takeLast(MAX_LOG_LINES) }
    }

    companion object {
        private const val TAG = "MockBleConnector"
        private const val MAX_LOG_LINES = 50
    }
}
