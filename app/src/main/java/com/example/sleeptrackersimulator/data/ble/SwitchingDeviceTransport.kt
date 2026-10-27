@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.example.sleeptrackersimulator.data.ble

import com.example.sleeptrackersimulator.core.model.CommandResult
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn

enum class BleMode { REAL, DEMO }

/** Single application BLE gateway shared by Devices and RecordingService. */
class SwitchingDeviceTransport(
    private val real: DeviceTransport,
    private val demo: DeviceTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DeviceTransport {
    private val _mode = MutableStateFlow(BleMode.REAL)
    val mode: StateFlow<BleMode> = _mode

    private val active: DeviceTransport get() = if (_mode.value == BleMode.REAL) real else demo

    override val connectionState: StateFlow<DeviceConnectionState> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.connectionState else demo.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, DeviceConnectionState.DISCONNECTED)

    override val connectedDevice: StateFlow<DeviceInfo?> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.connectedDevice else demo.connectedDevice }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val heartRateStream: SharedFlow<HeartRateSample> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.heartRateStream else demo.heartRateStream }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.observeDiscoveredDevices() else demo.observeDiscoveredDevices() }

    suspend fun selectMode(mode: BleMode) {
        if (_mode.value == mode) return
        active.disconnect()
        _mode.value = mode
    }

    override suspend fun startScan() = active.startScan()
    override suspend fun stopScan() = active.stopScan()
    override suspend fun connect(deviceId: String) = active.connect(deviceId)
    override suspend fun disconnect() = active.disconnect()
    override suspend fun executeCommand(command: DeviceCommand): Result<CommandResult> = active.executeCommand(command)
}
