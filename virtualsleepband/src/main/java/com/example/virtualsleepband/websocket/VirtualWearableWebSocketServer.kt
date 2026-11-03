package com.example.virtualsleepband.websocket

import com.example.virtualsleepband.gatt.SimulatedHeartRate
import com.example.wearableprotocol.WearableProtocol
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Local-network RFC 6455 endpoint. Synthetic HR is generated only in this APK. */
class VirtualWearableWebSocketServer(
    private val scope: CoroutineScope,
    port: Int = 8765,
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {
    init {
        // A force-stop closes accepted sockets abruptly. Reusing the listener address
        // prevents the independent wearable from becoming unavailable during TIME_WAIT.
        setReuseAddr(true)
    }

    private data class Session(val id: String, var lastRemoteSequence: Long = -1L, val localSequence: AtomicLong = AtomicLong())
    private val sessions = ConcurrentHashMap<WebSocket, Session>()
    private val generator = SimulatedHeartRate()
    private var streamJob: Job? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) = Unit

    override fun onMessage(conn: WebSocket, raw: String) {
        val decoded = WearableProtocol.decode(raw)
        if (decoded !is WearableProtocol.DecodeResult.Valid) {
            conn.close(1008, (decoded as WearableProtocol.DecodeResult.Invalid).reason)
            return
        }
        val message = decoded.message
        if (message.eventType == WearableProtocol.EventType.HELLO) {
            if (sessions.containsKey(conn)) return
            if (message.payload.get("role")?.asString != "SLEEPSENSE") {
                conn.close(1008, "UNAUTHORIZED_ROLE")
                return
            }
            val session = Session(message.sessionId, message.sequenceNumber)
            sessions[conn] = session
            android.util.Log.i(TAG, "HELLO accepted session=${session.id.take(8)}")
            send(conn, session, WearableProtocol.EventType.DEVICE_INFO, JsonObject().apply {
                addProperty("name", "VirtualSleepBand")
                addProperty("simulated", true)
                addProperty("transport", "WEBSOCKET")
            })
            return
        }
        val session = sessions[conn] ?: run { conn.close(1008, "HELLO_REQUIRED"); return }
        if (message.sessionId != session.id || message.sequenceNumber <= session.lastRemoteSequence) return
        session.lastRemoteSequence = message.sequenceNumber
        when (message.eventType) {
            WearableProtocol.EventType.HEARTBEAT -> send(conn, session, WearableProtocol.EventType.HEARTBEAT, JsonObject())
            WearableProtocol.EventType.ACTUATOR_COMMAND -> {
                val commandId = message.payload.get("commandId")?.asString ?: return
                val action = message.payload.get("action")?.asString?.uppercase() ?: ""
                send(conn, session, WearableProtocol.EventType.COMMAND_ACK, JsonObject().apply { addProperty("commandId", commandId) })
                send(conn, session, WearableProtocol.EventType.COMMAND_RESULT, JsonObject().apply {
                    addProperty("commandId", commandId)
                    addProperty("success", action in setOf("ALERT", "VIBRATE", "OFF"))
                    addProperty("status", if (action in setOf("ALERT", "VIBRATE", "OFF")) "SIMULATED_EXECUTION" else "UNSUPPORTED_COMMAND")
                })
            }
            WearableProtocol.EventType.DISCONNECT -> conn.close(1000, "client disconnect")
            else -> Unit
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) { sessions.remove(conn) }
    override fun onError(conn: WebSocket?, ex: Exception?) {
        conn?.let(sessions::remove)
        android.util.Log.e(TAG, "WebSocket server failure", ex)
    }
    override fun onStart() {
        connectionLostTimeout = 10
        android.util.Log.i(TAG, "Listening on ${address.hostString}:${address.port}")
        val started = System.currentTimeMillis()
        streamJob = scope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - started) / 1000L
                sessions.forEach { (socket, session) ->
                    send(socket, session, WearableProtocol.EventType.HEART_RATE_SAMPLE, JsonObject().apply { addProperty("bpm", generator.bpm(elapsed)) })
                    send(socket, session, WearableProtocol.EventType.HEARTBEAT, JsonObject())
                }
                delay(1_000L)
            }
        }
    }

    override fun stop(timeout: Int) {
        streamJob?.cancel()
        super.stop(timeout)
    }

    private fun send(socket: WebSocket, session: Session, type: WearableProtocol.EventType, payload: JsonObject) {
        if (!socket.isOpen) return
        socket.send(WearableProtocol.encode(WearableProtocol.Envelope(
            sessionId = session.id, deviceId = DEVICE_ID, messageId = UUID.randomUUID().toString(),
            sequenceNumber = session.localSequence.incrementAndGet(), timestamp = System.currentTimeMillis(), eventType = type, payload = payload,
        )))
    }

    companion object {
        const val DEVICE_ID = "virtual-sleep-band-ws-01"
        const val TAG = "VirtualBandWebSocket"
    }
}
