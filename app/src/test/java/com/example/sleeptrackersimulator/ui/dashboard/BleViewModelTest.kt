package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.IBleConnector
import com.example.sleeptrackersimulator.ble.BleOperationResult
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleViewModelTest {

    private class FakeBleConnector : IBleConnector {
        val stateFlow = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
        val logsFlow = MutableStateFlow<List<String>>(emptyList())

        override val state: StateFlow<BleConnectionState> = stateFlow
        override val logs: StateFlow<List<String>> = logsFlow
        override val heartRate = MutableStateFlow<HeartRateMeasurement?>(null)
        override val heartRateHistory = MutableStateFlow<List<Int>>(emptyList())
        override val notificationsEnabled = MutableStateFlow(false)

        var scanCalls = 0
        val connectedDevices = mutableListOf<BleDevice>()
        var disconnectCalls = 0
        val commands = mutableListOf<String>()
        var readCalls = 0
        var closeCalls = 0

        override fun startScan() {
            scanCalls++
        }

        override fun connect(device: BleDevice) {
            connectedDevices.add(device)
        }

        override fun disconnect() {
            disconnectCalls++
        }

        override fun readBodySensorLocation(): BleOperationResult {
            readCalls++
            return BleOperationResult.Success("Wrist")
        }

        override fun writeCommand(command: String): BleOperationResult {
            commands.add(command)
            return BleOperationResult.Success("OK")
        }

        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun `actions delegate to connector`() {
        val fake = FakeBleConnector()
        val viewModel = BleViewModel(fake)
        val device = BleDevice("Test Device", "MOCK:AA:BB:CC")

        viewModel.startScan()
        viewModel.connect(device)
        viewModel.disconnect()

        assertEquals(1, fake.scanCalls)
        assertEquals(listOf(device), fake.connectedDevices)
        assertEquals(1, fake.disconnectCalls)
    }

    @Test
    fun `read and trimmed write delegate to connector`() {
        val fake = FakeBleConnector()
        val viewModel = BleViewModel(fake)

        viewModel.readBodySensorLocation()
        viewModel.writeCommand("  REQUEST_STATUS  ")

        assertEquals(1, fake.readCalls)
        assertEquals(listOf("REQUEST_STATUS"), fake.commands)
    }

    @Test
    fun `clearing ViewModel closes connector`() {
        val fake = FakeBleConnector()
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, BleViewModel.factory(fake))
        provider[BleViewModel::class.java]

        assertEquals(0, fake.closeCalls)
        store.clear()
        assertEquals(1, fake.closeCalls)
    }
}
