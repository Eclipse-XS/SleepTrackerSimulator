package com.example.virtualsleepband

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.virtualsleepband.gatt.BandPeripheralState
import com.example.virtualsleepband.gatt.VirtualGattServer
import com.example.virtualsleepband.gatt.SimulationScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VirtualBandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: VirtualGattServer
    private val binder = Binder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        server = VirtualGattServer(this, scope)
        scope.launch { server.state.collect { sharedState.value = it } }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("VirtualSleepBand running")
                    .setContentText("Synthetic measurements · not medical data")
                    .setOngoing(true).build())
                server.startAdvertising().onFailure { sharedState.value = sharedState.value.copy(errorMessage = it.message) }
            }
            ACTION_STOP -> {
                server.stopAdvertising()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_INTERVAL -> server.setPacketInterval(intent.getLongExtra(EXTRA_INTERVAL, 1000L))
            ACTION_SCENARIO -> server.setScenario(
                runCatching { SimulationScenario.valueOf(intent.getStringExtra(EXTRA_SCENARIO).orEmpty()) }
                    .getOrDefault(SimulationScenario.NORMAL_REST),
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server.stopAdvertising()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Virtual wearable simulation", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val ACTION_START = "com.example.virtualsleepband.START"
        const val ACTION_STOP = "com.example.virtualsleepband.STOP"
        const val ACTION_INTERVAL = "com.example.virtualsleepband.INTERVAL"
        const val ACTION_SCENARIO = "com.example.virtualsleepband.SCENARIO"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_SCENARIO = "scenario"
        private const val CHANNEL_ID = "virtual_band_simulation"
        private const val NOTIFICATION_ID = 2401
        val sharedState = MutableStateFlow(BandPeripheralState())

        fun start(context: Context) = context.startForegroundService(Intent(context, VirtualBandService::class.java).setAction(ACTION_START))
        fun stop(context: Context) = context.startService(Intent(context, VirtualBandService::class.java).setAction(ACTION_STOP))
        fun setInterval(context: Context, interval: Long) = context.startService(Intent(context, VirtualBandService::class.java).setAction(ACTION_INTERVAL).putExtra(EXTRA_INTERVAL, interval))
        fun setScenario(context: Context, scenario: SimulationScenario) = context.startService(Intent(context, VirtualBandService::class.java).setAction(ACTION_SCENARIO).putExtra(EXTRA_SCENARIO, scenario.name))
    }
}
