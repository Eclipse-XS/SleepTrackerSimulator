package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockBleConnectorTest {
    private fun connector() = MockBleConnector(
        scope = activeScope!!,
        connectionDelayMillis = 100,
        notificationIntervalMillis = 100,
        variation = HeartRateVariation { 1 },
        logOutput = { _, _ -> },
    )
    private var activeScope: kotlinx.coroutines.CoroutineScope? = null

    @Test fun `initial state is idle`() = runTest {
        activeScope = backgroundScope; val subject = connector()
        assertEquals(BleConnectionState.Idle, subject.state.value)
        assertTrue(subject.heartRateHistory.value.isEmpty())
    }

    @Test fun `scan discovers virtual band once after configured delay`() = runTest {
        activeScope = this
        val subject = MockBleConnector(scanner = MockBleScanner(200), scope = this, logOutput = { _, _ -> })
        subject.startScan(); runCurrent()
        assertEquals(BleConnectionState.Scanning, subject.state.value)
        advanceTimeBy(200); runCurrent()
        val devices = (subject.state.value as BleConnectionState.DeviceFound).devices
        assertEquals(listOf(MockBleScanner.VIRTUAL_DEVICE), devices)
        subject.startScan(); advanceTimeBy(200); runCurrent()
        assertEquals(1, (subject.state.value as BleConnectionState.DeviceFound).devices.size)
    }

    @Test fun `connection discovers service subscribes and streams bounded smooth heart rate`() = runTest {
        activeScope = backgroundScope; val subject = connector()
        subject.startScan(); advanceTimeBy(2_000); runCurrent()
        subject.connect(MockBleScanner.VIRTUAL_DEVICE); runCurrent()
        assertTrue(subject.state.value is BleConnectionState.Connecting)
        advanceTimeBy(100); runCurrent()
        assertTrue(subject.state.value is BleConnectionState.Connected)
        assertTrue(subject.notificationsEnabled.value)
        assertTrue(subject.logs.value.any { it == "SERVICE 180D discovered" })
        assertTrue(subject.logs.value.any { it == "SUBSCRIBED characteristic 2A37" })
        advanceTimeBy(7_000); runCurrent()
        assertEquals(MockBleConnector.MAX_HISTORY_SIZE, subject.heartRateHistory.value.size)
        assertTrue(subject.heartRateHistory.value.all { it in 45..90 })
        assertTrue(subject.heartRateHistory.value.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) <= 3 })
    }

    @Test fun `disconnect stops notifications`() = runTest {
        activeScope = backgroundScope; val subject = connector()
        subject.startScan(); advanceTimeBy(2_000); subject.connect(MockBleScanner.VIRTUAL_DEVICE)
        advanceTimeBy(200); runCurrent()
        subject.disconnect(); val count = subject.heartRateHistory.value.size
        advanceTimeBy(1_000); runCurrent()
        assertFalse(subject.notificationsEnabled.value)
        assertEquals(count, subject.heartRateHistory.value.size)
    }

    @Test fun `read and typed writes return meaningful results`() = runTest {
        activeScope = backgroundScope; val subject = connector()
        assertEquals(BleOperationResult.Error("NOT_CONNECTED"), subject.readBodySensorLocation())
        subject.startScan(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        subject.connect(MockBleScanner.VIRTUAL_DEVICE); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(BleOperationResult.Success("Wrist"), subject.readBodySensorLocation())
        assertEquals(BleOperationResult.Success("STREAM_STOPPED"), subject.writeCommand("STOP_STREAM"))
        assertFalse(subject.notificationsEnabled.value)
        assertEquals(BleOperationResult.Success("STREAM_STARTED"), subject.writeCommand("START_STREAM"))
        assertEquals(BleOperationResult.Error("UNKNOWN_COMMAND"), subject.writeCommand("HELLO"))
    }

    @Test fun `connect without discovery is rejected and close cancels scan`() = runTest {
        activeScope = backgroundScope; val subject = connector()
        subject.connect(MockBleScanner.VIRTUAL_DEVICE)
        assertEquals(BleConnectionState.Idle, subject.state.value)
        assertTrue(subject.logs.value.last().contains("rejected"))
        subject.startScan(); subject.close(); advanceTimeBy(3_000); runCurrent()
        assertFalse(subject.state.value is BleConnectionState.DeviceFound)
    }
}
