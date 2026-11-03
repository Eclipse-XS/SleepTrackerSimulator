package com.example.sleeptrackersimulator.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.sleeptrackersimulator.core.model.CommandResult
import com.example.sleeptrackersimulator.core.model.DeviceCapability
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production Android BLE GATT Transport client communicating with genuine Bluetooth peripherals.
 * Adheres strictly to the Bluetooth SIG Heart Rate Profile (0x180D, 0x2A37).
 * Transport authenticity and signal authenticity are tracked separately. Unknown peripherals
 * remain BLE_UNVERIFIED; the companion marker identifies synthetic data but is not authenticated.
 */
class AndroidGattDeviceTransport(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    injectedBluetoothManager: BluetoothManager? = null,
) : DeviceTransport {

    private val bluetoothManager = injectedBluetoothManager ?: runCatching {
        context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }.getOrNull()
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<DeviceInfo?>(null)
    override val connectedDevice: StateFlow<DeviceInfo?> = _connectedDevice.asStateFlow()

    private val _heartRateStream = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val heartRateStream: SharedFlow<HeartRateSample> = _heartRateStream.asSharedFlow()
    private val _lastHeartRate = MutableStateFlow<HeartRateSample?>(null)
    override val lastHeartRate: StateFlow<HeartRateSample?> = _lastHeartRate.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private var activeGatt: BluetoothGatt? = null
    private var scanTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private val discoveredSourceKinds = ConcurrentHashMap<String, SourceKind>()

    private var userRequestedDisconnect = false
    private var lastConnectedDeviceId: String? = null
    private var reconnectAttempts = 0

    private val commandMutex = Mutex()
    private var pendingCommandDeferred: CompletableDeferred<Boolean>? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: "Unknown BLE Device"
                val address = device.address
                val rssi = result.rssi

                val marker = result.scanRecord?.getManufacturerSpecificData(SIMULATOR_MANUFACTURER_ID)
                val sourceKind = if (marker?.contentEquals(SIMULATOR_MARKER) == true) {
                    SourceKind.SIMULATED
                } else SourceKind.BLE_UNVERIFIED
                discoveredSourceKinds[address] = sourceKind
                val discovered = DeviceInfo(
                    deviceId = address,
                    displayName = name,
                    sourceKind = sourceKind,
                    capabilities = setOf(DeviceCapability.HEART_RATE, DeviceCapability.REMOTE_COMMANDS),
                    rssi = rssi,
                )

                val current = _discoveredDevices.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.deviceId == address }
                if (existingIndex >= 0) {
                    current[existingIndex] = discovered
                } else {
                    current.add(discovered)
                }
                _discoveredDevices.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanTimeoutJob?.cancel()
            _connectionState.value = DeviceConnectionState.ERROR
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt != null && activeGatt != null && gatt !== activeGatt) {
                gatt.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    reconnectAttempts = 0
                    _connectionState.value = DeviceConnectionState.DISCOVERING_SERVICES
                    if (gatt?.discoverServices() != true) {
                        handleUnexpectedDisconnect(gatt)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionTimeoutJob?.cancel()
                    pendingCommandDeferred?.complete(false)
                    pendingCommandDeferred = null
                    if (!userRequestedDisconnect && lastConnectedDeviceId != null) {
                        handleUnexpectedDisconnect(gatt)
                    } else {
                        _connectionState.value = DeviceConnectionState.DISCONNECTED
                        _connectedDevice.value = null
                        activeGatt?.close()
                        activeGatt = null
                    }
                }
            } else {
                connectionTimeoutJob?.cancel()
                pendingCommandDeferred?.complete(false)
                pendingCommandDeferred = null
                if (!userRequestedDisconnect && lastConnectedDeviceId != null) {
                    handleUnexpectedDisconnect(gatt)
                } else {
                    _connectionState.value = DeviceConnectionState.ERROR
                    _connectedDevice.value = null
                    activeGatt?.close()
                    activeGatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt !== activeGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val hrService = gatt.getService(UUID_HEART_RATE_SERVICE)
                val hrChar = hrService?.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)

                if (hrChar != null) {
                    _connectionState.value = DeviceConnectionState.SUBSCRIBING
                    if (!gatt.setCharacteristicNotification(hrChar, true)) {
                        handleUnexpectedDisconnect(gatt)
                        return
                    }

                    val descriptor = hrChar.getDescriptor(UUID_CCCD)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) {
                            handleUnexpectedDisconnect(gatt)
                        }
                    } else {
                        handleUnexpectedDisconnect(gatt)
                    }
                } else {
                    handleUnexpectedDisconnect(gatt)
                }
            } else {
                handleUnexpectedDisconnect(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (activeGatt != null && gatt !== activeGatt) return
            val descUuid = descriptor?.uuid ?: UUID_CCCD
            if (descUuid == UUID_CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectionTimeoutJob?.cancel()
                    reconnectAttempts = 0
                    _connectionState.value = DeviceConnectionState.CONNECTED
                } else {
                    handleUnexpectedDisconnect(gatt)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (activeGatt != null && gatt !== activeGatt) return
            if (_connectionState.value != DeviceConnectionState.CONNECTED) return
            val charUuid = characteristic?.uuid ?: UUID_HEART_RATE_MEASUREMENT
            if (charUuid == UUID_HEART_RATE_MEASUREMENT) {
                val value = characteristic?.value ?: return
                parseHeartRateMeasurement(value, gatt?.device?.address ?: "unknown")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            if (activeGatt != null && gatt !== activeGatt) return
            val charUuid = characteristic?.uuid ?: UUID_COMMAND_CHARACTERISTIC
            if (charUuid == UUID_COMMAND_CHARACTERISTIC) {
                pendingCommandDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleUnexpectedDisconnect(gatt: BluetoothGatt?) {
        gatt?.close()
        if (activeGatt === gatt) {
            activeGatt = null
        }
        val targetDeviceId = lastConnectedDeviceId
        if (targetDeviceId != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            val attempt = ++reconnectAttempts
            val backoffDelayMs = (1000L * (1L shl (attempt - 1))).coerceAtMost(8000L)
            _connectionState.value = DeviceConnectionState.RECONNECTING
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(backoffDelayMs)
                if (!userRequestedDisconnect && lastConnectedDeviceId == targetDeviceId) {
                    connect(targetDeviceId)
                }
            }
        } else {
            _connectionState.value = DeviceConnectionState.ERROR
            _connectedDevice.value = null
        }
    }

    internal fun parseHeartRateMeasurement(bytes: ByteArray, deviceAddress: String) {
        val bpm = HeartRatePacketParser.parse(bytes).getOrNull() ?: return

        val sample = HeartRateSample(
            sampleId = UUID.randomUUID().toString(),
            sessionId = "active_session",
            deviceId = deviceAddress,
            bpm = bpm,
            utcTimestampMillis = System.currentTimeMillis(),
            timingUncertaintyMillis = 0L,
            sourceKind = discoveredSourceKinds[deviceAddress] ?: SourceKind.BLE_UNVERIFIED,
        )

        scope.launch {
            _lastHeartRate.value = sample
            _heartRateStream.emit(sample)
        }
    }

    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startScan(): Result<Unit> {
        if (!hasBlePermissions()) {
            _connectionState.value = DeviceConnectionState.ERROR
            return Result.failure(SecurityException("Missing Bluetooth permissions"))
        }

        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = DeviceConnectionState.UNSUPPORTED
            return Result.failure(UnsupportedOperationException("Bluetooth LE is unavailable on this device"))
        }
        if (!adapter.isEnabled) {
            _connectionState.value = DeviceConnectionState.ERROR
            return Result.failure(IllegalStateException("Bluetooth is disabled"))
        }

        val scanner = adapter.bluetoothLeScanner
            ?: return Result.failure(IllegalStateException("Bluetooth LE Scanner not available"))

        _connectionState.value = DeviceConnectionState.SCANNING
        _discoveredDevices.value = emptyList()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MILLIS)
            if (_connectionState.value == DeviceConnectionState.SCANNING) stopScan()
        }
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan(): Result<Unit> {
        scanTimeoutJob?.cancel()
        if (hasBlePermissions()) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        if (_connectionState.value == DeviceConnectionState.SCANNING) {
            _connectionState.value = DeviceConnectionState.DISCONNECTED
        }
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceId: String): Result<Unit> {
        if (!hasBlePermissions()) {
            _connectionState.value = DeviceConnectionState.ERROR
            return Result.failure(SecurityException("Missing Bluetooth permissions"))
        }

        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = DeviceConnectionState.UNSUPPORTED
            return Result.failure(UnsupportedOperationException("Bluetooth LE is unavailable on this device"))
        }
        if (!adapter.isEnabled) {
            _connectionState.value = DeviceConnectionState.ERROR
            return Result.failure(IllegalStateException("Bluetooth is disabled"))
        }

        reconnectJob?.cancel()
        userRequestedDisconnect = false
        lastConnectedDeviceId = deviceId

        stopScan()
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        val device = runCatching { adapter.getRemoteDevice(deviceId) }.getOrElse {
            _connectionState.value = DeviceConnectionState.ERROR
            return Result.failure(IllegalArgumentException("Invalid Bluetooth device address: $deviceId", it))
        }

        _connectionState.value = DeviceConnectionState.CONNECTING
        val ctx = context ?: return Result.failure(IllegalStateException("Context not available"))
        activeGatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MILLIS)
            if (_connectionState.value in setOf(
                    DeviceConnectionState.CONNECTING,
                    DeviceConnectionState.DISCOVERING_SERVICES,
                    DeviceConnectionState.SUBSCRIBING,
                )
            ) {
                activeGatt?.disconnect()
                activeGatt?.close()
                activeGatt = null
                _connectedDevice.value = null
                _connectionState.value = DeviceConnectionState.ERROR
            }
        }

        _connectedDevice.value = DeviceInfo(
            deviceId = device.address,
            displayName = device.name ?: "BLE Heart Rate Sensor",
            sourceKind = discoveredSourceKinds[device.address] ?: SourceKind.BLE_UNVERIFIED,
            capabilities = setOf(DeviceCapability.HEART_RATE, DeviceCapability.REMOTE_COMMANDS),
        )

        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect(): Result<Unit> {
        userRequestedDisconnect = true
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        reconnectAttempts = 0
        lastConnectedDeviceId = null

        pendingCommandDeferred?.complete(false)
        pendingCommandDeferred = null

        if (hasBlePermissions()) {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
        _connectedDevice.value = null
        _lastHeartRate.value = null
        _connectionState.value = DeviceConnectionState.DISCONNECTED
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun executeCommand(command: DeviceCommand): Result<CommandResult> {
        val gatt = activeGatt ?: return Result.failure(IllegalStateException("GATT not connected"))
        if (!hasBlePermissions()) return Result.failure(SecurityException("Missing Bluetooth permissions"))

        val hrService = gatt.getService(UUID_HEART_RATE_SERVICE)
        val commandChar = hrService?.getCharacteristic(UUID_COMMAND_CHARACTERISTIC)
            ?: return Result.failure(UnsupportedOperationException("Command characteristic not found on peripheral"))

        return commandMutex.withLock {
            val startTime = System.nanoTime()
            val deferred = CompletableDeferred<Boolean>()
            pendingCommandDeferred = deferred

            try {
                val payload = command.payload ?: command.action.toByteArray(Charsets.UTF_8)
                val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        commandChar,
                        payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    commandChar.value = payload
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(commandChar)
                }

                if (!writeSuccess) {
                    return@withLock Result.success(
                        CommandResult(
                            commandId = command.commandId,
                            success = false,
                            statusMessage = "Failed to initiate GATT write",
                            executionDurationNs = System.nanoTime() - startTime,
                        ),
                    )
                }

                val acknowledged = withTimeoutOrNull(COMMAND_TIMEOUT_MILLIS) {
                    deferred.await()
                } ?: false

                Result.success(
                    CommandResult(
                        commandId = command.commandId,
                        success = acknowledged,
                        statusMessage = if (acknowledged) {
                            "Sent command to peripheral"
                        } else {
                            "Peripheral command timed out awaiting acknowledgement"
                        },
                        executionDurationNs = System.nanoTime() - startTime,
                    ),
                )
            } finally {
                pendingCommandDeferred = null
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal var testForceBlePermissionGranted: Boolean = false

    private fun hasBlePermissions(): Boolean {
        if (testForceBlePermissionGranted) return true
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @androidx.annotation.VisibleForTesting
    internal val testGattCallback: BluetoothGattCallback get() = gattCallback

    @androidx.annotation.VisibleForTesting
    internal var testActiveGatt: BluetoothGatt?
        get() = activeGatt
        set(value) { activeGatt = value }

    @androidx.annotation.VisibleForTesting
    internal var testPendingCommandDeferred: CompletableDeferred<Boolean>?
        get() = pendingCommandDeferred
        set(value) { pendingCommandDeferred = value }

    @androidx.annotation.VisibleForTesting
    internal var testReconnectAttempts: Int
        get() = reconnectAttempts
        set(value) { reconnectAttempts = value }

    @androidx.annotation.VisibleForTesting
    internal var testUserRequestedDisconnect: Boolean
        get() = userRequestedDisconnect
        set(value) { userRequestedDisconnect = value }

    @androidx.annotation.VisibleForTesting
    internal var testLastConnectedDeviceId: String?
        get() = lastConnectedDeviceId
        set(value) { lastConnectedDeviceId = value }

    @androidx.annotation.VisibleForTesting
    internal fun testSetConnectionState(state: DeviceConnectionState) {
        _connectionState.value = state
    }

    @androidx.annotation.VisibleForTesting
    internal fun testHandleUnexpectedDisconnect(gatt: BluetoothGatt?) {
        handleUnexpectedDisconnect(gatt)
    }

    @androidx.annotation.VisibleForTesting
    internal val testDiscoveredSourceKinds get() = discoveredSourceKinds

    companion object {
        val UUID_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_COMMAND_CHARACTERISTIC: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
        const val SIMULATOR_MANUFACTURER_ID = 0xFFFF
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val CONNECTION_TIMEOUT_MILLIS = 15_000L
        const val COMMAND_TIMEOUT_MILLIS = 5_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
        val SIMULATOR_MARKER = byteArrayOf(0x53, 0x53, 0x56, 0x31)
    }
}
