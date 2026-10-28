package com.example.sleeptrackersimulator.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.sleeptrackersimulator.ble.BleOperationResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticLog
import com.example.sleeptrackersimulator.diagnostics.DiagnosticResult
import com.example.sleeptrackersimulator.diagnostics.DiagnosticTags
import com.example.sleeptrackersimulator.history.LocalHistoryRecorderViewModel
import com.example.sleeptrackersimulator.dataset.DemoDatasetViewModel
import com.example.sleeptrackersimulator.ml.MlViewModel
import com.example.sleeptrackersimulator.service.RecordingService
import com.example.sleeptrackersimulator.smart.SmartScenariosViewModel
import com.example.sleeptrackersimulator.ui.analytics.AnalyticsScreen
import com.example.sleeptrackersimulator.ui.analytics.AnalyticsViewModel
import com.example.sleeptrackersimulator.ui.dashboard.ActuatorViewModel
import com.example.sleeptrackersimulator.ui.dashboard.BleViewModel
import com.example.sleeptrackersimulator.ui.dashboard.CloudSyncViewModel
import com.example.sleeptrackersimulator.ui.dashboard.RestStateViewModel
import com.example.sleeptrackersimulator.ui.dashboard.SensorScreen
import com.example.sleeptrackersimulator.ui.dashboard.SensorViewModel
import com.example.sleeptrackersimulator.ui.developer.DeveloperLabScreen
import com.example.sleeptrackersimulator.ui.devices.DevicesScreen
import com.example.sleeptrackersimulator.ui.devices.DevicesViewModel
import com.example.sleeptrackersimulator.ui.history.HistoryScreen
import com.example.sleeptrackersimulator.ui.history.HistoryViewModel
import com.example.sleeptrackersimulator.ui.insights.InsightsScreen
import com.example.sleeptrackersimulator.ui.insights.InsightsViewModel
import com.example.sleeptrackersimulator.ui.settings.SettingsScreen
import com.example.sleeptrackersimulator.ui.smart.SmartScenariosScreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurface
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.NightBlue
import com.example.sleeptrackersimulator.ui.theme.SleepTrackerTheme
import com.example.sleeptrackersimulator.ui.today.TodayScreen
import com.example.sleeptrackersimulator.ui.today.TodayViewModel

class MainActivity : ComponentActivity() {

    private val container by lazy {
        (application as SleepTrackerApplication).container
    }

    private var pendingFlashlightEnable = false
    private var pendingRecordingStart: (() -> Unit)? = null
    private var pendingBleScan = false
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingFlashlightEnable) {
            if (granted) actuatorViewModel.setManualFlashlight(true)
            else actuatorViewModel.reportFlashlightPermissionDenied()
        }
        pendingFlashlightEnable = false
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingRecordingStart
        pendingRecordingStart = null
        if (granted) pending?.invoke()
        else todayViewModel.reportServiceCommandFailure(
            "Start recording",
            SecurityException("Activity recognition permission denied"),
        )
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        if (pendingBleScan && granted) devicesViewModel.startScan()
        pendingBleScan = false
    }

    // Core SleepSense ViewModels
    private val todayViewModel: TodayViewModel by viewModels {
        TodayViewModel.factory(
            sessionCoordinator = container.sessionCoordinator,
            analyticsRepository = container.analyticsRepository,
        )
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.factory(container.sessionRepository)
    }

    private val insightsViewModel: InsightsViewModel by viewModels {
        InsightsViewModel.factory(container.analyticsRepository)
    }

    private val demoDatasetViewModel: DemoDatasetViewModel by viewModels {
        DemoDatasetViewModel.factory(this, container.sessionRepository)
    }

    private val devicesViewModel: DevicesViewModel by viewModels {
        DevicesViewModel.factory(container.deviceTransport)
    }

    // Diagnostics & Labs ViewModels
    private val sensorViewModel: SensorViewModel by viewModels {
        SensorViewModel.factory(container.accelerometerDataSource)
    }

    private val bleViewModel: BleViewModel by viewModels {
        BleViewModel.factory(container.bleConnector)
    }

    private val restStateViewModel: RestStateViewModel by viewModels {
        RestStateViewModel.factory(
            sensorState = sensorViewModel.uiState,
            bleState = bleViewModel.state,
            heartRate = bleViewModel.heartRate,
            heartRateHistory = bleViewModel.heartRateHistory,
        )
    }

    private val mlViewModel: MlViewModel by viewModels {
        MlViewModel.factory(sensorViewModel.samples, container.activityClassifier)
    }

    private val cloudSyncViewModel: CloudSyncViewModel by viewModels {
        CloudSyncViewModel.factory(
            repository = container.cloudRepository,
            authRepository = container.authRepository,
            networkMonitor = container.networkMonitor,
            stateStore = container.cloudStateStore,
            sensorState = sensorViewModel.uiState,
            bleState = bleViewModel.state,
            heartRate = bleViewModel.heartRate,
            restState = restStateViewModel.state,
            recoveryScheduler = container.cloudRecoveryScheduler,
            ruleEventStatusUpdater = { eventId -> container.smartRuleRepository.updateCloudStatus(eventId, "SYNCED") },
        )
    }

    private val actuatorViewModel: ActuatorViewModel by viewModels {
        ActuatorViewModel.factory(
            sensorState = sensorViewModel.uiState,
            actuator = container.smartActuator,
            remoteRepository = container.remoteControlRepository,
            mlState = mlViewModel.state,
        )
    }

    private val historyRecorderViewModel: LocalHistoryRecorderViewModel by viewModels {
        LocalHistoryRecorderViewModel.factory(
            sensorState = sensorViewModel.uiState,
            actuatorActivations = actuatorViewModel.activations,
            repository = container.sensorHistoryRepository,
        )
    }

    private val legacyAnalyticsViewModel: AnalyticsViewModel by viewModels {
        AnalyticsViewModel.factory(container.sensorHistoryRepository)
    }

    private val smartScenariosViewModel: SmartScenariosViewModel by viewModels {
        SmartScenariosViewModel.factory(
            analyses = sensorViewModel.analyses,
            predictions = mlViewModel.predictions,
            repository = container.smartRuleRepository,
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
            eventCoordinator = container.smartRuleEventCoordinator,
            recoveryScheduler = container.cloudRecoveryScheduler,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onCreate", DiagnosticResult.SUCCESS, details = mapOf("restored" to (savedInstanceState != null)))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            SleepTrackerTheme {
                var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
                var activeOverlay by rememberSaveable { mutableStateOf(AppOverlay.NONE) }
                var overlayBackDestination by rememberSaveable { mutableStateOf(AppOverlay.NONE) }

                LaunchedEffect(selectedTab) {
                    if (selectedTab == AppTab.INSIGHTS) insightsViewModel.refresh()
                }

                val todayState = todayViewModel.uiState.collectAsStateWithLifecycle().value
                val historyState = historyViewModel.uiState.collectAsStateWithLifecycle().value
                val insightsState = insightsViewModel.uiState.collectAsStateWithLifecycle().value
                val devicesState = devicesViewModel.uiState.collectAsStateWithLifecycle().value
                val demoDatasetState = demoDatasetViewModel.state.collectAsStateWithLifecycle().value

                // Legacy states for developer lab and compatibility
                val sensorState = sensorViewModel.uiState.collectAsStateWithLifecycle().value
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
                val legacyAnalyticsState = legacyAnalyticsViewModel.state.collectAsStateWithLifecycle().value
                val smartState = smartScenariosViewModel.state.collectAsStateWithLifecycle().value

                when (activeOverlay) {
                    AppOverlay.SETTINGS -> {
                        BackHandler { activeOverlay = AppOverlay.NONE }
                        SettingsScreen(
                            onBack = { activeOverlay = AppOverlay.NONE },
                            onOpenDeveloperLab = {
                                overlayBackDestination = AppOverlay.SETTINGS
                                activeOverlay = AppOverlay.DEVELOPER_LAB
                            },
                            onExportData = {
                                overlayBackDestination = AppOverlay.SETTINGS
                                activeOverlay = AppOverlay.DEVELOPER_LAB
                            },
                            onDeleteAllData = {
                                lifecycleScope.launch {
                                    container.sessionRepository.clearAllLocalData()
                                }
                            },
                            deleteAllEnabled = !todayState.sessionStatus.isActive,
                        )
                    }
                    AppOverlay.AUTOMATIONS -> {
                        BackHandler { activeOverlay = AppOverlay.NONE }
                        SmartScenariosScreen(
                            state = smartState,
                            onSave = smartScenariosViewModel::save,
                            onEnabled = smartScenariosViewModel::setEnabled,
                            onDelete = smartScenariosViewModel::delete,
                            onBack = { activeOverlay = AppOverlay.NONE },
                            actuatorState = actuatorState,
                            onEmergencyOff = { actuatorViewModel.emergencyOff() },
                        )
                    }
                    AppOverlay.DEVELOPER_LAB -> {
                        val returnDest = overlayBackDestination
                        BackHandler {
                            activeOverlay = returnDest
                            overlayBackDestination = AppOverlay.NONE
                        }
                        val appContainer = (application as SleepTrackerApplication).container
                        val bleAdvState by appContainer.virtualSleepBandAdvertiser.state.collectAsStateWithLifecycle()
                        DeveloperLabScreen(
                            onBack = {
                                activeOverlay = returnDest
                                overlayBackDestination = AppOverlay.NONE
                            },
                            actuatorState = actuatorState,
                            onTriggerVibration = { actuatorViewModel.testVibration() },
                            onPersistentVibrationChanged = actuatorViewModel::setManualVibration,
                            onFlashlightChanged = ::requestFlashlightChange,
                            onEmergencyOff = { actuatorViewModel.emergencyOff() },
                            onAutomaticControlChanged = actuatorViewModel::setAutomaticControlEnabled,
                            onTriggerBleAlert = { bleViewModel.sendAlert("DEV_LAB") },
                            onOpenLegacyDashboard = { activeOverlay = AppOverlay.LEGACY_DASHBOARD },
                            isBleAdvertising = bleAdvState.isAdvertising,
                            onToggleBleAdvertising = {
                                if (bleAdvState.isAdvertising) {
                                    appContainer.virtualSleepBandAdvertiser.stopAdvertising()
                                } else {
                                    appContainer.virtualSleepBandAdvertiser.startAdvertising()
                                }
                            },
                            demoDatasetState = demoDatasetState,
                            onGenerateDemo = demoDatasetViewModel::generate,
                            onImportDemo = demoDatasetViewModel::importHistory,
                            onVerifyDemo = demoDatasetViewModel::verify,
                            onExportDemo = demoDatasetViewModel::exportCsv,
                            onResetDemo = demoDatasetViewModel::reset,
                        )
                    }
                    AppOverlay.LEGACY_DASHBOARD -> {
                        BackHandler { activeOverlay = AppOverlay.DEVELOPER_LAB }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { activeOverlay = AppOverlay.DEVELOPER_LAB }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = "Back to Developer Lab",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Lab View (All 7 Subsystems)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            SensorScreen(
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
                                recentRecords = legacyAnalyticsState.records.takeLast(5).reversed(),
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
                        }
                    }
                    AppOverlay.NONE -> {
                        val isInspectingHistoryDetails = selectedTab == AppTab.HISTORY && historyState.selectedSessionDetails != null
                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            bottomBar = {
                                if (!isInspectingHistoryDetails) {
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ) {
                                        NavigationBarItem(
                                            selected = selectedTab == AppTab.TODAY,
                                            onClick = { selectedTab = AppTab.TODAY },
                                            icon = { Icon(Icons.Rounded.NightsStay, contentDescription = "Today") },
                                            label = { Text("Today") },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == AppTab.HISTORY,
                                            onClick = { selectedTab = AppTab.HISTORY },
                                            icon = { Icon(Icons.Rounded.History, contentDescription = "History") },
                                            label = { Text("History") },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == AppTab.INSIGHTS,
                                            onClick = { selectedTab = AppTab.INSIGHTS },
                                            icon = { Icon(Icons.Rounded.Analytics, contentDescription = "Insights") },
                                            label = { Text("Insights") },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == AppTab.DEVICES,
                                            onClick = { selectedTab = AppTab.DEVICES },
                                            icon = { Icon(Icons.Rounded.Watch, contentDescription = "Devices") },
                                            label = { Text("Devices") },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                    }
                                }
                            },
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                            ) {
                                when (selectedTab) {
                                    AppTab.TODAY -> TodayScreen(
                                        state = todayState,
                                        onStartRecording = {
                                            startRecordingWithRequiredPermission(
                                                mode = todayState.selectedSourceMode,
                                                placement = todayState.selectedPlacement,
                                            )
                                        },
                                        onPauseRecording = {
                                            handleRecordingCommand("Pause recording", RecordingService.pause(this@MainActivity))
                                        },
                                        onResumeRecording = {
                                            handleRecordingCommand("Resume recording", RecordingService.resume(this@MainActivity))
                                        },
                                        onStopRecording = {
                                            handleRecordingCommand("Stop recording", RecordingService.stop(this@MainActivity))
                                        },
                                        onOpenSettings = { activeOverlay = AppOverlay.SETTINGS },
                                        onOpenAutomations = { activeOverlay = AppOverlay.AUTOMATIONS },
                                        onDismissError = todayViewModel::dismissError,
                                    )
                                    AppTab.HISTORY -> HistoryScreen(
                                        state = historyState,
                                        onSelectFilter = historyViewModel::setFilter,
                                        onOpenSession = historyViewModel::openSessionDetails,
                                        onCloseDetails = historyViewModel::closeSessionDetails,
                                        onDeleteSession = historyViewModel::deleteSession,
                                    )
                                    AppTab.INSIGHTS -> InsightsScreen(
                                        state = insightsState,
                                        onSelectMode = insightsViewModel::selectMode,
                                    )
                                    AppTab.DEVICES -> DevicesScreen(
                                        state = devicesState,
                                        onConnectDevice = devicesViewModel::connectDevice,
                                        onDisconnectDevice = devicesViewModel::disconnectDevice,
                                        onStartScan = ::startBleScanWithPermissions,
                                        onSelectMode = devicesViewModel::selectMode,
                                    )
                                }
                            }
                        }
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
        sensorViewModel.setForegroundActive(true)
        actuatorViewModel.setForegroundActive(true)
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onResume", DiagnosticResult.SUCCESS)
    }

    override fun onPause() {
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onPause", DiagnosticResult.STARTED)
        actuatorViewModel.setForegroundActive(false)
        sensorViewModel.setForegroundActive(false)
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onPause", DiagnosticResult.SUCCESS)
        super.onPause()
    }

    override fun onDestroy() {
        DiagnosticLog.event(DiagnosticTags.LIFECYCLE, "onDestroy", DiagnosticResult.SUCCESS, details = mapOf("changingConfigurations" to isChangingConfigurations))
        super.onDestroy()
    }

    private enum class AppTab { TODAY, HISTORY, INSIGHTS, DEVICES }
    private enum class AppOverlay { NONE, SETTINGS, AUTOMATIONS, DEVELOPER_LAB, LEGACY_DASHBOARD }

    private fun startRecordingWithRequiredPermission(
        mode: com.example.sleeptrackersimulator.core.model.SourceMode,
        placement: com.example.sleeptrackersimulator.core.model.DevicePlacement,
    ) {
        val start: () -> Unit = {
            handleRecordingCommand("Start recording", RecordingService.start(
                this,
                mode = mode,
                placement = placement,
            ))
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            start()
        } else {
            pendingRecordingStart = start
            activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun handleRecordingCommand(operation: String, result: Result<Unit>) {
        result.exceptionOrNull()?.let { todayViewModel.reportServiceCommandFailure(operation, it) }
    }

    private fun startBleScanWithPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            devicesViewModel.startScan()
            return
        }
        val required = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            devicesViewModel.startScan()
        } else {
            pendingBleScan = true
            bluetoothPermissionLauncher.launch(required)
        }
    }

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
