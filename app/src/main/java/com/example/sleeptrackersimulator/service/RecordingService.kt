package com.example.sleeptrackersimulator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.sleeptrackersimulator.app.MainActivity
import com.example.sleeptrackersimulator.app.SleepTrackerApplication
import com.example.sleeptrackersimulator.core.model.DevicePlacement
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class RecordingService : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var coordinator: com.example.sleeptrackersimulator.domain.recording.SessionCoordinator? = null
    private var transportJob: Job? = null
    private var activeMode: SourceMode = SourceMode.PHONE_ONLY
    private var activeSamplingHz: Int = 20
    private val commandChannel = Channel<ServiceCommand>(capacity = Channel.BUFFERED)
    private val sampleChannel = Channel<AccelerometerReading>(capacity = SENSOR_QUEUE_CAPACITY)
    private val droppedSamples = AtomicLong(0)
    private val ingestionEpoch = AtomicLong(0)

    private sealed interface ServiceCommand {
        data class Start(val mode: SourceMode, val placement: DevicePlacement, val samplingHz: Int) : ServiceCommand
        data object Pause : ServiceCommand
        data object Resume : ServiceCommand
        data object Stop : ServiceCommand
        data class FatalIngestionFailure(val error: Throwable) : ServiceCommand
    }

    private data class AccelerometerReading(
        val elapsedNanos: Long,
        val x: Float,
        val y: Float,
        val z: Float,
        val accuracy: Int,
        val epoch: Long,
    )

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
        fun getCoordinator(): com.example.sleeptrackersimulator.domain.recording.SessionCoordinator? = coordinator
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepSense:RecordingWakeLock")?.apply {
            setReferenceCounted(false)
        }

        val app = application as? SleepTrackerApplication
        if (app != null) {
            coordinator = app.container.sessionCoordinator
        }

        createNotificationChannel()
        serviceScope.launch {
            for (command in commandChannel) {
                runCatching { handleCommand(command) }.onFailure { error ->
                    DiagnosticLog.event(
                        tag = DiagnosticTags.LIFECYCLE,
                        operation = "recording_service_command",
                        result = DiagnosticResult.FAILED,
                        correlationId = coordinator?.activeSession?.value?.sessionId,
                        details = mapOf("command" to command::class.simpleName),
                        error = error,
                    )
                    stopSources()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        serviceScope.launch {
            for (reading in sampleChannel) {
                if (reading.epoch != ingestionEpoch.get()) continue
                try {
                    coordinator?.ingestSensorEvent(
                        sourceId = "phone_accelerometer",
                        elapsedNanos = reading.elapsedNanos,
                        x = reading.x,
                        y = reading.y,
                        z = reading.z,
                        accuracy = reading.accuracy,
                        sourceKind = SourceKind.PHONE_REAL,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    commandChannel.send(ServiceCommand.FatalIngestionFailure(error))
                    ingestionEpoch.incrementAndGet()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            ACTION_START -> {
                val sourceModeStr = intent.getStringExtra(EXTRA_SOURCE_MODE) ?: SourceMode.PHONE_ONLY.name
                val placementStr = intent.getStringExtra(EXTRA_PLACEMENT) ?: DevicePlacement.MATTRESS.name
                val samplingHz = intent.getIntExtra(EXTRA_SAMPLING_HZ, 20)
                ServiceCommand.Start(
                    mode = runCatching { SourceMode.valueOf(sourceModeStr) }.getOrDefault(SourceMode.PHONE_ONLY),
                    placement = DevicePlacement.fromString(placementStr),
                    samplingHz = samplingHz,
                )
            }
            ACTION_PAUSE -> ServiceCommand.Pause
            ACTION_RESUME -> ServiceCommand.Resume
            ACTION_STOP -> ServiceCommand.Stop
            else -> null
        }
        if (command != null && commandChannel.trySend(command).isFailure) {
            DiagnosticLog.event(
                tag = DiagnosticTags.LIFECYCLE,
                operation = "recording_service_command_enqueue",
                result = DiagnosticResult.FAILED,
                details = mapOf("command" to command::class.simpleName),
            )
        }
        return START_NOT_STICKY
    }

    private suspend fun handleCommand(command: ServiceCommand) {
        when (command) {
            is ServiceCommand.Start -> startRecordingSession(command.mode, command.placement, command.samplingHz)
            ServiceCommand.Pause -> pauseRecordingSession()
            ServiceCommand.Resume -> resumeRecordingSession()
            ServiceCommand.Stop -> stopRecordingSession()
            is ServiceCommand.FatalIngestionFailure -> failRecordingSession(command.error)
        }
    }

    private suspend fun failRecordingSession(error: Throwable) {
        stopSources()
        val reason = "Sensor ingestion failed: ${error.message ?: error.javaClass.simpleName}"
        coordinator?.failSession(reason, droppedSamples.get())
        DiagnosticLog.event(
            tag = DiagnosticTags.SENSORS,
            operation = "sensor_ingestion_fatal",
            result = DiagnosticResult.FAILED,
            correlationId = coordinator?.activeSession?.value?.sessionId,
            details = mapOf("droppedSamples" to droppedSamples.get()),
            error = error,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf()
    }

    private suspend fun startRecordingSession(mode: SourceMode, placement: DevicePlacement, samplingHz: Int) {
        if (coordinator?.sessionStatus?.value?.isActive == true) {
            DiagnosticLog.event(
                tag = DiagnosticTags.LIFECYCLE,
                operation = "recording_start",
                result = DiagnosticResult.SKIPPED,
                correlationId = coordinator?.activeSession?.value?.sessionId,
                details = mapOf("reason" to "already_active"),
            )
            return
        }
        val notification = buildNotification("Recording in progress", "Monitoring device movement...")

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        wakeLock?.acquire(8 * 60 * 60 * 1000L) // 8 hours max safety limit

        activeMode = mode
        activeSamplingHz = samplingHz.coerceIn(1, 200)
        val result = coordinator?.startSession(mode, placement, activeSamplingHz)
        if (result?.isSuccess == true) {
            ingestionEpoch.incrementAndGet()
            droppedSamples.set(0)
            startSourcesForActiveMode()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            stopSelf()
        }
    }

    private suspend fun pauseRecordingSession() {
        if (coordinator?.sessionStatus?.value != SessionStatus.RECORDING) return
        ingestionEpoch.incrementAndGet()
        stopSources()
        coordinator?.pauseSession()
        val notification = buildNotification("Recording paused", "Tap to resume session")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun resumeRecordingSession() {
        if (coordinator?.sessionStatus?.value != SessionStatus.PAUSED) return
        val result = coordinator?.resumeSession()
        if (result?.isSuccess == true) {
            ingestionEpoch.incrementAndGet()
            startSourcesForActiveMode()
        }
        val notification = buildNotification("Recording in progress", "Monitoring device movement...")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startSourcesForActiveMode() {
        if (activeMode.requiresPhoneSensor) {
            val delayUs = 1_000_000 / activeSamplingHz
            val registered = accelerometer != null && sensorManager?.registerListener(this, accelerometer, delayUs) == true
            if (!registered) throw IllegalStateException("Accelerometer is unavailable or listener registration failed")
        }
        if (!activeMode.requiresBleConnection) return

        val app = application as? SleepTrackerApplication
        val transport = app?.container?.deviceTransport
        if (transport != null) {
            transportJob?.cancel()
            transportJob = serviceScope.launch {
                transport.heartRateStream.collect { hrSample ->
                    coordinator?.ingestHeartRateSample(
                        deviceId = hrSample.deviceId,
                        bpm = hrSample.bpm,
                        utcTimestampMillis = hrSample.utcTimestampMillis,
                        timingUncertaintyMillis = hrSample.timingUncertaintyMillis,
                        sourceKind = hrSample.sourceKind,
                    )
                }
            }
        }
    }

    private suspend fun stopRecordingSession() {
        if (coordinator?.sessionStatus?.value?.isActive != true) {
            stopSources()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        ingestionEpoch.incrementAndGet()
        stopSources()
        val sessionId = coordinator?.activeSession?.value?.sessionId
        coordinator?.reportDroppedSamples(droppedSamples.get())
        coordinator?.stopSession()
        DiagnosticLog.event(
            tag = DiagnosticTags.SENSORS,
            operation = "sensor_ingestion_summary",
            result = DiagnosticResult.SUCCESS,
            correlationId = sessionId,
            details = mapOf("droppedSamples" to droppedSamples.get()),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // SensorEvent and its values array are owned and reused by SensorManager. Copy all
        // fields synchronously on the callback thread before handing work to another coroutine.
        val values = event.values
        if (values.size < 3) return
        val reading = AccelerometerReading(
            event.timestamp,
            values[0],
            values[1],
            values[2],
            event.accuracy,
            ingestionEpoch.get(),
        )
        if (sampleChannel.trySend(reading).isFailure) {
            val count = droppedSamples.incrementAndGet()
            if (count == 1L || count % 100L == 0L) {
                DiagnosticLog.event(
                    tag = DiagnosticTags.SENSORS,
                    operation = "sensor_sample_dropped",
                    result = DiagnosticResult.SKIPPED,
                    correlationId = coordinator?.activeSession?.value?.sessionId,
                    details = mapOf("droppedSamples" to count, "queueCapacity" to SENSOR_QUEUE_CAPACITY),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handled through sensor accuracy passed with each sample
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSources()
        releaseWakeLock()
        commandChannel.close()
        sampleChannel.close()
        serviceScope.cancel()
    }

    private fun stopSources() {
        sensorManager?.unregisterListener(this)
        transportJob?.cancel()
        transportJob = null
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sleep & Movement Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows ongoing session recording status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Finish", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "sleep_recording_channel"
        const val NOTIFICATION_ID = 2026
        private const val SENSOR_QUEUE_CAPACITY = 512

        const val ACTION_START = "com.example.sleeptrackersimulator.action.START_RECORDING"
        const val ACTION_PAUSE = "com.example.sleeptrackersimulator.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.sleeptrackersimulator.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.example.sleeptrackersimulator.action.STOP_RECORDING"

        const val EXTRA_SOURCE_MODE = "extra_source_mode"
        const val EXTRA_PLACEMENT = "extra_placement"
        const val EXTRA_SAMPLING_HZ = "extra_sampling_hz"

        fun start(
            context: Context,
            mode: SourceMode = SourceMode.PHONE_ONLY,
            placement: DevicePlacement = DevicePlacement.MATTRESS,
            samplingHz: Int = 20,
        ): Result<Unit> = runCatching {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_MODE, mode.name)
                putExtra(EXTRA_PLACEMENT, placement.name)
                putExtra(EXTRA_SAMPLING_HZ, samplingHz)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_PAUSE }
            context.startService(intent)
        }

        fun resume(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_RESUME }
            context.startService(intent)
        }

        fun stop(context: Context): Result<Unit> = runCatching {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
