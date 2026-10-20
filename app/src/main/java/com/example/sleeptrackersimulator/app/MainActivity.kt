package com.example.sleeptrackersimulator.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sleeptrackersimulator.ble.MockBleConnector
import com.example.sleeptrackersimulator.actuator.AndroidSmartActuator
import com.example.sleeptrackersimulator.actuator.FirebaseRemoteControlRepository
import com.example.sleeptrackersimulator.cloud.AndroidNetworkMonitor
import com.example.sleeptrackersimulator.cloud.FirebaseCloudRepository
import com.example.sleeptrackersimulator.cloud.FirebaseAnonymousAuthRepository
import com.example.sleeptrackersimulator.cloud.RoomCloudStateStore
import com.example.sleeptrackersimulator.cloud.WorkManagerCloudRecoveryScheduler
import com.example.sleeptrackersimulator.cloud.OutboxSmartRuleCloudEventSink
import com.example.sleeptrackersimulator.sensor.AndroidAccelerometerDataSource
import com.example.sleeptrackersimulator.history.LocalHistoryRecorderViewModel
import com.example.sleeptrackersimulator.history.RoomSensorHistoryRepository
import com.example.sleeptrackersimulator.history.SleepTrackerDatabase
import com.example.sleeptrackersimulator.ui.analytics.AnalyticsScreen
import com.example.sleeptrackersimulator.ui.analytics.AnalyticsViewModel
import com.example.sleeptrackersimulator.ui.dashboard.BleViewModel
import com.example.sleeptrackersimulator.ui.dashboard.ActuatorViewModel
import com.example.sleeptrackersimulator.ui.dashboard.CloudSyncViewModel
import com.example.sleeptrackersimulator.ui.dashboard.SensorScreen
import com.example.sleeptrackersimulator.ui.dashboard.SensorViewModel
import com.example.sleeptrackersimulator.ui.dashboard.RestStateViewModel
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import com.example.sleeptrackersimulator.ml.ActivityClassifier
import com.example.sleeptrackersimulator.ml.MlViewModel
import com.example.sleeptrackersimulator.smart.RoomSmartRuleRepository
import com.example.sleeptrackersimulator.smart.SmartScenariosViewModel
import com.example.sleeptrackersimulator.ui.smart.SmartScenariosScreen
import com.example.sleeptrackersimulator.ble.BleOperationResult

class MainActivity : ComponentActivity() {
    private val database by lazy { SleepTrackerDatabase.getInstance(applicationContext) }
    private val cloudStateStore by lazy {
        RoomCloudStateStore(
            dao = database.cloudOutboxDao(),
            legacyFile = applicationContext.filesDir.resolve("cloud/cloud-state.json"),
        )
    }
    private val cloudRecoveryScheduler by lazy { WorkManagerCloudRecoveryScheduler(applicationContext) }
    private val smartRuleRepository by lazy { RoomSmartRuleRepository(database.smartRuleDao()) }
    private var pendingFlashlightEnable = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingFlashlightEnable) {
            if (granted) actuatorViewModel.setManualFlashlight(true)
            else actuatorViewModel.reportFlashlightPermissionDenied()
        }
        pendingFlashlightEnable = false
    }

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

    private val mlViewModel: MlViewModel by viewModels {
        MlViewModel.factory(viewModel.samples, ActivityClassifier(applicationContext))
    }

    private val cloudSyncViewModel: CloudSyncViewModel by viewModels {
        CloudSyncViewModel.factory(
            repository = FirebaseCloudRepository(),
            authRepository = FirebaseAnonymousAuthRepository(),
            networkMonitor = AndroidNetworkMonitor(applicationContext),
            stateStore = cloudStateStore,
            sensorState = viewModel.uiState,
            bleState = bleViewModel.state,
            heartRate = bleViewModel.heartRate,
            restState = restStateViewModel.state,
            recoveryScheduler = cloudRecoveryScheduler,
            ruleEventStatusUpdater = { eventId -> smartRuleRepository.updateCloudStatus(eventId, "SYNCED") },
        )
    }

    private val actuatorViewModel: ActuatorViewModel by viewModels {
        ActuatorViewModel.factory(
            sensorState = viewModel.uiState,
            actuator = AndroidSmartActuator(applicationContext),
            remoteRepository = FirebaseRemoteControlRepository(),
            mlState = mlViewModel.state,
        )
    }

    private val historyRepository by lazy {
        RoomSensorHistoryRepository(SleepTrackerDatabase.getInstance(applicationContext).sensorRecordDao())
    }

    private val historyRecorderViewModel: LocalHistoryRecorderViewModel by viewModels {
        LocalHistoryRecorderViewModel.factory(
            sensorState = viewModel.uiState,
            actuatorActivations = actuatorViewModel.activations,
            repository = historyRepository,
        )
    }

    private val analyticsViewModel: AnalyticsViewModel by viewModels {
        AnalyticsViewModel.factory(historyRepository)
    }

    private val smartScenariosViewModel: SmartScenariosViewModel by viewModels {
        SmartScenariosViewModel.factory(
            analyses = viewModel.analyses,
            predictions = mlViewModel.predictions,
            repository = smartRuleRepository,
            vibration = {
                actuatorViewModel.triggerSmartRulePulse().let { result ->
                    if (result.successful) result.message else "ERROR:${result.message}"
                }
            },
            bleAlert = { eventId ->
                when (val result = bleViewModel.sendAlert(eventId)) {
                    is BleOperationResult.Success -> result.message
                    is BleOperationResult.Error -> "ERROR:${result.message}"
                }
            },
            cloudEvents = OutboxSmartRuleCloudEventSink(cloudStateStore, cloudRecoveryScheduler),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onCreate", DiagnosticResult.SUCCESS, details = mapOf("restored" to (savedInstanceState != null)))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            SleepTrackerTheme {
                var destination by rememberSaveable { mutableStateOf(AppDestination.DASHBOARD) }
                val sensorState = viewModel.uiState.collectAsStateWithLifecycle().value
                val bleState = bleViewModel.state.collectAsStateWithLifecycle().value
                val bleLogs = bleViewModel.logs.collectAsStateWithLifecycle().value
                val heartRate = bleViewModel.heartRate.collectAsStateWithLifecycle().value
                val heartRateHistory = bleViewModel.heartRateHistory.collectAsStateWithLifecycle().value
                val notificationsEnabled = bleViewModel.notificationsEnabled.collectAsStateWithLifecycle().value
                val restState = restStateViewModel.state.collectAsStateWithLifecycle().value
                val mlState = mlViewModel.state.collectAsStateWithLifecycle().value
                val cloudSyncState = cloudSyncViewModel.state.collectAsStateWithLifecycle().value
                val actuatorState = actuatorViewModel.state.collectAsStateWithLifecycle().value
                val recordingState = historyRecorderViewModel.state.collectAsStateWithLifecycle().value
                val analyticsState = analyticsViewModel.state.collectAsStateWithLifecycle().value
                val smartState = smartScenariosViewModel.state.collectAsStateWithLifecycle().value

                Column(Modifier.fillMaxSize()) {
                    when (destination) {
                        AppDestination.DASHBOARD -> SensorScreen(
                            uiState = sensorState,
                            bleState = bleState,
                            bleLogs = bleLogs,
                            heartRate = heartRate,
                            heartRateHistory = heartRateHistory,
                            notificationsEnabled = notificationsEnabled,
                            restState = restState,
                            mlState = mlState,
                            cloudSyncState = cloudSyncState,
                            actuatorState = actuatorState,
                            recentRecords = analyticsState.records.takeLast(5).reversed(),
                            onScan = bleViewModel::startScan,
                            onConnect = bleViewModel::connect,
                            onDisconnect = bleViewModel::disconnect,
                            onRead = bleViewModel::readBodySensorLocation,
                            onWrite = bleViewModel::writeCommand,
                            onStartMonitoring = cloudSyncViewModel::startSync,
                            onEndMonitoring = cloudSyncViewModel::endMonitoring,
                            onAutomaticControlChanged = actuatorViewModel::setAutomaticControlEnabled,
                            onVibrationChanged = actuatorViewModel::setManualVibration,
                            onFlashlightChanged = ::requestFlashlightChange,
                            modifier = Modifier.weight(1f),
                        )
                        AppDestination.ANALYTICS -> AnalyticsScreen(
                            state = analyticsState,
                            recordingError = recordingState.lastError,
                            modifier = Modifier.weight(1f),
                        )
                        AppDestination.SMART_SCENARIOS -> SmartScenariosScreen(
                            state = smartState,
                            onSave = smartScenariosViewModel::save,
                            onEnabled = smartScenariosViewModel::setEnabled,
                            onDelete = smartScenariosViewModel::delete,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = destination == AppDestination.DASHBOARD,
                            onClick = { destination = AppDestination.DASHBOARD },
                            icon = { Icon(Icons.Rounded.Dashboard, contentDescription = null) },
                            label = { Text("Dashboard") },
                        )
                        NavigationBarItem(
                            selected = destination == AppDestination.SMART_SCENARIOS,
                            onClick = { destination = AppDestination.SMART_SCENARIOS },
                            icon = { Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null) },
                            label = { Text("Scenarios") },
                        )
                        NavigationBarItem(
                            selected = destination == AppDestination.ANALYTICS,
                            onClick = { destination = AppDestination.ANALYTICS },
                            icon = { Icon(Icons.Rounded.Analytics, contentDescription = null) },
                            label = { Text("Analytics") },
                        )
                    }
                }
                smartState.visibleAlert?.let { ruleName ->
                    AlertDialog(
                        onDismissRequest = smartScenariosViewModel::dismissVisibleAlert,
                        title = { Text("Smart scenario alert") },
                        text = { Text("Rule triggered: $ruleName") },
                        confirmButton = {
                            TextButton(onClick = smartScenariosViewModel::dismissVisibleAlert) {
                                Text("Dismiss")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onResume", DiagnosticResult.STARTED)
        viewModel.setForegroundActive(true)
        actuatorViewModel.setForegroundActive(true)
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onResume", DiagnosticResult.SUCCESS)
    }

    override fun onPause() {
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onPause", DiagnosticResult.STARTED)
        actuatorViewModel.setForegroundActive(false)
        viewModel.setForegroundActive(false)
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onPause", DiagnosticResult.SUCCESS)
        super.onPause()
    }

    override fun onDestroy() {
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onDestroy", DiagnosticResult.SUCCESS, details = mapOf("changingConfigurations" to isChangingConfigurations))
        super.onDestroy()
    }

    private enum class AppDestination { DASHBOARD, ANALYTICS, SMART_SCENARIOS }

    private fun requestFlashlightChange(enabled: Boolean) {
        if (!enabled ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            actuatorViewModel.setManualFlashlight(enabled)
            return
        }
        pendingFlashlightEnable = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
