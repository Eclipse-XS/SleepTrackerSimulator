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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production Android BLE GATT Transport client communicating with genuine Bluetooth peripherals.
 * Adheres strictly to the Bluetooth SIG Heart Rate Profile (0x180D, 0x2A37).
 * Transport authenticity and signal authenticity are tracked separately. Unknown peripherals
 * remain BLE_UNVERIFIED; the companion marker identifies synthetic data but is not authenticated.
 */
class AndroidGattDeviceTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DeviceTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<DeviceInfo?>(null)
    override val connectedDevice: StateFlow<DeviceInfo?> = _connectedDevice.asStateFlow()

    private val _heartRateStream = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val heartRateStream: SharedFlow<HeartRateSample> = _heartRateStream.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private var activeGatt: BluetoothGatt? = null
    private var scanTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private val discoveredSourceKinds = ConcurrentHashMap<String, SourceKind>()

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
                    capabilities = setOf(DeviceCapability.HEART_RATE),
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
                    _connectionState.value = DeviceConnectionState.DISCOVERING_SERVICES
                    if (gatt?.discoverServices() != true) _connectionState.value = DeviceConnectionState.ERROR
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionTimeoutJob?.cancel()
                    _connectionState.value = DeviceConnectionState.DISCONNECTED
                    _connectedDevice.value = null
                    activeGatt?.close()
                    activeGatt = null
                }
            } else {
                connectionTimeoutJob?.cancel()
                _connectionState.value = DeviceConnectionState.ERROR
                _connectedDevice.value = null
                activeGatt?.close()
                activeGatt = null
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
                    // Enable local notification
                    if (!gatt.setCharacteristicNotification(hrChar, true)) {
                        _connectionState.value = DeviceConnectionState.ERROR
                        return
                    }

                    // Write CCCD descriptor to subscribe to notifications on peripheral
                    val descriptor = hrChar.getDescriptor(UUID_CCCD)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) _connectionState.value = DeviceConnectionState.ERROR
                    } else _connectionState.value = DeviceConnectionState.ERROR
                } else {
                    _connectionState.value = DeviceConnectionState.ERROR
                }
            } else {
                _connectionState.value = DeviceConnectionState.ERROR
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (gatt !== activeGatt) return
            if (descriptor?.uuid == UUID_CCCD) {
                _connectionState.value = if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectionTimeoutJob?.cancel()
                    DeviceConnectionState.CONNECTED
                } else DeviceConnectionState.ERROR
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            if (gatt !== activeGatt) return
            if (characteristic?.uuid == UUID_HEART_RATE_MEASUREMENT) {
                val value = characteristic.value ?: return
                parseHeartRateMeasurement(value, gatt?.device?.address ?: "unknown")
            }
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
            _heartRateStream.emit(sample)
        }
    }

    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = _discoveredDevices.asStateFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startScan(): Result<Unit> {
        if (!hasBlePermissions()) {
            return Result.failure(SecurityException("Missing Bluetooth permissions"))
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
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
            return Result.failure(SecurityException("Missing Bluetooth permissions"))
        }

        stopScan()
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        val device = bluetoothAdapter?.getRemoteDevice(deviceId)
            ?: return Result.failure(IllegalArgumentException("Invalid Bluetooth device address: $deviceId"))

        _connectionState.value = DeviceConnectionState.CONNECTING
        activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
            capabilities = setOf(DeviceCapability.HEART_RATE),
        )

        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect(): Result<Unit> {
        connectionTimeoutJob?.cancel()
        if (hasBlePermissions()) {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
        _connectedDevice.value = null
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

        commandChar.value = command.action.toByteArray(Charsets.UTF_8)
        val success = gatt.writeCharacteristic(commandChar)

        return Result.success(
            CommandResult(
                commandId = command.commandId,
                success = success,
                statusMessage = if (success) "Sent command to peripheral" else "Failed to write characteristic",
            ),
        )
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        val UUID_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_COMMAND_CHARACTERISTIC: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
        const val SIMULATOR_MANUFACTURER_ID = 0xFFFF
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val CONNECTION_TIMEOUT_MILLIS = 15_000L
        val SIMULATOR_MARKER = byteArrayOf(0x53, 0x53, 0x56, 0x31)
    }
}
