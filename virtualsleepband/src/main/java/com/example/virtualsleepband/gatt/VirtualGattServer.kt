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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    val commandLogs: List<CommandLogEntry> = emptyList(),
    val errorMessage: String? = null,
)

class VirtualGattServer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BandPeripheralState())
    val state: StateFlow<BandPeripheralState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private var streamJob: Job? = null
    private val syntheticHeartRate = SimulatedHeartRate()
    private var simulationSeconds = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = _state.value.copy(isAdvertising = true, errorMessage = null)
        }

        override fun onStartFailure(errorCode: Int) {
            _state.value = _state.value.copy(
                isAdvertising = false,
                errorMessage = "Advertising failed: error code $errorCode",
            )
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevices.add(device)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
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
            val target = device ?: return
            val commandText = value?.let { String(it, Charsets.UTF_8) } ?: "<empty>"
            val logEntry = CommandLogEntry(
                timestamp = timeFormat.format(Date()),
                sourceDevice = target.address ?: "Central",
                characteristicUuid = characteristic?.uuid?.toString()?.take(8) ?: "Unknown",
                command = commandText,
            )

            val updatedLogs = (listOf(logEntry) + _state.value.commandLogs).take(50)
            _state.value = _state.value.copy(commandLogs = updatedLogs)

            if (responseNeeded) {
                gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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
            if (responseNeeded) {
                gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(): Result<Unit> {
        if (!hasPermissions()) {
            val msg = "Missing required Bluetooth permissions"
            _state.value = _state.value.copy(errorMessage = msg)
            return Result.failure(SecurityException(msg))
        }

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            ?: return Result.failure(IllegalStateException("BLE Advertising is not supported on this hardware"))

        // Set up GATT Server
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        setupServices()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        simulationSeconds = 0L
        startTransmissionLoop()

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
        connectedDevices.clear()
        _state.value = _state.value.copy(isAdvertising = false, connectedCentrals = 0)
    }

    fun setPacketInterval(intervalMs: Long) {
        _state.value = _state.value.copy(packetIntervalMs = intervalMs)
        if (_state.value.isAdvertising) {
            startTransmissionLoop()
        }
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
    private fun setupServices() {
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
        gattServer?.addService(hrService)

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
        gattServer?.addService(batService)
    }

    @SuppressLint("MissingPermission")
    private fun startTransmissionLoop() {
        streamJob?.cancel()
        streamJob = scope.launch {
            while (isActive) {
                delay(_state.value.packetIntervalMs)
                simulationSeconds += (_state.value.packetIntervalMs / 1000L).coerceAtLeast(1L)
                val emittedBpm = syntheticHeartRate.bpm(simulationSeconds)
                val battery = syntheticHeartRate.batteryPercent(simulationSeconds)
                _state.value = _state.value.copy(currentBpm = emittedBpm, batteryLevel = battery)

                if (connectedDevices.isNotEmpty() && hasPermissions()) {
                    val hrChar = gattServer?.getService(UUID_HEART_RATE_SERVICE)
                        ?.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)

                    if (hrChar != null) {
                        val payload = byteArrayOf(0x00, emittedBpm.toByte())
                        hrChar.value = payload
                        for (dev in connectedDevices) {
                            gattServer?.notifyCharacteristicChanged(dev, hrChar, false)
                        }
                    }
                }
            }
        }
    }

    companion object {
        val UUID_HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val UUID_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val UUID_BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val UUID_BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val UUID_BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_COMMAND_CHARACTERISTIC: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
    }
}
