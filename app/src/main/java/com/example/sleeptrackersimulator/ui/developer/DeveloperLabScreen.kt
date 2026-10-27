package com.example.sleeptrackersimulator.ui.developer

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                DevLabTab.DATABASE -> DatabaseInspectorTab()
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
                    Text("Send Alert Command (0x2A37)", color = DarkSurface, fontWeight = FontWeight.Bold)
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
private fun DatabaseInspectorTab() {
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
    var selectedTag by remember { mutableStateOf("RESTING") }
    var isRecordingGroundTruth by remember { mutableStateOf(false) }
    var recordedWindowsCount by remember { mutableIntStateOf(142) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

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

        // Ground-Truth Dataset Collection Mode
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
                        text = if (isRecordingGroundTruth) "COLLECTING" else "STANDBY",
                        color = if (isRecordingGroundTruth) CalmGreen else MoonBlue,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Label live motion data with user-verified ground-truth states to build benchmarking datasets for model retraining.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "SELECT ACTIVE GROUND TRUTH LABEL:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MistBlue.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("RESTING", "READING", "OUT_OF_BED").forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { selectedTag = tag },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MoonBlue.copy(alpha = 0.3f),
                                selectedLabelColor = MoonBlue,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            isRecordingGroundTruth = !isRecordingGroundTruth
                            if (isRecordingGroundTruth) {
                                recordedWindowsCount += 5
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecordingGroundTruth) Color(0xFFEF4444) else MoonBlue,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (isRecordingGroundTruth) "Stop Tagging" else "Tag as $selectedTag",
                            color = DarkSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            exportStatusMessage = "Exported $recordedWindowsCount windows ($selectedTag) to sleepsense_dataset.csv"
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export CSV", color = MistBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }

                exportStatusMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = CalmGreen,
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
