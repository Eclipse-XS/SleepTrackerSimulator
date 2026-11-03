package com.example.wearableprotocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object WearableProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_CHARS = 8_192
    const val MIN_HEART_RATE = 30
    const val MAX_HEART_RATE = 240
    const val HEART_RATE_STALE_MILLIS = 10_000L
    const val HEARTBEAT_TIMEOUT_MILLIS = 15_000L

    enum class EventType { HELLO, DEVICE_INFO, HEART_RATE_SAMPLE, HEARTBEAT, ACTUATOR_COMMAND, COMMAND_ACK, COMMAND_RESULT, ERROR, DISCONNECT }

    data class Envelope(
        val protocolVersion: Int = VERSION,
        val sessionId: String,
        val deviceId: String,
        val messageId: String,
        val sequenceNumber: Long,
        val timestamp: Long,
        val eventType: EventType,
        val payload: JsonObject = JsonObject(),
    )

    sealed interface DecodeResult {
        data class Valid(val message: Envelope) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    private val gson = Gson()

    fun encode(message: Envelope): String = gson.toJson(message)

    fun decode(raw: String, nowUtcMillis: Long = System.currentTimeMillis()): DecodeResult {
        if (raw.length > MAX_MESSAGE_CHARS) return DecodeResult.Invalid("MESSAGE_TOO_LARGE")
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            return DecodeResult.Invalid("MALFORMED_JSON")
        }
        val message = runCatching { gson.fromJson(root, Envelope::class.java) }.getOrElse {
            return DecodeResult.Invalid("INVALID_SCHEMA")
        }
        if (message.protocolVersion != VERSION) return DecodeResult.Invalid("UNSUPPORTED_PROTOCOL")
        if (message.sessionId.isBlank() || message.deviceId.isBlank() || message.messageId.isBlank()) {
            return DecodeResult.Invalid("MISSING_IDENTITY")
        }
        if (message.sequenceNumber < 0L) return DecodeResult.Invalid("INVALID_SEQUENCE")
        if (message.timestamp !in (nowUtcMillis - 86_400_000L)..(nowUtcMillis + 60_000L)) {
            return DecodeResult.Invalid("INVALID_TIMESTAMP")
        }
        if (message.eventType == EventType.HEART_RATE_SAMPLE) {
            val bpm = message.payload.get("bpm")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: return DecodeResult.Invalid("MISSING_BPM")
            if (bpm !in MIN_HEART_RATE..MAX_HEART_RATE) return DecodeResult.Invalid("INVALID_BPM")
        }
        return DecodeResult.Valid(message)
    }
}
