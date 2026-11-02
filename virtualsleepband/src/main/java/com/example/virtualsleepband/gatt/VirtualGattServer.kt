package com.example.virtualsleepband.gatt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

data class CommandLogEntry(
    val timestamp: String,
    val sourceDevice: String,
    val characteristicUuid: String,
    val command: String,
)

data class BandPeripheralState(
    val isAdvertising: Boolean = false,
    val connectedCentrals: Int = 0,
    val currentBpm: Int = 68,
    val batteryLevel: Int = 88,
    val packetIntervalMs: Long = 1000L,
    val scenario: SimulationScenario = SimulationScenario.NORMAL_REST,
    val elapsedSeconds: Long = 0L,
    val transmittedPacketCount: Long = 0L,
    val commandLogs: List<CommandLogEntry> = emptyList(),
    val errorMessage: String? = null,
)

class VirtualGattServer(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val monotonicClockNanos: () -> Long = { runCatching { SystemClock.elapsedRealtimeNanos() }.getOrDefault(System.nanoTime()) },
) {

    private val bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BandPeripheralState())
    val state: StateFlow<BandPeripheralState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val heartRateSubscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val lastAlertNanosByDevice = ConcurrentHashMap<String, Long>()
    private var streamJob: Job? = null
    private var syntheticHeartRate = SimulatedHeartRate()
    private var simulationSeconds = 0L
    private var simulationStartNanos = 0L

    private var pendingBatteryService: BluetoothGattService? = null
    @Volatile private var isStarting = false
    private val registrationSequencer = GattRegistrationSequencer()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = _state.value.copy(isAdvertising = true, errorMessage = null)
            startTransmissionLoop()
        }

        override fun onStartFailure(errorCode: Int) {
            streamJob?.cancel()
            streamJob = null
            _state.value = _state.value.copy(
                isAdvertising = false,
                errorMessage = "Advertising failed: error code $errorCode",
            )
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (!isStarting) return
            when (registrationSequencer.onServiceAdded(status == BluetoothGatt.GATT_SUCCESS, service?.uuid)) {
                RegistrationAction.ADD_BATTERY -> {
                    val battery = pendingBatteryService
                    if (battery == null || gattServer?.addService(battery) != true) failStartup("Battery service registration could not start")
                }
                RegistrationAction.START_ADVERTISING -> startAdvertiserAfterServicesRegistered()
                RegistrationAction.FAIL -> failStartup("GATT service registration failed or arrived out of order: $status")
                RegistrationAction.IGNORE -> Unit
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevices.add(device)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                heartRateSubscribers.remove(device)
            }
            _state.value = _state.value.copy(connectedCentrals = connectedDevices.size)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val target = device ?: return
            when (characteristic?.uuid) {
                UUID_BODY_SENSOR_LOCATION -> {
                    // 0x02 = Wrist
                    gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(0x02))
                }
                UUID_BATTERY_LEVEL -> {
                    val level = _state.value.batteryLevel.coerceIn(0, 100).toByte()
                    gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(level))
                }
                else -> {
                    gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val targetAddress = device?.address ?: "Central"
            val charUuid = characteristic?.uuid ?: UUID_COMMAND_CHARACTERISTIC
            if (characteristic != null && characteristic.uuid != null && characteristic.uuid != UUID_COMMAND_CHARACTERISTIC) {
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }
            if (offset > 0) {
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                }
                return
            }

            if (value != null && value.size > MAX_COMMAND_LENGTH_BYTES) {
                val logEntry = CommandLogEntry(
                    timestamp = Instant.now().toString(),
                    sourceDevice = targetAddress,
                    characteristicUuid = charUuid.toString().take(8),
                    command = "[REJECTED_LENGTH_${value.size}_BYTES]",
                )
                val updatedLogs = (listOf(logEntry) + _state.value.commandLogs).take(50)
                _state.value = _state.value.copy(commandLogs = updatedLogs)
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, 0x0D /* GATT_INVALID_ATTRIBUTE_LENGTH */, offset, null)
                }
                return
            }

            val commandText = value?.let { String(it, Charsets.UTF_8).trim() } ?: ""
            val isSupported = commandText == "ALERT" || commandText == "ALERT_PULSE" || commandText == "PING"

            if (!isSupported) {
                val logEntry = CommandLogEntry(
                    timestamp = Instant.now().toString(),
                    sourceDevice = targetAddress,
                    characteristicUuid = charUuid.toString().take(8),
                    command = "$commandText [REJECTED_UNSUPPORTED]",
                )
                val updatedLogs = (listOf(logEntry) + _state.value.commandLogs).take(50)
                _state.value = _state.value.copy(commandLogs = updatedLogs)
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            if (commandText == "ALERT" || commandText == "ALERT_PULSE") {
                val nowNanos = monotonicClockNanos()
                val lastNanos = lastAlertNanosByDevice[targetAddress] ?: 0L
                val elapsedMs = (nowNanos - lastNanos) / 1_000_000L
                if (lastNanos != 0L && elapsedMs < MIN_ALERT_INTERVAL_MILLIS) {
                    val logEntry = CommandLogEntry(
                        timestamp = Instant.now().toString(),
                        sourceDevice = targetAddress,
                        characteristicUuid = charUuid.toString().take(8),
                        command = "$commandText [THROTTLED_RATE_LIMITED_${elapsedMs}MS]",
                    )
                    val updatedLogs = (listOf(logEntry) + _state.value.commandLogs).take(50)
                    _state.value = _state.value.copy(commandLogs = updatedLogs)
                    if (responseNeeded && device != null) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                    return
                }
                lastAlertNanosByDevice[targetAddress] = nowNanos
            }

            var executionOutcome = "ACK"
            if (commandText == "ALERT" || commandText == "ALERT_PULSE") {
                executionOutcome = triggerPeripheralVibration(300L)
            }

            val logEntry = CommandLogEntry(
                timestamp = Instant.now().toString(),
                sourceDevice = targetAddress,
                characteristicUuid = charUuid.toString().take(8),
                command = "$commandText [$executionOutcome]",
            )

            val updatedLogs = (listOf(logEntry) + _state.value.commandLogs).take(50)
            _state.value = _state.value.copy(commandLogs = updatedLogs)

            if (responseNeeded && device != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val target = device ?: return
            if (descriptor?.uuid == UUID_CCCD && descriptor.characteristic.uuid == UUID_HEART_RATE_MEASUREMENT) {
                if (value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) {
                    heartRateSubscribers.add(target)
                } else {
                    heartRateSubscribers.remove(target)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal var testForcePermissionsGranted: Boolean = false

    fun hasPermissions(): Boolean {
        if (testForcePermissionsGranted) return true
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(): Result<Unit> {
        if (_state.value.isAdvertising || isStarting) return Result.success(Unit)
        if (!hasPermissions()) {
            val msg = "Missing required Bluetooth permissions"
            _state.value = _state.value.copy(errorMessage = msg)
            return Result.failure(SecurityException(msg))
        }

        bluetoothAdapter?.bluetoothLeAdvertiser
            ?: return Result.failure(IllegalStateException("BLE Advertising is not supported on this hardware"))

        val ctx = context ?: return Result.failure(IllegalStateException("Context not available"))
        // Set up GATT Server
        gattServer = bluetoothManager?.openGattServer(ctx, gattServerCallback)
            ?: return Result.failure(IllegalStateException("Unable to open GATT server"))
        val (heartRateService, batteryService) = createServices()
        pendingBatteryService = batteryService
        isStarting = true
        registrationSequencer.start()
        if (gattServer?.addService(heartRateService) != true) {
            failStartup("Heart-rate service registration could not start")
            return Result.failure(IllegalStateException("Heart-rate service registration could not start"))
        }
        simulationSeconds = 0L
        simulationStartNanos = monotonicClockNanos()

        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (hasPermissions()) {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }
        streamJob?.cancel()
        streamJob = null
        if (hasPermissions()) {
            gattServer?.close()
        }
        gattServer = null
        pendingBatteryService = null
        isStarting = false
        registrationSequencer.stop()
        connectedDevices.clear()
        heartRateSubscribers.clear()
        _state.value = _state.value.copy(isAdvertising = false, connectedCentrals = 0)
    }

    fun setPacketInterval(intervalMs: Long) {
        _state.value = _state.value.copy(packetIntervalMs = intervalMs)
        if (_state.value.isAdvertising) {
            startTransmissionLoop()
        }
    }

    fun setScenario(scenario: SimulationScenario) {
        syntheticHeartRate = SimulatedHeartRate(scenario = scenario)
        simulationSeconds = 0L
        simulationStartNanos = monotonicClockNanos()
        _state.value = _state.value.copy(scenario = scenario, elapsedSeconds = 0L, transmittedPacketCount = 0L)
    }

    fun setBpm(bpm: Int) {
        _state.value = _state.value.copy(currentBpm = bpm.coerceIn(40, 180))
    }

    fun setBatteryLevel(level: Int) {
        val bounded = level.coerceIn(0, 100)
        _state.value = _state.value.copy(batteryLevel = bounded)
        notifyBatteryChanged(bounded)
    }

    @SuppressLint("MissingPermission")
    private fun notifyBatteryChanged(level: Int) {
        if (connectedDevices.isEmpty() || !hasPermissions()) return
        val batChar = gattServer?.getService(UUID_BATTERY_SERVICE)?.getCharacteristic(UUID_BATTERY_LEVEL) ?: return
        batChar.value = byteArrayOf(level.toByte())
        for (device in connectedDevices) {
            gattServer?.notifyCharacteristicChanged(device, batChar, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createServices(): Pair<BluetoothGattService, BluetoothGattService> {
        // 1. Heart Rate Service (0x180D)
        val hrService = BluetoothGattService(UUID_HEART_RATE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val hrMeasurement = BluetoothGattCharacteristic(
            UUID_HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        val hrCccd = BluetoothGattDescriptor(
            UUID_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        hrMeasurement.addDescriptor(hrCccd)
        hrService.addCharacteristic(hrMeasurement)

        val bodyLocation = BluetoothGattCharacteristic(
            UUID_BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        hrService.addCharacteristic(bodyLocation)

        val customCommand = BluetoothGattCharacteristic(
            UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        hrService.addCharacteristic(customCommand)

        // 2. Battery Service (0x180F)
        val batService = BluetoothGattService(UUID_BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val batLevel = BluetoothGattCharacteristic(
            UUID_BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val batCccd = BluetoothGattDescriptor(
            UUID_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        batLevel.addDescriptor(batCccd)
        batService.addCharacteristic(batLevel)
        return hrService to batService
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertiserAfterServicesRegistered() {
        if (!isStarting || !hasPermissions()) {
            failStartup("Bluetooth permission lost during startup")
            return
        }
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: run {
            failStartup("BLE advertiser became unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))
            .addManufacturerData(SIMULATOR_MANUFACTURER_ID, SIMULATOR_MARKER)
            .build()
        isStarting = false
        registrationSequencer.stop()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun failStartup(message: String) {
        isStarting = false
        streamJob?.cancel()
        streamJob = null
        runCatching { gattServer?.close() }
        gattServer = null
        pendingBatteryService = null
        _state.value = _state.value.copy(isAdvertising = false, errorMessage = message)
    }

    @SuppressLint("MissingPermission")
    private fun startTransmissionLoop() {
        streamJob?.cancel()
        if (simulationStartNanos == 0L) {
            simulationStartNanos = monotonicClockNanos()
        }
        streamJob = scope.launch {
            while (isActive) {
                delay(_state.value.packetIntervalMs)
                val nowNanos = monotonicClockNanos()
                simulationSeconds = maxOf(0L, (nowNanos - simulationStartNanos) / 1_000_000_000L)
                val emittedBpm = syntheticHeartRate.bpm(simulationSeconds)
                val battery = syntheticHeartRate.batteryPercent(simulationSeconds)
                _state.value = _state.value.copy(currentBpm = emittedBpm, batteryLevel = battery, elapsedSeconds = simulationSeconds)

                if (syntheticHeartRate.hasSignal(simulationSeconds) && heartRateSubscribers.isNotEmpty() && hasPermissions()) {
                    val hrChar = gattServer?.getService(UUID_HEART_RATE_SERVICE)
                        ?.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)

                    if (hrChar != null) {
                        val payload = byteArrayOf(0x00, emittedBpm.toByte())
                        hrChar.value = payload
                        for (dev in heartRateSubscribers.toList()) {
                            gattServer?.notifyCharacteristicChanged(dev, hrChar, false)
                        }
                        _state.value = _state.value.copy(transmittedPacketCount = _state.value.transmittedPacketCount + heartRateSubscribers.size)
                    }
                }
            }
        }
    }

    private fun triggerPeripheralVibration(durationMs: Long): String {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
                "EXECUTED_VIBRATION_${durationMs}MS"
            } else {
                "EXECUTED_NO_VIBRATOR_HARDWARE"
            }
        } catch (e: Exception) {
            "EXECUTED_VIBRATOR_ERROR_${e.javaClass.simpleName}"
        }
    }

    @androidx.annotation.VisibleForTesting
    internal val testGattServerCallback get() = gattServerCallback

    @androidx.annotation.VisibleForTesting
    internal fun testTriggerPeripheralVibration(durationMs: Long) = triggerPeripheralVibration(durationMs)

    @androidx.annotation.VisibleForTesting
    internal fun testHandleCharacteristicWrite(
        device: BluetoothDevice?,
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray?,
        responseNeeded: Boolean = true,
    ) {
        gattServerCallback.onCharacteristicWriteRequest(device, 1, characteristic, false, responseNeeded, 0, value)
    }

    @androidx.annotation.VisibleForTesting
    internal fun testStartTransmissionLoop() {
        startTransmissionLoop()
    }

    @androidx.annotation.VisibleForTesting
    internal fun testStopTransmissionLoop() {
        streamJob?.cancel()
        streamJob = null
    }

    companion object {
        val UUID_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val UUID_BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val UUID_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_COMMAND_CHARACTERISTIC: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
        const val MAX_COMMAND_LENGTH_BYTES = 32
        const val MIN_ALERT_INTERVAL_MILLIS = 2_000L
        const val SIMULATOR_MANUFACTURER_ID = 0xFFFF
        val SIMULATOR_MARKER = byteArrayOf(0x53, 0x53, 0x56, 0x31)
    }
}
