package com.example.sleeptrackersimulator.data.ble

import com.example.sleeptrackersimulator.core.model.CommandResult
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingDeviceTransportTest {
    @Test fun modeSwitchDisconnectsPreviousTransportAndRoutesCommands() = runTest {
        val real = FakeTransport()
        val demo = FakeTransport()
        val gateway = SwitchingDeviceTransport(real, demo, TestScope(StandardTestDispatcher(testScheduler)))
        gateway.startScan()
        assertEquals(1, real.scans)
        gateway.selectMode(BleMode.DEMO)
        gateway.startScan()
        assertEquals(1, real.disconnects)
        assertEquals(1, demo.scans)
        assertEquals(BleMode.DEMO, gateway.mode.value)
    }
}

private class FakeTransport : DeviceTransport {
    var scans = 0
    var disconnects = 0
    override val connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectedDevice = MutableStateFlow<DeviceInfo?>(null)
    override val heartRateStream: SharedFlow<HeartRateSample> = MutableSharedFlow()
    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = MutableStateFlow(emptyList())
    override suspend fun startScan(): Result<Unit> { scans++; return Result.success(Unit) }
    override suspend fun stopScan() = Result.success(Unit)
    override suspend fun connect(deviceId: String) = Result.success(Unit)
    override suspend fun disconnect(): Result<Unit> { disconnects++; return Result.success(Unit) }
    override suspend fun executeCommand(command: DeviceCommand) = Result.success(CommandResult(command.commandId, true, "ok"))
}
