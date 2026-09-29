package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockBleConnectorTest {

    @Test
    fun `initial state is idle`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        assertEquals(BleConnectionState.Idle, connector.state.value)
        assertTrue(connector.logs.value.isEmpty())
    }

    @Test
    fun `start scan immediately exposes scanning`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        runCurrent()
        assertEquals(BleConnectionState.Scanning, connector.state.value)
        assertTrue(connector.logs.value.any { it.contains("SCAN started") })
    }

    @Test
    fun `device appears after two seconds`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        runCurrent()
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(BleConnectionState.Scanning, connector.state.value)

        advanceTimeBy(1L)
        runCurrent()
        val state = connector.state.value
        assertTrue(state is BleConnectionState.DeviceFound)
        assertEquals(listOf(MockBleScanner.VIRTUAL_DEVICE), (state as BleConnectionState.DeviceFound).devices)
    }

    @Test
    fun `connect transitions through connecting to connected`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()

        val device = MockBleScanner.VIRTUAL_DEVICE
        connector.connect(device)
        runCurrent()
        assertEquals(BleConnectionState.Connecting(device), connector.state.value)
        assertTrue(connector.logs.value.any { it.contains("CONNECTING ${device.name}") })

        advanceTimeBy(750L)
        runCurrent()
        assertEquals(BleConnectionState.Connected(device), connector.state.value)
        assertTrue(connector.logs.value.any { it.contains("CONNECTED ${device.name}") })
        assertTrue(connector.logs.value.any { it.contains("STATUS:READY") })
    }

    @Test
    fun `send data logs write and notification ack`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()
        connector.connect(MockBleScanner.VIRTUAL_DEVICE)
        advanceTimeBy(750L)
        runCurrent()

        connector.sendData("STATUS_REQUEST")
        runCurrent()

        assertTrue(connector.state.value is BleConnectionState.Connected)
        assertTrue(connector.logs.value.any { it.contains("TX ${BleProfile.COMMAND_CHARACTERISTIC_UUID}: STATUS_REQUEST") })
        assertTrue(connector.logs.value.any { it.contains("RX ${BleProfile.STATUS_CHARACTERISTIC_UUID}: ACK:STATUS_REQUEST") })
    }

    @Test
    fun `disconnect cancels connection and exposes disconnected`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()
        connector.connect(MockBleScanner.VIRTUAL_DEVICE)
        runCurrent()
        assertEquals(BleConnectionState.Connecting(MockBleScanner.VIRTUAL_DEVICE), connector.state.value)

        advanceTimeBy(300L)
        runCurrent()
        connector.disconnect()
        runCurrent()

        assertEquals(BleConnectionState.Disconnected(MockBleScanner.VIRTUAL_DEVICE), connector.state.value)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(BleConnectionState.Disconnected(MockBleScanner.VIRTUAL_DEVICE), connector.state.value)
    }

    @Test
    fun `disconnect after connected preserves device identity`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()
        connector.connect(MockBleScanner.VIRTUAL_DEVICE)
        advanceTimeBy(750L)
        runCurrent()

        connector.disconnect()
        runCurrent()

        assertEquals(BleConnectionState.Disconnected(MockBleScanner.VIRTUAL_DEVICE), connector.state.value)
        assertTrue(connector.logs.value.any { it.contains("DISCONNECTED ${MockBleScanner.VIRTUAL_DEVICE.name}") })
    }

    @Test
    fun `double scan while scanning does not restart delay`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(1_000L)
        runCurrent()

        connector.startScan()
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(connector.state.value is BleConnectionState.DeviceFound)
        val foundCount = connector.logs.value.count { it.contains("FOUND") }
        assertEquals(1, foundCount)
    }

    @Test
    fun `repeated scan replaces devices rather than duplicating`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()

        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()

        val state = connector.state.value
        assertTrue(state is BleConnectionState.DeviceFound)
        assertEquals(1, (state as BleConnectionState.DeviceFound).devices.size)
    }

    @Test
    fun `connect without discovery is ignored`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.connect(MockBleScanner.VIRTUAL_DEVICE)
        runCurrent()

        assertEquals(BleConnectionState.Idle, connector.state.value)
        assertFalse(connector.logs.value.any { it.contains("CONNECTED") })
    }

    @Test
    fun `send while disconnected is ignored`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.sendData("HELLO")
        runCurrent()

        assertFalse(connector.logs.value.any { it.contains("TX") })
    }

    @Test
    fun `close cancels pending scan`() = runTest {
        val connectorScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val connector = MockBleConnector(scope = connectorScope, logOutput = { _, _ -> })
        connector.startScan()
        runCurrent()
        assertEquals(BleConnectionState.Scanning, connector.state.value)

        connector.close()
        advanceTimeBy(2_000L)
        runCurrent()

        assertFalse(connector.state.value is BleConnectionState.DeviceFound)
    }

    @Test
    fun `terminal history is bounded`() = runTest {
        val connector = MockBleConnector(scope = this, logOutput = { _, _ -> })
        connector.startScan()
        advanceTimeBy(2_000L)
        runCurrent()
        connector.connect(MockBleScanner.VIRTUAL_DEVICE)
        advanceTimeBy(750L)
        runCurrent()

        for (i in 1..30) {
            connector.sendData("CMD_$i")
        }
        runCurrent()

        assertEquals(50, connector.logs.value.size)
        assertTrue(connector.logs.value.last().contains("ACK:CMD_30"))
    }
}
