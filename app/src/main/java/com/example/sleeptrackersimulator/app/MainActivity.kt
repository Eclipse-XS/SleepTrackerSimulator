package com.example.sleeptrackersimulator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sleeptrackersimulator.ble.MockBleConnector
import com.example.sleeptrackersimulator.cloud.AndroidNetworkMonitor
import com.example.sleeptrackersimulator.cloud.FirebaseCloudRepository
import com.example.sleeptrackersimulator.sensor.AndroidAccelerometerDataSource
import com.example.sleeptrackersimulator.ui.dashboard.BleViewModel
import com.example.sleeptrackersimulator.ui.dashboard.CloudSyncViewModel
import com.example.sleeptrackersimulator.ui.dashboard.SensorScreen
import com.example.sleeptrackersimulator.ui.dashboard.SensorViewModel
import com.example.sleeptrackersimulator.ui.dashboard.RestStateViewModel
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SensorViewModel by viewModels {
        SensorViewModel.factory(AndroidAccelerometerDataSource(applicationContext))
    }

    private val bleViewModel: BleViewModel by viewModels {
        BleViewModel.factory(MockBleConnector())
    }

    private val restStateViewModel: RestStateViewModel by viewModels {
        RestStateViewModel.factory(
            sensorState = viewModel.uiState,
            bleState = bleViewModel.state,
            heartRate = bleViewModel.heartRate,
            heartRateHistory = bleViewModel.heartRateHistory,
        )
    }

    private val cloudSyncViewModel: CloudSyncViewModel by viewModels {
        CloudSyncViewModel.factory(
            repository = FirebaseCloudRepository(),
            networkMonitor = AndroidNetworkMonitor(applicationContext),
            sensorState = viewModel.uiState,
            bleState = bleViewModel.state,
            heartRate = bleViewModel.heartRate,
            restState = restStateViewModel.state,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepTrackerTheme {
                val sensorState = viewModel.uiState.collectAsStateWithLifecycle().value
                val bleState = bleViewModel.state.collectAsStateWithLifecycle().value
                val bleLogs = bleViewModel.logs.collectAsStateWithLifecycle().value
                val heartRate = bleViewModel.heartRate.collectAsStateWithLifecycle().value
                val heartRateHistory = bleViewModel.heartRateHistory.collectAsStateWithLifecycle().value
                val notificationsEnabled = bleViewModel.notificationsEnabled.collectAsStateWithLifecycle().value
                val restState = restStateViewModel.state.collectAsStateWithLifecycle().value
                val cloudSyncState = cloudSyncViewModel.state.collectAsStateWithLifecycle().value

                SensorScreen(
                    uiState = sensorState,
                    bleState = bleState,
                    bleLogs = bleLogs,
                    heartRate = heartRate,
                    heartRateHistory = heartRateHistory,
                    notificationsEnabled = notificationsEnabled,
                    restState = restState,
                    cloudSyncState = cloudSyncState,
                    onScan = bleViewModel::startScan,
                    onConnect = bleViewModel::connect,
                    onDisconnect = bleViewModel::disconnect,
                    onRead = bleViewModel::readBodySensorLocation,
                    onWrite = bleViewModel::writeCommand,
                    onStartMonitoring = cloudSyncViewModel::startSync,
                    onEndMonitoring = cloudSyncViewModel::endMonitoring,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
