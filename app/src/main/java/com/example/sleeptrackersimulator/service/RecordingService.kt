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
import com.example.sleeptrackersimulator.domain.recording.SessionCoordinatorImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceModeStr = intent.getStringExtra(EXTRA_SOURCE_MODE) ?: SourceMode.PHONE_ONLY.name
                val placementStr = intent.getStringExtra(EXTRA_PLACEMENT) ?: DevicePlacement.MATTRESS.name
                val samplingHz = intent.getIntExtra(EXTRA_SAMPLING_HZ, 20)

                startRecordingSession(
                    SourceMode.valueOf(sourceModeStr),
                    DevicePlacement.fromString(placementStr),
                    samplingHz,
                )
            }
            ACTION_PAUSE -> pauseRecordingSession()
            ACTION_RESUME -> resumeRecordingSession()
            ACTION_STOP -> stopRecordingSession()
        }
        return START_NOT_STICKY
    }

    private fun startRecordingSession(mode: SourceMode, placement: DevicePlacement, samplingHz: Int) {
        val notification = buildNotification("Recording in progress", "Monitoring device movement...")

        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
        wakeLock?.acquire(8 * 60 * 60 * 1000L) // 8 hours max safety limit

        activeMode = mode
        activeSamplingHz = samplingHz.coerceIn(1, 200)
        serviceScope.launch {
            val result = coordinator?.startSession(mode, placement, activeSamplingHz)
            if (result?.isSuccess == true) {
                startSourcesForActiveMode()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopSelf()
            }
        }
    }

    private fun pauseRecordingSession() {
        sensorManager?.unregisterListener(this)
        transportJob?.cancel()
        serviceScope.launch {
            coordinator?.pauseSession()
        }
        val notification = buildNotification("Recording paused", "Tap to resume session")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun resumeRecordingSession() {
        serviceScope.launch {
            val result = coordinator?.resumeSession()
            if (result?.isSuccess == true) startSourcesForActiveMode()
        }
        val notification = buildNotification("Recording in progress", "Monitoring device movement...")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startSourcesForActiveMode() {
        if (activeMode.requiresPhoneSensor) {
            val delayUs = 1_000_000 / activeSamplingHz
            sensorManager?.registerListener(this, accelerometer, delayUs)
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

    private fun stopRecordingSession() {
        sensorManager?.unregisterListener(this)
        transportJob?.cancel()
        serviceScope.launch {
            coordinator?.stopSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        serviceScope.launch {
            coordinator?.ingestSensorEvent(
                sourceId = "phone_accelerometer",
                elapsedNanos = event.timestamp,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                accuracy = event.accuracy,
                sourceKind = SourceKind.PHONE_REAL,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handled through sensor accuracy passed with each sample
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        transportJob?.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        serviceScope.cancel()
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
        ) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_MODE, mode.name)
                putExtra(EXTRA_PLACEMENT, placement.name)
                putExtra(EXTRA_SAMPLING_HZ, samplingHz)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_PAUSE }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_RESUME }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
