package com.example.sleeptrackersimulator.data.ble

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
import java.util.UUID
import kotlin.random.Random

data class PeripheralAdvertiserState(
    val isAdvertising: Boolean = false,
    val connectedCentrals: Int = 0,
    val currentBpm: Int = 68,
    val lastReceivedCommand: String? = null,
    val errorMessage: String? = null,
)

/**
 * Android BLE GATT Peripheral / Server advertising Heart Rate Service (0x180D).
 * Turns an Android device into a genuine test peripheral emitting 0x2A37 notifications.
 */
class VirtualSleepBandAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _state = MutableStateFlow(PeripheralAdvertiserState())
    val state: StateFlow<PeripheralAdvertiserState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    private var streamJob: Job? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _state.value = _state.value.copy(isAdvertising = true, errorMessage = null)
        }

        override fun onStartFailure(errorCode: Int) {
            _state.value = _state.value.copy(
                isAdvertising = false,
                errorMessage = "Advertising failed with error code $errorCode",
            )
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (device == null) return
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                subscribedDevices.add(device)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
            }
            _state.value = _state.value.copy(connectedCentrals = subscribedDevices.size)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            val target = device ?: return
            if (characteristic?.uuid == UUID_BODY_SENSOR_LOCATION) {
                // Return 0x02 (Wrist)
                val response = byteArrayOf(0x02)
                gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            } else {
                gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
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
            if (characteristic?.uuid == UUID_COMMAND_CHARACTERISTIC) {
                val commandStr = value?.toString(Charsets.UTF_8) ?: ""
                _state.value = _state.value.copy(lastReceivedCommand = commandStr)

                if (responseNeeded) {
                    gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
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
            if (descriptor?.uuid == UUID_CCCD) {
                if (responseNeeded) {
                    gattServer?.sendResponse(target, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
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
            val err = "Missing required Bluetooth permissions (BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT)"
            _state.value = _state.value.copy(errorMessage = err)
            return Result.failure(SecurityException(err))
        }

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            ?: return Result.failure(IllegalStateException("BLE Advertising is not supported on this device"))

        // Set up GATT Server
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        setupGattServices()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID_HEART_RATE_SERVICE))
            .build()

        advertiser.startAdvertising(settings, data, advertiseCallback)
        startNotificationLoop()

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
        subscribedDevices.clear()
        _state.value = PeripheralAdvertiserState(isAdvertising = false)
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServices() {
        val service = BluetoothGattService(UUID_HEART_RATE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Heart Rate Measurement (Notify)
        val hrChar = BluetoothGattCharacteristic(
            UUID_HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        val cccd = BluetoothGattDescriptor(
            UUID_CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        hrChar.addDescriptor(cccd)
        service.addCharacteristic(hrChar)

        // Body Sensor Location (Read)
        val locationChar = BluetoothGattCharacteristic(
            UUID_BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(locationChar)

        // Custom Command (Write)
        val commandChar = BluetoothGattCharacteristic(
            UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(commandChar)

        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startNotificationLoop() {
        streamJob?.cancel()
        streamJob = scope.launch {
            var bpm = 65
            while (isActive) {
                delay(1200L)
                bpm = (bpm + Random.nextInt(-2, 3)).coerceIn(55, 95)
                _state.value = _state.value.copy(currentBpm = bpm)

                if (subscribedDevices.isNotEmpty() && hasPermissions()) {
                    val hrChar = gattServer?.getService(UUID_HEART_RATE_SERVICE)
                        ?.getCharacteristic(UUID_HEART_RATE_MEASUREMENT)

                    if (hrChar != null) {
                        // Flags: 0x00 (uint8 format, no energy, no RR)
                        val payload = byteArrayOf(0x00, bpm.toByte())
                        hrChar.value = payload

                        for (device in subscribedDevices) {
                            gattServer?.notifyCharacteristicChanged(device, hrChar, false)
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
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val UUID_COMMAND_CHARACTERISTIC: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
    }
}
