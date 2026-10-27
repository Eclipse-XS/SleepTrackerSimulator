package com.example.sleeptrackersimulator.ui.developer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleeptrackersimulator.core.model.SensorSample
import com.example.sleeptrackersimulator.dataset.DatasetCsvExporter
import com.example.sleeptrackersimulator.dataset.DatasetRecordingController
import com.example.sleeptrackersimulator.dataset.DatasetSession
import com.example.sleeptrackersimulator.dataset.DevicePlacement
import com.example.sleeptrackersimulator.dataset.ExperimentType
import com.example.sleeptrackersimulator.dataset.DemoDatasetUiState
import com.example.sleeptrackersimulator.dataset.GroundTruthActivity
import com.example.sleeptrackersimulator.ui.components.MetricCard
import com.example.sleeptrackersimulator.ui.components.StatusBadge
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurface
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.HeartCoral
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import com.example.sleeptrackersimulator.ui.theme.NightBlue

enum class DevLabTab(val title: String) {
    SENSORS("Sensors (L1)"),
    BLE("BLE (L2)"),
    CLOUD("Cloud (L3)"),
    ACTUATORS("Actuators (L4)"),
    DATABASE("Room (L5)"),
    ML("ML (L6)"),
    TRACE("Pipeline Trace (L7)"),
}

@Composable
fun DeveloperLabScreen(
    onBack: () -> Unit,
    onTriggerVibration: () -> Unit,
    onTriggerFlashlight: () -> Unit,
    onTriggerBleAlert: () -> Unit,
    onOpenLegacyDashboard: () -> Unit = {},
    isBleAdvertising: Boolean = false,
    onToggleBleAdvertising: () -> Unit = {},
    demoDatasetState: DemoDatasetUiState = DemoDatasetUiState(),
    onGenerateDemo: () -> Unit = {},
    onImportDemo: () -> Unit = {},
    onVerifyDemo: () -> Unit = {},
    onExportDemo: () -> Unit = {},
    onResetDemo: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = DevLabTab.values()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NightBlue),
    ) {
        // Top app bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MistBlue,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Developer Lab",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MistBlue,
                )
                Text(
                    text = "Labs 1–7 Diagnostic & Verification Console",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalmGreen,
                )
            }
            OutlinedButton(
                onClick = onOpenLegacyDashboard,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Lab View", color = MoonBlue, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Horizontal tabs
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = DarkSurface,
            contentColor = MoonBlue,
            edgePadding = 12.dp,
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (selectedTabIndex == index) MoonBlue else MistBlue.copy(alpha = 0.6f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
        }

        // Content area based on tab
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            when (tabs[selectedTabIndex]) {
                DevLabTab.SENSORS -> SensorInspectorTab()
                DevLabTab.BLE -> BleInspectorTab(
                    onSendAlert = onTriggerBleAlert,
                    isAdvertising = isBleAdvertising,
                    onToggleAdvertising = onToggleBleAdvertising,
                )
                DevLabTab.CLOUD -> CloudInspectorTab()
                DevLabTab.ACTUATORS -> ActuatorInspectorTab(onTriggerVibration, onTriggerFlashlight)
                DevLabTab.DATABASE -> DatabaseInspectorTab(
                    demoDatasetState, onGenerateDemo, onImportDemo, onVerifyDemo, onExportDemo, onResetDemo,
                )
                DevLabTab.ML -> MlInspectorTab()
                DevLabTab.TRACE -> PipelineTraceTab()
            }
        }
    }
}

@Composable
private fun SensorInspectorTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Sensor Ingestion Diagnostics",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(title = "Requested Hz", value = "20", subtitle = "SENSOR_DELAY_GAME", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(title = "Hardware State", value = "ACTIVE", accentColor = CalmGreen, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Sensor Event Contract", fontWeight = FontWeight.Bold, color = MistBlue)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Sensor: TYPE_ACCELEROMETER\n• Clock reference: SystemClock.elapsedRealtimeNanos()\n• Time anchor: UTC Millis calibrated\n• Dynamic calculation: |sqrt(x²+y²+z²) - 9.80665| m/s²",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun BleInspectorTab(
    onSendAlert: () -> Unit,
    isAdvertising: Boolean = false,
    onToggleAdvertising: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Bluetooth Low Energy & GATT Console",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(title = "Service UUID", value = "0x180D", subtitle = "Heart Rate Service", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(
                title = "GATT Status",
                value = if (isAdvertising) "ADVERTISING" else "READY",
                accentColor = if (isAdvertising) CalmGreen else MoonBlue,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Single-Radio Dual-Role Architecture Notice", fontWeight = FontWeight.Bold, color = MotionAmber)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Running BLE Central (scanner/client) and BLE Peripheral (GATT server) concurrently on the same physical Android radio causes controller-level time slicing, latency spikes, and potential packet drops.\n• For realistic physical testing, run the standalone companion app VirtualSleepBand (package com.example.virtualsleepband) on a second Android device or separate emulator instance.\n• The loopback advertiser below is maintained for automated single-device test pipelines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.85f),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "In-App Loopback Peripheral Controls", fontWeight = FontWeight.Bold, color = MistBlue)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onSendAlert,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MoonBlue),
                ) {
                    Text("Send Alert Command (Vendor UUID)", color = DarkSurface, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onToggleAdvertising,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (isAdvertising) "Stop BLE Peripheral Advertiser" else "Start BLE Peripheral Advertiser (GATT Server)",
                        color = MistBlue,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudInspectorTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Firebase Outbox & Recovery Status",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(title = "Sync Outbox", value = "0 pending", subtitle = "Durable SQLite queue", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(title = "Cloud Mode", value = "OPT-IN", accentColor = CalmGreen, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Security Rules Guarantee", fontWeight = FontWeight.Bold, color = MistBlue)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Scope: /users/{uid}/sessionSummaries/{sessionId}\n• Enforced owner authorization (auth.uid === \$uid)\n• Strict payload validation on timestamps and ranges\n• Idempotent replay protection on event IDs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ActuatorInspectorTab(onVibrate: () -> Unit, onFlashlight: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Actuator Controls & Hardware Safety",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(title = "Cooldown Guard", value = "ACTIVE", subtitle = "15-minute rate limit", accentColor = CalmGreen, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(title = "Outcome Policy", value = "SAFE", subtitle = "Unknown state on crash", modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Manual Actuator Trigger", fontWeight = FontWeight.Bold, color = MistBlue)
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = onVibrate,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoonBlue),
                    ) {
                        Text("Vibrate Pulse", color = DarkSurface, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = onFlashlight,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Test Flashlight")
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseInspectorTab(
    demoState: DemoDatasetUiState,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onVerify: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Room Database v7 Inspector",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(title = "Database Version", value = "v7", subtitle = "Room 2.8.5", accentColor = MoonBlue, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(title = "Migration Path", value = "1 → 7 PASS", subtitle = "Additive evolution", accentColor = CalmGreen, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("90-night synthetic demo history", fontWeight = FontWeight.Bold, color = MistBlue)
                Text(demoState.message, color = MistBlue.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                Text("Generated: ${demoState.generatedCount} · Imported: ${demoState.importedCount}", color = CalmGreen)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onGenerate, enabled = !demoState.isBusy) { Text("Generate") }
                    Button(onClick = onImport, enabled = !demoState.isBusy) { Text("Import") }
                    OutlinedButton(onClick = onVerify, enabled = !demoState.isBusy) { Text("Verify") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onExport, enabled = !demoState.isBusy) { Text("Export CSV") }
                    OutlinedButton(onClick = onReset, enabled = !demoState.isBusy) { Text("Reset demo") }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Managed Relational Tables", fontWeight = FontWeight.Bold, color = MistBlue)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• recording_sessions\n• sensor_samples (CASCADE delete)\n• heart_rate_samples (CASCADE delete)\n• feature_windows (CASCADE delete)\n• predictions (CASCADE delete)\n• session_summaries\n• personal_baselines\n• devices\n• smart_rules & smart_rule_executions\n• cloud_outbox & cloud_active_session\n• legacy_sensor_records (preserved)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun MlInspectorTab() {
    val context = LocalContext.current
    val controller = remember { DatasetRecordingController() }

    var selectedExperiment by remember { mutableStateOf(ExperimentType.CONTROLLED_REST) }
    var selectedPlacement by remember { mutableStateOf(DevicePlacement.MATTRESS) }
    var selectedGroundTruth by remember { mutableStateOf(GroundTruthActivity.RESTING) }

    var isRecording by remember { mutableStateOf(false) }
    var totalSampleCount by remember { mutableIntStateOf(0) }
    var validSampleCount by remember { mutableIntStateOf(0) }
    var rejectedSampleCount by remember { mutableIntStateOf(0) }
    var liveSamplingRateHz by remember { mutableDoubleStateOf(0.0) }
    var lastSavedFilePath by remember { mutableStateOf<String?>(null) }
    var lastExportSummary by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val csvDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val export = pendingCsv
        pendingCsv = null
        if (uri == null || export == null) {
            lastExportSummary = "Export cancelled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(export.first)
            } ?: error("Android could not open the selected document")
        }.onSuccess {
            lastSavedFilePath = uri.toString()
            lastExportSummary = "Exported ${export.second} real sensor samples"
        }.onFailure { error ->
            lastExportSummary = "Export failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    DisposableEffect(isRecording) {
        if (!isRecording) return@DisposableEffect onDispose {}

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        var lastCalcTimeMs = System.currentTimeMillis()
        var samplesSinceCalc = 0

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || !isRecording) return
                val sample = SensorSample(
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    timestampNanos = event.timestamp,
                )
                controller.processSample(sample, predictedClass = "LIVE")
                totalSampleCount = controller.sampleCount
                validSampleCount = controller.validSampleCount
                rejectedSampleCount = controller.rejectedSampleCount

                samplesSinceCalc++
                val now = System.currentTimeMillis()
                val elapsed = now - lastCalcTimeMs
                if (elapsed >= 1000L) {
                    liveSamplingRateHz = (samplesSinceCalc.toDouble() / elapsed.toDouble()) * 1000.0
                    samplesSinceCalc = 0
                    lastCalcTimeMs = now
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "TensorFlow Lite On-Device ML",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCard(title = "Input Tensor", value = "[1, 20, 3]", subtitle = "20 samples x 3 axes", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(title = "Classes", value = "3", subtitle = "REST, RESTLESS, ACTIVE", accentColor = CalmGreen, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Real Ground-Truth Dataset Collection Mode
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Dataset Collection & Ground Truth", fontWeight = FontWeight.Bold, color = MistBlue)
                    StatusBadge(
                        text = if (isRecording) "COLLECTING LIVE" else "STANDBY",
                        color = if (isRecording) CalmGreen else MoonBlue,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Collects genuine hardware accelerometer timestamps and XYZ readings for verifiable ML model retraining and pandas evaluation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 1. Experiment Selector
                Text(
                    text = "EXPERIMENT PROTOCOL:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MistBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ExperimentType.CONTROLLED_REST, ExperimentType.READING_IN_BED, ExperimentType.STATIC_BENCH).forEach { exp ->
                        FilterChip(
                            selected = selectedExperiment == exp,
                            onClick = { selectedExperiment = exp },
                            label = { Text(exp.name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MoonBlue.copy(alpha = 0.3f),
                                selectedLabelColor = MoonBlue,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Placement Selector
                Text(
                    text = "DEVICE PLACEMENT:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MistBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(DevicePlacement.MATTRESS, DevicePlacement.NIGHTSTAND, DevicePlacement.BODY_WRIST).forEach { plc ->
                        FilterChip(
                            selected = selectedPlacement == plc,
                            onClick = { selectedPlacement = plc },
                            label = { Text(plc.name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MoonBlue.copy(alpha = 0.3f),
                                selectedLabelColor = MoonBlue,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Ground Truth Label Selector
                Text(
                    text = "ACTIVE GROUND TRUTH LABEL:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MistBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GroundTruthActivity.RESTING, GroundTruthActivity.READING, GroundTruthActivity.OUT_OF_BED).forEach { tag ->
                        FilterChip(
                            selected = selectedGroundTruth == tag,
                            onClick = {
                                selectedGroundTruth = tag
                                if (isRecording) {
                                    controller.updateGroundTruth(tag)
                                }
                            },
                            label = { Text(tag.name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CalmGreen.copy(alpha = 0.3f),
                                selectedLabelColor = CalmGreen,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Telemetry Row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Valid: $validSampleCount | Rejected: $rejectedSampleCount",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MistBlue,
                    )
                    Text(
                        text = "Rate: ${String.format(Locale.US, "%.1f", liveSamplingRateHz)} Hz",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = CalmGreen,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (!isRecording) {
                                controller.start(selectedExperiment, selectedPlacement, selectedGroundTruth)
                                isRecording = true
                                lastExportSummary = null
                            } else {
                                isRecording = false
                                val session = controller.stop()
                                lastExportSummary = "Recorded ${session.validCount} valid samples at ${String.format(Locale.US, "%.1f", session.actualSamplingRateHz)} Hz"
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFEF4444) else MoonBlue,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (isRecording) "Stop Recording" else "Record Sensor (${selectedGroundTruth.name})",
                            color = DarkSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val session = controller.stop()
                            pendingCsv = DatasetCsvExporter.generateCsv(session) to session.validCount
                            csvDocumentLauncher.launch(
                                "sleepsense_${session.experimentType.name.lowercase()}_${session.startTimeUtcMs}.csv",
                            )
                        },
                        enabled = controller.sampleCount > 0 && !isRecording,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export CSV File", color = MistBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }

                lastExportSummary?.let { summary ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = CalmGreen,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                lastSavedFilePath?.let { path ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Path: $path",
                        style = MaterialTheme.typography.labelSmall,
                        color = MistBlue.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clinical Validation Disclaimer
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Clinical Validation & Medical Disclaimer", fontWeight = FontWeight.Bold, color = MotionAmber)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• NOT A MEDICAL DEVICE: SleepSense estimates night movement activity and relative restfulness using smartphone accelerometry.\n• It is NOT a medical polysomnograph (PSG), cannot diagnose sleep apnea, insomnia, or sleep disorders, and must not be used for clinical decisions.\n• Predictions are classified locally using an on-device TFLite model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.85f),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Preprocessing & Model Registry", fontWeight = FontWeight.Bold, color = MistBlue)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• Model: activity_classifier.tflite\n• Internal Keras mean-subtraction layer\n• Training seed: 4319 (deterministic)\n• Quantization: Float32 FlatBuffer\n• Golden-vector parity verified",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun PipelineTraceTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "End-to-End Pipeline Trace",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MistBlue,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                TraceStep(step = "1. INGESTION", detail = "SensorEvent (XYZ) received from Android SensorManager")
                TraceStep(step = "2. VALIDATION", detail = "SampleValidator verifies finite numbers and physical limits")
                TraceStep(step = "3. WINDOWING", detail = "SlidingWindowSegmenter groups 20 samples into window")
                TraceStep(step = "4. FEATURES", detail = "FeatureExtractor calculates dynamic RMS, variance, jerk")
                TraceStep(step = "5. INFERENCE", detail = "ActivityClassifier executes TFLite model -> Prediction")
                TraceStep(step = "6. SMART RULES", detail = "SmartRuleEngine evaluates conditions & safety cooldown")
                TraceStep(step = "7. PERSISTENCE", detail = "Room v7 commits sample batches, predictions, and executions")
                TraceStep(step = "8. SYNC", detail = "Durable Outbox queues summaries for WorkManager -> Firebase", isLast = true)
            }
        }
    }
}

@Composable
private fun TraceStep(step: String, detail: String, isLast: Boolean = false) {
    Column {
        Text(text = step, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MoonBlue)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MistBlue.copy(alpha = 0.8f))
        if (!isLast) {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
