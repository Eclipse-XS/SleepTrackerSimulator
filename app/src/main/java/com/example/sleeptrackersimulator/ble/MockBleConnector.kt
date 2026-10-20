package com.example.sleeptrackersimulator.ble

import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
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
    private val logOutput: (String, String) -> Unit = { _, _ -> },
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
    override val alertState: StateFlow<DeviceAlertState> = band.alertState
    private var lifecycleJob: Job? = null
    private var measurementJob: Job? = null

    override fun startScan() {
        if (_state.value is BleConnectionState.Scanning) {
            diagnostic("scan", DiagnosticResult.SKIPPED, mapOf("reason" to "already_scanning"))
            return
        }
        stopTelemetry()
        band.clearAlert()
        clearSessionMeasurements()
        lifecycleJob?.cancel()
        _state.value = BleConnectionState.Scanning
        diagnostic("scan", DiagnosticResult.STARTED)
        log("SCAN started")
        lifecycleJob = scope.launch {
            try {
                val devices = scanner.scan().distinctBy(BleDevice::address)
                _state.value = BleConnectionState.DeviceFound(devices)
                diagnostic("scan", DiagnosticResult.SUCCESS, mapOf("deviceCount" to devices.size))
                devices.forEach { diagnostic("deviceFound", DiagnosticResult.SUCCESS, mapOf("name" to it.name, "addressType" to "virtual")) }
                devices.forEach { log("FOUND ${it.name} [${it.address}]") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = BleConnectionState.Error(error.message ?: "BLE scan failed")
                diagnostic("scan", DiagnosticResult.FAILED, mapOf("reason" to error.javaClass.simpleName), error)
                log("ERROR scan failed")
            }
        }
    }

    override fun connect(device: BleDevice) {
        val discovered = (_state.value as? BleConnectionState.DeviceFound)?.devices.orEmpty()
        if (device !in discovered) {
            diagnostic("connect", DiagnosticResult.SKIPPED, mapOf("reason" to "device_not_discovered"))
            log("ERROR connect rejected: device was not discovered")
            return
        }
        lifecycleJob?.cancel()
        clearSessionMeasurements()
        _state.value = BleConnectionState.Connecting(device)
        diagnostic("connect", DiagnosticResult.STARTED, mapOf("deviceName" to device.name))
        log("CONNECTING ${device.name}")
        lifecycleJob = scope.launch {
            delay(connectionDelayMillis)
            _state.value = BleConnectionState.Connected(device)
            diagnostic("connectionState", DiagnosticResult.SUCCESS, mapOf("from" to "CONNECTING", "to" to "CONNECTED", "deviceName" to device.name))
            log("CONNECTED ${device.name}")
            log("SERVICE ${HeartRateProfile.SERVICE_SHORT_ID} discovered")
            log("SUBSCRIBED characteristic ${HeartRateProfile.MEASUREMENT_SHORT_ID}")
            startTelemetry()
        }
    }

    override fun disconnect() {
        val current = _state.value
        if (current is BleConnectionState.Idle || current is BleConnectionState.Disconnected) {
            diagnostic("disconnect", DiagnosticResult.SKIPPED, mapOf("reason" to "not_connected"))
            return
        }
        lifecycleJob?.cancel()
        val device = when (current) {
            is BleConnectionState.Connected -> current.device
            is BleConnectionState.Connecting -> current.device
            else -> null
        }
        stopTelemetry()
        band.clearAlert()
        clearSessionMeasurements()
        _state.value = BleConnectionState.Disconnected(device)
        diagnostic("disconnect", DiagnosticResult.SUCCESS, mapOf("deviceName" to device?.name))
        log("DISCONNECTED${device?.let { " ${it.name}" }.orEmpty()}")
    }

    override fun readBodySensorLocation(): BleOperationResult {
        if (_state.value !is BleConnectionState.Connected) return notConnected("read")
        val message = "READ ${HeartRateProfile.BODY_SENSOR_LOCATION_SHORT_ID}: ${band.bodySensorLocation}"
        log(message)
        diagnostic("read", DiagnosticResult.SUCCESS, mapOf("characteristic" to HeartRateProfile.BODY_SENSOR_LOCATION_SHORT_ID))
        return BleOperationResult.Success(band.bodySensorLocation)
    }

    override fun writeCommand(command: String): BleOperationResult {
        if (_state.value !is BleConnectionState.Connected) return notConnected("write")
        val parsed = runCatching { DeviceCommand.valueOf(command.trim().uppercase()) }.getOrNull()
            ?: return BleOperationResult.Error("UNKNOWN_COMMAND").also {
                diagnostic("write", DiagnosticResult.FAILED, mapOf("reason" to "unknown_command"))
                log("WRITE ERROR: UNKNOWN_COMMAND [$command]")
            }
        log("WRITE command: ${parsed.name}")
        val result = when (parsed) {
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
            DeviceCommand.ALERT -> sendAlert("manual-${System.nanoTime()}")
        }
        val message = when (result) {
            is BleOperationResult.Success -> result.message
            is BleOperationResult.Error -> result.message
        }
        log("WRITE result: $message")
        diagnostic("write", DiagnosticResult.SUCCESS, mapOf("command" to parsed.name, "resultMessage" to message))
        return result
    }

    override fun sendData(data: String): BleOperationResult = writeCommand(data)

    override fun sendAlert(eventId: String): BleOperationResult {
        if (_state.value !is BleConnectionState.Connected) return notConnected("alert")
        val before = band.alertState.value
        val state = runCatching { band.processAlert(eventId) }.getOrElse {
            return BleOperationResult.Error("INVALID_EVENT_ID")
        }
        val duplicate = before.active && before.eventId == eventId
        val message = if (duplicate) "ALERT_DUPLICATE_ACK:$eventId" else "ALERT_ACK:$eventId:REV=${state.revision}"
        log("WRITE result: $message")
        diagnostic("alert", DiagnosticResult.SUCCESS, mapOf("eventId" to eventId, "duplicate" to duplicate, "revision" to state.revision))
        return BleOperationResult.Success(message)
    }

    override fun close() {
        lifecycleJob?.cancel()
        stopTelemetry()
        band.clearAlert()
        clearSessionMeasurements()
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
                    log("NOTIFY ${HeartRateProfile.MEASUREMENT_SHORT_ID}: HR=${measurement.beatsPerMinute} BPM", diagnostics = false)
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

    private fun clearSessionMeasurements() {
        _heartRate.value = null
        _heartRateHistory.value = emptyList()
    }

    private fun notConnected(operation: String): BleOperationResult.Error =
        BleOperationResult.Error("NOT_CONNECTED").also {
            diagnostic(operation, DiagnosticResult.FAILED, mapOf("reason" to "not_connected"))
            log("ERROR NOT_CONNECTED")
        }

    private fun log(message: String, diagnostics: Boolean = true) {
        if (diagnostics) try { logOutput(TAG, message) } catch (_: RuntimeException) { }
        _logs.update { (it + message).takeLast(MAX_LOG_LINES) }
    }

    private fun diagnostic(
        operation: String,
        result: DiagnosticResult,
        details: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = DiagnosticLog.event(DiagnosticTags.BLE, operation, result, source = "USER", details = details, error = error)

    companion object {
        private const val TAG = "MockBleConnector"
        private const val MAX_LOG_LINES = 80
        const val MAX_HISTORY_SIZE = 60
    }
}
