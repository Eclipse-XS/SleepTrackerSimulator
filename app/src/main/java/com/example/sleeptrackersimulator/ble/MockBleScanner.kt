package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.delay

class MockBleScanner(
    private val discoveryDelayMillis: Long = 2_000L,
) {
    suspend fun scan(): List<BleDevice> {
        delay(discoveryDelayMillis)
        return listOf(VIRTUAL_DEVICE)
    }

    companion object {
        val VIRTUAL_DEVICE = BleDevice("Sleep Tracker Hub", "MOCK:00:11:22:33:44")
    }
}
