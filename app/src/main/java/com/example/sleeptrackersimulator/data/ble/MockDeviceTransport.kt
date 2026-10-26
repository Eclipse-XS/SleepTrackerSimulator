package com.example.sleeptrackersimulator.data.ble

import com.example.sleeptrackersimulator.ble.DeviceAlertState
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.ble.VirtualSleepBand
import com.example.sleeptrackersimulator.core.model.CommandResult
import com.example.sleeptrackersimulator.core.model.DeviceCapability
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Deterministic in-memory simulated BLE transport wrapping VirtualSleepBand.
 * Strictly labels all emitted telemetry with SourceKind.SIMULATED.
 */
class MockDeviceTransport(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    notificationIntervalMillis: Long = 1000L,
    initialBpm: Int = 65,
) : DeviceTransport {

    private val band = VirtualSleepBand(
        scope = scope,
        notificationIntervalMillis = notificationIntervalMillis,
        initialBpm = initialBpm,
    )

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<DeviceInfo?>(null)
    override val connectedDevice: StateFlow<DeviceInfo?> = _connectedDevice.asStateFlow()

    private val _heartRateStream = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val heartRateStream: SharedFlow<HeartRateSample> = _heartRateStream.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private var measurementJob: Job? = null

    val simulatedDeviceInfo = DeviceInfo(
        deviceId = "VIRTUAL:HR:01",
        displayName = "Virtual Sleep Band (Simulated)",
        sourceKind = SourceKind.SIMULATED,
        capabilities = setOf(
            DeviceCapability.HEART_RATE,
            DeviceCapability.VIBRATION_ALERT,
            DeviceCapability.BATTERY_LEVEL,
            DeviceCapability.REMOTE_COMMANDS,
        ),
        rssi = -58,
        batteryLevel = 92,
    )

    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    override suspend fun startScan(): Result<Unit> {
        _connectionState.value = DeviceConnectionState.SCANNING
        delay(400L) // Simulate scan discovery latency
        _discoveredDevices.value = listOf(simulatedDeviceInfo)
        return Result.success(Unit)
    }

    override suspend fun stopScan(): Result<Unit> {
        if (_connectionState.value == DeviceConnectionState.SCANNING) {
            _connectionState.value = DeviceConnectionState.DISCONNECTED
        }
        return Result.success(Unit)
    }

    override suspend fun connect(deviceId: String): Result<Unit> {
        _connectionState.value = DeviceConnectionState.CONNECTING
        delay(350L) // Simulate GATT connection handshake

        _connectedDevice.value = simulatedDeviceInfo
        _connectionState.value = DeviceConnectionState.CONNECTED

        band.startStreaming()

        measurementJob?.cancel()
        measurementJob = scope.launch {
            band.measurements.collect { measurement ->
                val sample = HeartRateSample(
                    sampleId = UUID.randomUUID().toString(),
                    sessionId = "active_session",
                    deviceId = simulatedDeviceInfo.deviceId,
                    bpm = measurement.beatsPerMinute,
                    utcTimestampMillis = System.currentTimeMillis(),
                    timingUncertaintyMillis = 0L,
                    sourceKind = SourceKind.SIMULATED,
                )
                _heartRateStream.emit(sample)
            }
        }

        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        measurementJob?.cancel()
        measurementJob = null
        band.stopStreaming()

        _connectedDevice.value = null
        _connectionState.value = DeviceConnectionState.DISCONNECTED
        return Result.success(Unit)
    }

    override suspend fun executeCommand(command: DeviceCommand): Result<CommandResult> {
        if (_connectionState.value != DeviceConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Device is not connected"))
        }

        return when (command.action.uppercase()) {
            "ALERT", "VIBRATE" -> {
                band.processAlert(command.commandId)
                Result.success(
                    CommandResult(
                        commandId = command.commandId,
                        success = true,
                        statusMessage = "Simulated pulse alert executed successfully",
                    ),
                )
            }
            "STOP_STREAM" -> {
                band.stopStreaming()
                Result.success(
                    CommandResult(
                        commandId = command.commandId,
                        success = true,
                        statusMessage = "Stream paused",
                    ),
                )
            }
            "START_STREAM" -> {
                band.startStreaming()
                Result.success(
                    CommandResult(
                        commandId = command.commandId,
                        success = true,
                        statusMessage = "Stream resumed",
                    ),
                )
            }
            else -> {
                Result.success(
                    CommandResult(
                        commandId = command.commandId,
                        success = true,
                        statusMessage = "Acknowledged command: ${command.action}",
                    ),
                )
            }
        }
    }
}
