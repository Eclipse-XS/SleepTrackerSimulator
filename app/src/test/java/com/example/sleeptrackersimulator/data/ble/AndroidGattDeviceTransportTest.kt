package com.example.sleeptrackersimulator.data.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGattDeviceTransportTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createTransport(): AndroidGattDeviceTransport {
        return AndroidGattDeviceTransport(
            context = null,
            scope = testScope,
        ).apply {
            testForceBlePermissionGranted = true
        }
    }

    @Test
    fun `successful onCharacteristicWrite acknowledgement completes deferred with true`() = runTest {
        val transport = createTransport()
        val deferred = CompletableDeferred<Boolean>()
        transport.testPendingCommandDeferred = deferred

        val commandChar = BluetoothGattCharacteristic(
            AndroidGattDeviceTransport.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        transport.testGattCallback.onCharacteristicWrite(
            null,
            commandChar,
            BluetoothGatt.GATT_SUCCESS,
        )

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.await())
    }

    @Test
    fun `failed onCharacteristicWrite status completes deferred with false`() = runTest {
        val transport = createTransport()
        val deferred = CompletableDeferred<Boolean>()
        transport.testPendingCommandDeferred = deferred

        val commandChar = BluetoothGattCharacteristic(
            AndroidGattDeviceTransport.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        transport.testGattCallback.onCharacteristicWrite(
            null,
            commandChar,
            BluetoothGatt.GATT_FAILURE,
        )

        assertTrue(deferred.isCompleted)
        assertFalse(deferred.await())
    }

    @Test
    fun `write initiation fails immediately when GATT is not connected`() = runTest {
        val transport = createTransport()
        val result = transport.executeCommand(DeviceCommand(targetDeviceId = "dev-1", action = "ALERT"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("GATT not connected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `disconnect during pending write cancels deferred and unblocks caller`() = runTest {
        val transport = createTransport()
        val deferred = CompletableDeferred<Boolean>()
        transport.testPendingCommandDeferred = deferred
        transport.testSetConnectionState(DeviceConnectionState.CONNECTED)

        transport.testGattCallback.onConnectionStateChange(
            null,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED,
        )

        assertTrue(deferred.isCompleted)
        assertFalse(deferred.await())
        assertNull(transport.testPendingCommandDeferred)
    }

    @Test
    fun `duplicate onCharacteristicWrite callbacks are idempotent and do not crash`() = runTest {
        val transport = createTransport()
        val deferred = CompletableDeferred<Boolean>()
        transport.testPendingCommandDeferred = deferred

        val commandChar = BluetoothGattCharacteristic(
            AndroidGattDeviceTransport.UUID_COMMAND_CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        transport.testGattCallback.onCharacteristicWrite(null, commandChar, BluetoothGatt.GATT_SUCCESS)
        transport.testGattCallback.onCharacteristicWrite(null, commandChar, BluetoothGatt.GATT_SUCCESS)
        transport.testGattCallback.onCharacteristicWrite(null, commandChar, BluetoothGatt.GATT_FAILURE)

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.await())
    }

    @Test
    fun `unexpected disconnect triggers backoff and exhausts after max attempts`() = runTest {
        val transport = createTransport()
        val deviceId = "AA:BB:CC:DD:EE:FF"
        transport.testLastConnectedDeviceId = deviceId
        transport.testUserRequestedDisconnect = false
        assertEquals(0, transport.testReconnectAttempts)

        // Attempt 1
        transport.testHandleUnexpectedDisconnect(null)
        assertEquals(1, transport.testReconnectAttempts)
        assertEquals(DeviceConnectionState.RECONNECTING, transport.connectionState.value)

        // Attempt 2
        transport.testHandleUnexpectedDisconnect(null)
        assertEquals(2, transport.testReconnectAttempts)
        assertEquals(DeviceConnectionState.RECONNECTING, transport.connectionState.value)

        // Attempt 3
        transport.testHandleUnexpectedDisconnect(null)
        assertEquals(3, transport.testReconnectAttempts)
        assertEquals(DeviceConnectionState.RECONNECTING, transport.connectionState.value)

        // Attempt 4 -> Max exceeded, transition to ERROR
        transport.testHandleUnexpectedDisconnect(null)
        assertEquals(3, transport.testReconnectAttempts)
        assertEquals(DeviceConnectionState.ERROR, transport.connectionState.value)
        assertNull(transport.connectedDevice.value)
    }

    @Test
    fun `user requested disconnect suppresses automatic reconnect`() = runTest {
        val transport = createTransport()
        transport.testLastConnectedDeviceId = "AA:BB:CC:DD:EE:FF"
        transport.testSetConnectionState(DeviceConnectionState.CONNECTED)

        transport.disconnect()

        assertEquals(DeviceConnectionState.DISCONNECTED, transport.connectionState.value)
        assertTrue(transport.testUserRequestedDisconnect)
        assertNull(transport.testLastConnectedDeviceId)
        assertEquals(0, transport.testReconnectAttempts)

        // Simulating subsequent async onConnectionStateChange from hardware
        transport.testGattCallback.onConnectionStateChange(
            null,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED,
        )

        // Must remain DISCONNECTED, not transition to RECONNECTING
        assertEquals(DeviceConnectionState.DISCONNECTED, transport.connectionState.value)
    }

    @Test
    fun `CCCD descriptor write success transitions to CONNECTED and resets reconnect counter`() = runTest {
        val transport = createTransport()
        transport.testSetConnectionState(DeviceConnectionState.SUBSCRIBING)
        transport.testReconnectAttempts = 2

        val descriptor = BluetoothGattDescriptor(
            AndroidGattDeviceTransport.UUID_CCCD,
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )

        transport.testGattCallback.onDescriptorWrite(
            null,
            descriptor,
            BluetoothGatt.GATT_SUCCESS,
        )

        assertEquals(DeviceConnectionState.CONNECTED, transport.connectionState.value)
        assertEquals(0, transport.testReconnectAttempts)
    }

    @Test
    fun `stale heart rate packets arriving when not connected are ignored`() = runTest {
        val transport = createTransport()
        transport.testSetConnectionState(DeviceConnectionState.DISCONNECTED)

        val hrChar = BluetoothGattCharacteristic(
            AndroidGattDeviceTransport.UUID_HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        ).apply {
            value = byteArrayOf(0x00, 75.toByte())
        }

        @Suppress("DEPRECATION")
        transport.testGattCallback.onCharacteristicChanged(null, hrChar)

        // Verify nothing was emitted to the stream
        assertEquals(0, transport.heartRateStream.replayCache.size)
    }

    @Test
    fun `valid heart rate packets when connected are parsed and emitted with correct provenance`() = testScope.runTest {
        val transport = createTransport()
        transport.testSetConnectionState(DeviceConnectionState.CONNECTED)
        val deviceAddress = "AA:BB:CC:DD:EE:11"

        val sampleDeferred = async { transport.heartRateStream.first() }

        transport.parseHeartRateMeasurement(byteArrayOf(0x00, 78.toByte()), deviceAddress)
        testScheduler.runCurrent()

        val sample = sampleDeferred.await()
        assertEquals(78, sample.bpm)
        assertEquals(deviceAddress, sample.deviceId)
        assertEquals("active_session", sample.sessionId)
        assertTrue(sample.utcTimestampMillis > 0L)
    }
}
