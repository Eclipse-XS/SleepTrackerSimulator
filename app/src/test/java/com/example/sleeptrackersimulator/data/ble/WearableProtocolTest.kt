package com.example.sleeptrackersimulator.data.ble

import com.example.wearableprotocol.WearableProtocol
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableProtocolTest {
    private val now = 1_800_000_000_000L

    @Test fun `valid HR round trips`() {
        val message = WearableProtocol.Envelope(
            sessionId = "session", deviceId = "band", messageId = "sample-1", sequenceNumber = 1,
            timestamp = now, eventType = WearableProtocol.EventType.HEART_RATE_SAMPLE,
            payload = JsonObject().apply { addProperty("bpm", 72) },
        )
        val decoded = WearableProtocol.decode(WearableProtocol.encode(message), now)
        assertTrue(decoded is WearableProtocol.DecodeResult.Valid)
        assertEquals(72, (decoded as WearableProtocol.DecodeResult.Valid).message.payload.get("bpm").asInt)
    }

    @Test fun `invalid HR is rejected`() {
        val raw = WearableProtocol.encode(WearableProtocol.Envelope(
            sessionId = "session", deviceId = "band", messageId = "sample-1", sequenceNumber = 1,
            timestamp = now, eventType = WearableProtocol.EventType.HEART_RATE_SAMPLE,
            payload = JsonObject().apply { addProperty("bpm", 400) },
        ))
        assertEquals(WearableProtocol.DecodeResult.Invalid("INVALID_BPM"), WearableProtocol.decode(raw, now))
    }

    @Test fun `oversized and malformed messages are rejected`() {
        assertEquals(WearableProtocol.DecodeResult.Invalid("MESSAGE_TOO_LARGE"), WearableProtocol.decode("x".repeat(WearableProtocol.MAX_MESSAGE_CHARS + 1), now))
        assertEquals(WearableProtocol.DecodeResult.Invalid("MALFORMED_JSON"), WearableProtocol.decode("not-json", now))
    }
}
