package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.delay

class MockBleScanner(
    private val discoveryDelayMillis: Long = 2_000L,
    private val virtualDevice: BleDevice = VIRTUAL_DEVICE,
) {
    suspend fun scan(): List<BleDevice> {
        delay(discoveryDelayMillis)
        return listOf(virtualDevice)
    }

    companion object {
        val VIRTUAL_DEVICE = BleDevice("Sleep Band HR", "VIRTUAL:HR:01")
    }
}
