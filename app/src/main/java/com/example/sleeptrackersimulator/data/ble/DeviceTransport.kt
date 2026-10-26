package com.example.sleeptrackersimulator.data.ble

import com.example.sleeptrackersimulator.core.model.CommandResult
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Universal transport abstraction for BLE peripherals (Mock, Replay, and Android GATT).
 */
interface DeviceTransport {
    val connectionState: StateFlow<DeviceConnectionState>
    val connectedDevice: StateFlow<DeviceInfo?>
    val heartRateStream: SharedFlow<HeartRateSample>

    fun observeDiscoveredDevices(): Flow<List<DeviceInfo>>

    suspend fun startScan(): Result<Unit>
    suspend fun stopScan(): Result<Unit>
    suspend fun connect(deviceId: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun executeCommand(command: DeviceCommand): Result<CommandResult>
}
