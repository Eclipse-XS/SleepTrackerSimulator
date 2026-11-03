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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class BleMode { REAL, VIRTUAL_WEBSOCKET }

/** Single application BLE gateway shared by Devices and RecordingService. */
class SwitchingDeviceTransport(
    private val real: DeviceTransport,
    virtualWebSocket: DeviceTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DeviceTransport {
    private val _mode = MutableStateFlow(BleMode.REAL)
    private val _virtualWebSocket = MutableStateFlow(virtualWebSocket)
    private val operationMutex = Mutex()
    val mode: StateFlow<BleMode> = _mode
    val virtualConnectionFailure: StateFlow<String?> = _virtualWebSocket
        .flatMapLatest { transport ->
            (transport as? WebSocketWearableTransport)?.connectionFailure ?: MutableStateFlow(null)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val active: DeviceTransport get() = if (_mode.value == BleMode.REAL) real else _virtualWebSocket.value

    override val connectionState: StateFlow<DeviceConnectionState> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.connectionState else _virtualWebSocket.flatMapLatest { transport -> transport.connectionState } }
        .stateIn(scope, SharingStarted.Eagerly, DeviceConnectionState.DISCONNECTED)

    override val connectedDevice: StateFlow<DeviceInfo?> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.connectedDevice else _virtualWebSocket.flatMapLatest { transport -> transport.connectedDevice } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val heartRateStream: SharedFlow<HeartRateSample> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.heartRateStream else _virtualWebSocket.flatMapLatest { transport -> transport.heartRateStream } }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val lastHeartRate: StateFlow<HeartRateSample?> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.lastHeartRate else _virtualWebSocket.flatMapLatest { transport -> transport.lastHeartRate } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = _mode
        .flatMapLatest { if (it == BleMode.REAL) real.observeDiscoveredDevices() else _virtualWebSocket.flatMapLatest { transport -> transport.observeDiscoveredDevices() } }

    suspend fun replaceVirtualWebSocket(replacement: DeviceTransport) {
        operationMutex.withLock {
            _virtualWebSocket.value.disconnect()
            _virtualWebSocket.value = replacement
        }
    }

    suspend fun selectMode(mode: BleMode) {
        operationMutex.withLock {
            if (_mode.value == mode) return
            active.disconnect()
            _mode.value = mode
        }
    }

    override suspend fun startScan() = operationMutex.withLock { active.startScan() }
    override suspend fun stopScan() = operationMutex.withLock { active.stopScan() }
    override suspend fun connect(deviceId: String) = operationMutex.withLock { active.connect(deviceId) }
    override suspend fun disconnect() = operationMutex.withLock { active.disconnect() }
    override suspend fun executeCommand(command: DeviceCommand): Result<CommandResult> = operationMutex.withLock { active.executeCommand(command) }
}
