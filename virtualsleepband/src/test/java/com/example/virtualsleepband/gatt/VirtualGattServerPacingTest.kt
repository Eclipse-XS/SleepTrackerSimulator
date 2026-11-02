package com.example.virtualsleepband.gatt

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualGattServerPacingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `autonomous BPM pacing tracks elapsed monotonic time across 250ms interval`() = testScope.runTest {
        var simulatedNowNanos = 1_000_000_000L
        val server = VirtualGattServer(
            context = null,
            scope = testScope,
            monotonicClockNanos = { simulatedNowNanos },
        )
        server.setPacketInterval(250L)
        server.testStartTransmissionLoop()

        // Initial state before time advances
        assertEquals(250L, server.state.value.packetIntervalMs)

        // Advance 250ms
        simulatedNowNanos += 250_000_000L
        advanceTimeBy(250L)
        runCurrent()

        // Advance 750ms more (total 1000ms / 1s)
        simulatedNowNanos += 750_000_000L
        advanceTimeBy(750L)
        runCurrent()

        assertEquals(1L, server.state.value.elapsedSeconds)
        assertTrue(server.state.value.currentBpm in 40..180)

        server.testStopTransmissionLoop()
    }

    @Test
    fun `autonomous BPM pacing tracks elapsed monotonic time across 500ms interval`() = testScope.runTest {
        var simulatedNowNanos = 2_000_000_000L
        val server = VirtualGattServer(
            context = null,
            scope = testScope,
            monotonicClockNanos = { simulatedNowNanos },
        )
        server.setPacketInterval(500L)
        server.testStartTransmissionLoop()

        simulatedNowNanos += 2_000_000_000L // 2 seconds
        advanceTimeBy(2000L)
        runCurrent()

        assertEquals(2L, server.state.value.elapsedSeconds)
        server.testStopTransmissionLoop()
    }

    @Test
    fun `autonomous BPM pacing tracks elapsed monotonic time across 1000ms interval`() = testScope.runTest {
        var simulatedNowNanos = 5_000_000_000L
        val server = VirtualGattServer(
            context = null,
            scope = testScope,
            monotonicClockNanos = { simulatedNowNanos },
        )
        server.setPacketInterval(1000L)
        server.testStartTransmissionLoop()

        simulatedNowNanos += 5_000_000_000L // 5 seconds
        advanceTimeBy(5000L)
        runCurrent()

        assertEquals(5L, server.state.value.elapsedSeconds)
        server.testStopTransmissionLoop()
    }

    @Test
    fun `autonomous BPM pacing tracks elapsed monotonic time across 2000ms interval`() = testScope.runTest {
        var simulatedNowNanos = 10_000_000_000L
        val server = VirtualGattServer(
            context = null,
            scope = testScope,
            monotonicClockNanos = { simulatedNowNanos },
        )
        server.setPacketInterval(2000L)
        server.testStartTransmissionLoop()

        simulatedNowNanos += 6_000_000_000L // 6 seconds
        advanceTimeBy(6000L)
        runCurrent()

        assertEquals(6L, server.state.value.elapsedSeconds)
        server.testStopTransmissionLoop()
    }

    @Test
    fun `command handling accepts ALERT ALERT_PULSE and PING`() {
        val server = VirtualGattServer(context = null, scope = testScope)
        val commandChar = BluetoothGattCharacteristic(
            VirtualGattServer.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // 1. ALERT
        server.testHandleCharacteristicWrite(null, commandChar, "ALERT".toByteArray(Charsets.UTF_8))
        val alertLog = server.state.value.commandLogs.firstOrNull()
        assertTrue(alertLog != null)
        assertTrue(alertLog!!.command.startsWith("ALERT"))
        assertTrue(!alertLog.command.contains("REJECTED"))

        // 2. ALERT_PULSE
        server.testHandleCharacteristicWrite(null, commandChar, "ALERT_PULSE".toByteArray(Charsets.UTF_8))
        val pulseLog = server.state.value.commandLogs.first()
        assertTrue(pulseLog.command.startsWith("ALERT_PULSE"))
        assertTrue(!pulseLog.command.contains("REJECTED"))

        // 3. PING
        server.testHandleCharacteristicWrite(null, commandChar, "PING".toByteArray(Charsets.UTF_8))
        val pingLog = server.state.value.commandLogs.first()
        assertEquals("PING [ACK]", pingLog.command)
    }

    @Test
    fun `unsupported command writes are rejected and logged`() {
        val server = VirtualGattServer(context = null, scope = testScope)
        val commandChar = BluetoothGattCharacteristic(
            VirtualGattServer.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        server.testHandleCharacteristicWrite(null, commandChar, "MALICIOUS_REBOOT".toByteArray(Charsets.UTF_8))
        val rejectedLog = server.state.value.commandLogs.first()
        assertEquals("MALICIOUS_REBOOT [REJECTED_UNSUPPORTED]", rejectedLog.command)
    }

    @Test
    fun `oversized command writes exceeding 32 bytes are rejected and logged`() {
        val server = VirtualGattServer(context = null, scope = testScope)
        val commandChar = BluetoothGattCharacteristic(
            VirtualGattServer.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val oversizedPayload = ByteArray(33) { 0x41 }
        server.testHandleCharacteristicWrite(null, commandChar, oversizedPayload)
        val rejectedLog = server.state.value.commandLogs.first()
        assertEquals("[REJECTED_LENGTH_33_BYTES]", rejectedLog.command)
    }

    @Test
    fun `repeated ALERT commands within 2000ms are throttled to prevent actuator DOS`() {
        var simulatedNowNanos = 10_000_000_000L
        val server = VirtualGattServer(
            context = null,
            scope = testScope,
            monotonicClockNanos = { simulatedNowNanos },
        )
        val commandChar = BluetoothGattCharacteristic(
            VirtualGattServer.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // 1. Initial ALERT at t=0ms succeeds
        server.testHandleCharacteristicWrite(null, commandChar, "ALERT".toByteArray(Charsets.UTF_8))
        val firstLog = server.state.value.commandLogs.first()
        assertTrue(firstLog.command.startsWith("ALERT"))
        assertTrue(!firstLog.command.contains("THROTTLED"))

        // 2. Second ALERT at t=500ms is throttled
        simulatedNowNanos += 500_000_000L // 500ms
        server.testHandleCharacteristicWrite(null, commandChar, "ALERT".toByteArray(Charsets.UTF_8))
        val throttledLog = server.state.value.commandLogs.first()
        assertTrue(throttledLog.command.startsWith("ALERT [THROTTLED_RATE_LIMITED"))

        // 3. Third ALERT at t=2100ms (elapsed 2100ms from initial) succeeds
        simulatedNowNanos += 1_600_000_000L // total 2100ms from t=0
        server.testHandleCharacteristicWrite(null, commandChar, "ALERT".toByteArray(Charsets.UTF_8))
        val allowedLog = server.state.value.commandLogs.first()
        assertTrue(allowedLog.command.startsWith("ALERT"))
        assertTrue(!allowedLog.command.contains("THROTTLED"))
    }

    @Test
    fun `peripheral vibration gracefully reports no hardware when context or vibrator is absent`() {
        val server = VirtualGattServer(context = null, scope = testScope)
        val result = server.testTriggerPeripheralVibration(300L)
        assertEquals("EXECUTED_NO_VIBRATOR_HARDWARE", result)
    }
}
