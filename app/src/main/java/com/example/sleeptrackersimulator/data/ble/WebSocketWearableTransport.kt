package com.example.sleeptrackersimulator.data.ble

import com.example.sleeptrackersimulator.core.model.CommandResult
import com.example.sleeptrackersimulator.core.model.DeviceCapability
import com.example.sleeptrackersimulator.core.model.DeviceCommand
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.HeartRateSample
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.wearableprotocol.WearableProtocol
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Independent-process virtual wearable transport. This is WebSocket, never BLE. */
class WebSocketWearableTransport(
    private val endpoint: URI,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : DeviceTransport {
    private val state = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = state.asStateFlow()
    private val device = MutableStateFlow<DeviceInfo?>(null)
    override val connectedDevice: StateFlow<DeviceInfo?> = device.asStateFlow()
    private val samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 32)
    override val heartRateStream: SharedFlow<HeartRateSample> = samples.asSharedFlow()
    private val last = MutableStateFlow<HeartRateSample?>(null)
    override val lastHeartRate: StateFlow<HeartRateSample?> = last.asStateFlow()
    private val discovered = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<CommandResult>>()
    private val sequence = AtomicLong(0)
    private var remoteSequence = -1L
    private var sessionId = ""
    @Volatile private var lastHeartbeatElapsed = 0L
    @Volatile private var client: WebSocketClient? = null

    override fun observeDiscoveredDevices(): Flow<List<DeviceInfo>> = discovered.asStateFlow()

    override suspend fun startScan(): Result<Unit> {
        state.value = DeviceConnectionState.SCANNING
        discovered.value = listOf(candidate())
        state.value = DeviceConnectionState.DISCONNECTED
        return Result.success(Unit)
    }

    override suspend fun stopScan(): Result<Unit> = Result.success(Unit)

    override suspend fun connect(deviceId: String): Result<Unit> = runCatching {
        disconnect()
        state.value = DeviceConnectionState.CONNECTING
        sessionId = UUID.randomUUID().toString()
        remoteSequence = -1L
        val socket = object : WebSocketClient(endpoint) {
            override fun onOpen(handshake: ServerHandshake?) {
                lastHeartbeatElapsed = android.os.SystemClock.elapsedRealtime()
                sendMessage(WearableProtocol.EventType.HELLO, JsonObject().apply { addProperty("role", "SLEEPSENSE") })
            }
            override fun onMessage(raw: String?) { raw?.let(::handleMessage) }
            override fun onClose(code: Int, reason: String?, remote: Boolean) { clearConnection() }
            override fun onError(ex: Exception?) { state.value = DeviceConnectionState.ERROR }
        }
        client = socket
        if (!socket.connectBlocking()) error("Unable to connect to $endpoint")
        scope.launch {
            while (client === socket && socket.isOpen) {
                delay(1_000L)
                if (android.os.SystemClock.elapsedRealtime() - lastHeartbeatElapsed > WearableProtocol.HEARTBEAT_TIMEOUT_MILLIS) {
                    socket.close(4000, "heartbeat timeout")
                }
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        client?.closeBlocking()
        clearConnection()
    }

    override suspend fun executeCommand(command: DeviceCommand): Result<CommandResult> = runCatching {
        check(state.value == DeviceConnectionState.CONNECTED) { "Virtual wearable session is not established" }
        val deferred = CompletableDeferred<CommandResult>()
        pendingCommands[command.commandId] = deferred
        sendMessage(WearableProtocol.EventType.ACTUATOR_COMMAND, JsonObject().apply {
            addProperty("commandId", command.commandId)
            addProperty("action", command.action)
        })
        try { withTimeout(5_000L) { deferred.await() } } finally { pendingCommands.remove(command.commandId) }
    }

    private fun handleMessage(raw: String) {
        val decoded = WearableProtocol.decode(raw)
        if (decoded !is WearableProtocol.DecodeResult.Valid) return
        val message = decoded.message
        if (message.sessionId != sessionId || message.sequenceNumber <= remoteSequence) return
        remoteSequence = message.sequenceNumber
        lastHeartbeatElapsed = android.os.SystemClock.elapsedRealtime()
        when (message.eventType) {
            WearableProtocol.EventType.DEVICE_INFO -> {
                val info = candidate().copy(deviceId = message.deviceId, displayName = message.payload.get("name")?.asString ?: "VirtualSleepBand")
                device.value = info
                state.value = DeviceConnectionState.CONNECTED
            }
            WearableProtocol.EventType.HEART_RATE_SAMPLE -> {
                if (state.value != DeviceConnectionState.CONNECTED) return
                val sample = HeartRateSample(
                    sampleId = message.messageId, sessionId = sessionId, deviceId = message.deviceId,
                    bpm = message.payload.get("bpm").asInt, utcTimestampMillis = message.timestamp,
                    sourceKind = SourceKind.WEBSOCKET_SIMULATED,
                )
                last.value = sample
                samples.tryEmit(sample)
            }
            WearableProtocol.EventType.COMMAND_RESULT -> {
                val id = message.payload.get("commandId")?.asString ?: return
                pendingCommands[id]?.complete(CommandResult(id, message.payload.get("success")?.asBoolean == true, message.payload.get("status")?.asString ?: "SIMULATED_EXECUTION"))
            }
            WearableProtocol.EventType.DISCONNECT -> client?.close()
            else -> Unit
        }
    }

    private fun sendMessage(type: WearableProtocol.EventType, payload: JsonObject) {
        client?.takeIf { it.isOpen }?.send(WearableProtocol.encode(WearableProtocol.Envelope(
            sessionId = sessionId, deviceId = "sleepsense", messageId = UUID.randomUUID().toString(),
            sequenceNumber = sequence.incrementAndGet(), timestamp = System.currentTimeMillis(), eventType = type, payload = payload,
        )))
    }

    private fun clearConnection() {
        client = null
        state.value = DeviceConnectionState.DISCONNECTED
        device.value = null
        last.value = null
        pendingCommands.values.forEach { it.cancel() }
        pendingCommands.clear()
    }

    private fun candidate() = DeviceInfo(
        deviceId = endpoint.toString(), displayName = "VirtualSleepBand via WebSocket",
        sourceKind = SourceKind.WEBSOCKET_SIMULATED,
        capabilities = setOf(DeviceCapability.HEART_RATE, DeviceCapability.VIBRATION_ALERT, DeviceCapability.REMOTE_COMMANDS),
    )
}
