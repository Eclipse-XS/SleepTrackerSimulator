package com.example.sleeptrackersimulator.ble

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    notificationIntervalMillis: Long = 1_500L,
    initialBpm: Int = 62,
    variation: HeartRateVariation = HeartRateVariation { kotlin.random.Random.Default.nextInt(-2, 3) },
    private val logOutput: (String, String) -> Unit = { tag, message -> Log.d(tag, message) },
) : IBleConnector {
    private val band = VirtualSleepBand(scope, notificationIntervalMillis, initialBpm, variation = variation)
    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val state: StateFlow<BleConnectionState> = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _heartRate = MutableStateFlow<HeartRateMeasurement?>(null)
    override val heartRate: StateFlow<HeartRateMeasurement?> = _heartRate.asStateFlow()
    private val _heartRateHistory = MutableStateFlow<List<Int>>(emptyList())
    override val heartRateHistory: StateFlow<List<Int>> = _heartRateHistory.asStateFlow()
    private val _notificationsEnabled = MutableStateFlow(false)
    override val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()
    private var lifecycleJob: Job? = null
    private var measurementJob: Job? = null

    override fun startScan() {
        if (_state.value is BleConnectionState.Scanning) return
        stopTelemetry()
        lifecycleJob?.cancel()
        _state.value = BleConnectionState.Scanning
        log("SCAN started")
        lifecycleJob = scope.launch {
            try {
                val devices = scanner.scan().distinctBy(BleDevice::address)
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
        val discovered = (_state.value as? BleConnectionState.DeviceFound)?.devices.orEmpty()
        if (device !in discovered) {
            log("ERROR connect rejected: device was not discovered")
            return
        }
        lifecycleJob?.cancel()
        _state.value = BleConnectionState.Connecting(device)
        log("CONNECTING ${device.name}")
        lifecycleJob = scope.launch {
            delay(connectionDelayMillis)
            _state.value = BleConnectionState.Connected(device)
            log("CONNECTED ${device.name}")
            log("SERVICE ${HeartRateProfile.SERVICE_SHORT_ID} discovered")
            log("SUBSCRIBED characteristic ${HeartRateProfile.MEASUREMENT_SHORT_ID}")
            startTelemetry()
        }
    }

    override fun disconnect() {
        val current = _state.value
        if (current is BleConnectionState.Idle || current is BleConnectionState.Disconnected) return
        lifecycleJob?.cancel()
        val device = when (current) {
            is BleConnectionState.Connected -> current.device
            is BleConnectionState.Connecting -> current.device
            else -> null
        }
        stopTelemetry()
        _state.value = BleConnectionState.Disconnected(device)
        log("DISCONNECTED${device?.let { " ${it.name}" }.orEmpty()}")
    }

    override fun readBodySensorLocation(): BleOperationResult {
        if (_state.value !is BleConnectionState.Connected) return notConnected()
        val message = "READ ${HeartRateProfile.BODY_SENSOR_LOCATION_SHORT_ID}: ${band.bodySensorLocation}"
        log(message)
        return BleOperationResult.Success(band.bodySensorLocation)
    }

    override fun writeCommand(command: String): BleOperationResult {
        if (_state.value !is BleConnectionState.Connected) return notConnected()
        val parsed = runCatching { DeviceCommand.valueOf(command.trim().uppercase()) }.getOrNull()
            ?: return BleOperationResult.Error("UNKNOWN_COMMAND").also {
                log("WRITE ERROR: UNKNOWN_COMMAND [$command]")
            }
        log("WRITE command: ${parsed.name}")
        return when (parsed) {
            DeviceCommand.START_STREAM -> {
                startTelemetry()
                BleOperationResult.Success("STREAM_STARTED")
            }
            DeviceCommand.STOP_STREAM -> {
                stopTelemetry()
                BleOperationResult.Success("STREAM_STOPPED")
            }
            DeviceCommand.REQUEST_STATUS -> BleOperationResult.Success(
                "CONNECTED; STREAM=${if (band.isStreaming) "ON" else "OFF"}; HR=${band.currentMeasurement.beatsPerMinute}",
            )
        }.also { result -> log("WRITE result: ${result.message}") }
    }

    override fun close() {
        lifecycleJob?.cancel()
        stopTelemetry()
        band.close()
    }

    private fun startTelemetry() {
        if (_state.value !is BleConnectionState.Connected || _notificationsEnabled.value) return
        _notificationsEnabled.value = true
        measurementJob = scope.launch {
            band.measurements.collect { measurement ->
                if (_notificationsEnabled.value) {
                    _heartRate.value = measurement
                    _heartRateHistory.update { (it + measurement.beatsPerMinute).takeLast(MAX_HISTORY_SIZE) }
                    log("NOTIFY ${HeartRateProfile.MEASUREMENT_SHORT_ID}: HR=${measurement.beatsPerMinute} BPM")
                }
            }
        }
        band.startStreaming()
    }

    private fun stopTelemetry() {
        _notificationsEnabled.value = false
        band.stopStreaming()
        measurementJob?.cancel()
        measurementJob = null
    }

    private fun notConnected(): BleOperationResult.Error =
        BleOperationResult.Error("NOT_CONNECTED").also { log("ERROR NOT_CONNECTED") }

    private fun log(message: String) {
        try { logOutput(TAG, message) } catch (_: RuntimeException) { }
        _logs.update { (it + message).takeLast(MAX_LOG_LINES) }
    }

    companion object {
        private const val TAG = "MockBleConnector"
        private const val MAX_LOG_LINES = 80
        const val MAX_HISTORY_SIZE = 60
    }
}
