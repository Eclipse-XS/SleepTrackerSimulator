package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.IBleConnector
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

        var scanCalls = 0
        val connectedDevices = mutableListOf<BleDevice>()
        var disconnectCalls = 0
        val sentData = mutableListOf<String>()
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

        override fun sendData(data: String) {
            sentData.add(data)
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
    fun `blank command is not delegated`() {
        val fake = FakeBleConnector()
        val viewModel = BleViewModel(fake)

        viewModel.sendData("")
        viewModel.sendData("   ")

        assertTrue(fake.sentData.isEmpty())
    }

    @Test
    fun `command is trimmed before delegation`() {
        val fake = FakeBleConnector()
        val viewModel = BleViewModel(fake)

        viewModel.sendData("  STATUS_REQUEST  ")

        assertEquals(listOf("STATUS_REQUEST"), fake.sentData)
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
