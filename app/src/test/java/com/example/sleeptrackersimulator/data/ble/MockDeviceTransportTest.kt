package com.example.sleeptrackersimulator.data.ble

import com.example.sleeptrackersimulator.core.model.DeviceCapability
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.SourceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockDeviceTransportTest {

    @Test
    fun initialConnectionStateIsDisconnected() = runTest {
        val transport = MockDeviceTransport(
            scope = backgroundScope,
            notificationIntervalMillis = 500L,
            initialBpm = 64,
        )
        assertEquals(DeviceConnectionState.DISCONNECTED, transport.connectionState.value)
        assertNull(transport.connectedDevice.value)
    }

    @Test
    fun startScanEmitsSimulatedDeviceWithCorrectCapabilities() = runTest {
        val transport = MockDeviceTransport(
            scope = backgroundScope,
            notificationIntervalMillis = 500L,
            initialBpm = 64,
        )
        val scanResult = transport.startScan()
        assertTrue(scanResult.isSuccess)

        advanceTimeBy(500L)

        val devices = transport.observeDiscoveredDevices().first()
        assertEquals(1, devices.size)
        val device = devices.first()

        assertEquals("VIRTUAL:HR:01", device.deviceId)
        assertEquals(SourceKind.SIMULATED, device.sourceKind)
        assertTrue(device.capabilities.contains(DeviceCapability.HEART_RATE))
        assertTrue(device.capabilities.contains(DeviceCapability.VIBRATION_ALERT))
    }

    @Test
    fun connectEstablishesConnectionAndStreamsTelemetry() = runTest {
        val transport = MockDeviceTransport(
            scope = backgroundScope,
            notificationIntervalMillis = 500L,
            initialBpm = 64,
        )
        val connectResult = transport.connect("VIRTUAL:HR:01")
        assertTrue(connectResult.isSuccess)

        advanceTimeBy(400L)
        assertEquals(DeviceConnectionState.CONNECTED, transport.connectionState.value)
        assertNotNull(transport.connectedDevice.value)

        advanceTimeBy(1200L)

        val sample = transport.heartRateStream.first()
        assertEquals(SourceKind.SIMULATED, sample.sourceKind)
        assertTrue(sample.bpm in 45..90)
    }

    @Test
    fun disconnectTearsDownConnectionAndClearsDevice() = runTest {
        val transport = MockDeviceTransport(
            scope = backgroundScope,
            notificationIntervalMillis = 500L,
            initialBpm = 64,
        )
        transport.connect("VIRTUAL:HR:01")
        advanceTimeBy(400L)
        assertEquals(DeviceConnectionState.CONNECTED, transport.connectionState.value)

        val disconnectResult = transport.disconnect()
        assertTrue(disconnectResult.isSuccess)

        assertEquals(DeviceConnectionState.DISCONNECTED, transport.connectionState.value)
        assertNull(transport.connectedDevice.value)
    }

    @Test
    fun executeCommandFailsWhenDisconnected() = runTest {
        val transport = MockDeviceTransport(
            scope = backgroundScope,
            notificationIntervalMillis = 500L,
            initialBpm = 64,
        )
        val result = transport.executeCommand(
            DeviceCommand(targetDeviceId = "VIRTUAL:HR:01", action = "ALERT"),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun executeCommandSucceedsWhenConnected() = runTest {
        val transport = MockDeviceTransport(
            scope = backgroundScope,
            notificationIntervalMillis = 500L,
            initialBpm = 64,
        )
        transport.connect("VIRTUAL:HR:01")
        advanceTimeBy(400L)

        val alertResult = transport.executeCommand(
            DeviceCommand(targetDeviceId = "VIRTUAL:HR:01", action = "ALERT"),
        )
        assertTrue(alertResult.isSuccess)
        assertTrue(alertResult.getOrThrow().success)

        val streamStopResult = transport.executeCommand(
            DeviceCommand(targetDeviceId = "VIRTUAL:HR:01", action = "STOP_STREAM"),
        )
        assertTrue(streamStopResult.isSuccess)
    }
}
