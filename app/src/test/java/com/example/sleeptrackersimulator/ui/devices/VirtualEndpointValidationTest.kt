package com.example.sleeptrackersimulator.ui.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualEndpointValidationTest {
    @Test fun `accepts supported development topologies`() {
        listOf(
            "ws://127.0.0.1:8765",
            "ws://10.0.2.2:8765",
            "ws://192.168.1.42:8765",
        ).forEach { value -> assertEquals(value, DevicesViewModel.validateEndpoint(value).getOrThrow().toString()) }
    }

    @Test fun `rejects insecure ambiguity and unsupported schemes`() {
        listOf(
            "http://127.0.0.1:8765",
            "wss://127.0.0.1:8765",
            "ws://127.0.0.1",
            "ws://user@127.0.0.1:8765",
            "ws://127.0.0.1:8765/path",
        ).forEach { value -> assertTrue(value, DevicesViewModel.validateEndpoint(value).isFailure) }
    }
}
