package com.example.sleeptrackersimulator.ui.dashboard

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.actuator.ActuatorUiState
import com.example.sleeptrackersimulator.actuator.ActuatorAction
import com.example.sleeptrackersimulator.actuator.TriggerSource
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.BleOperationResult
import com.example.sleeptrackersimulator.ble.HeartRateMeasurement
import com.example.sleeptrackersimulator.ble.HeartRateProfile
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.cloud.CloudSyncStatus
import com.example.sleeptrackersimulator.cloud.CloudSyncUiState
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.HeartCoral
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import com.example.sleeptrackersimulator.rest.RestState
import com.example.sleeptrackersimulator.rest.RestStateResult
import com.example.sleeptrackersimulator.ml.ActivityClass
import com.example.sleeptrackersimulator.ml.MlUiState
import com.example.sleeptrackersimulator.history.SensorRecord
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorScreen(
    uiState: SensorUiState,
    bleState: BleConnectionState,
    bleLogs: List<String>,
    heartRate: HeartRateMeasurement?,
    heartRateHistory: List<Int>,
    notificationsEnabled: Boolean,
    restState: RestStateResult,
    mlState: MlUiState,
    cloudSyncState: CloudSyncUiState,
    actuatorState: ActuatorUiState,
    recentRecords: List<SensorRecord>,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRead: () -> BleOperationResult,
    onWrite: (String) -> BleOperationResult,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
    onAutomaticControlChanged: (Boolean) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onFlashlightChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(cloudSyncState.status) {
        if (cloudSyncState.status == CloudSyncStatus.PENDING || cloudSyncState.status == CloudSyncStatus.ERROR) {
            Toast.makeText(context, cloudSyncState.lastErrorMessage ?: "Cloud synchronization failed", Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) { padding ->
        if (bleState is BleConnectionState.Connected) {
            ConnectedDashboard(
                uiState, bleState.device, heartRate, heartRateHistory, restState, mlState, cloudSyncState,
                actuatorState,
                recentRecords,
                Modifier.padding(padding),
                onDiagnostics = { showDiagnostics = true },
                onDisconnect = onDisconnect,
                onStartMonitoring = onStartMonitoring,
                onEndMonitoring = onEndMonitoring,
                onAutomaticControlChanged = onAutomaticControlChanged,
                onVibrationChanged = onVibrationChanged,
                onFlashlightChanged = onFlashlightChanged,
            )
        } else {
            DisconnectedExperience(
                uiState, bleState, mlState, cloudSyncState, actuatorState, recentRecords, onScan, onConnect,
                onStartMonitoring, onEndMonitoring, onAutomaticControlChanged,
                onVibrationChanged, onFlashlightChanged, Modifier.padding(padding),
            )
        }
    }

    if (showDiagnostics) {
        ModalBottomSheet(
            onDismissRequest = { showDiagnostics = false },
            containerColor = DarkSurfaceRaised,
        ) {
            DiagnosticsContent(bleState, bleLogs, notificationsEnabled, heartRate, restState, cloudSyncState, onRead, onWrite)
        }
    }
}

@Composable
private fun DisconnectedExperience(
    sensorState: SensorUiState,
    bleState: BleConnectionState,
    mlState: MlUiState,
    cloudSyncState: CloudSyncUiState,
    actuatorState: ActuatorUiState,
    recentRecords: List<SensorRecord>,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
    onAutomaticControlChanged: (Boolean) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onFlashlightChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppHeader()
        Spacer(Modifier.height(24.dp))
        BandIllustration(bleState is BleConnectionState.Scanning || bleState is BleConnectionState.Connecting)
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (bleState) {
                BleConnectionState.Scanning -> "Looking for your band"
                is BleConnectionState.Connecting -> "Connecting"
                is BleConnectionState.DeviceFound -> "Sleep Band found"
                is BleConnectionState.Error -> "Connection interrupted"
                else -> "No wearable connected"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = when (bleState) {
                BleConnectionState.Scanning -> "Searching for the virtual Heart Rate Service"
                is BleConnectionState.Connecting -> "Preparing Heart Rate notifications"
                is BleConnectionState.DeviceFound -> "Ready to begin simulated monitoring"
                is BleConnectionState.Error -> bleState.message
                else -> "Connect the virtual Sleep Band to start simulated heart-rate monitoring."
            },
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        ConnectionAction(bleState, onScan, onConnect)
        Spacer(Modifier.height(28.dp))
        PhoneSensorStrip(sensorState)
        Spacer(Modifier.height(12.dp))
        AiInsightsCard(mlState)
        Spacer(Modifier.height(12.dp))
        RecentRecordsCard(recentRecords)
        Spacer(Modifier.height(18.dp))
        CloudSyncCard(cloudSyncState)
        Spacer(Modifier.height(12.dp))
        MonitoringAction(cloudSyncState, onStartMonitoring, onEndMonitoring)
        Spacer(Modifier.height(18.dp))
        ActuatorPanel(
            actuatorState, onAutomaticControlChanged, onVibrationChanged, onFlashlightChanged,
        )
    }
}

@Composable
private fun AppHeader() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text("Sleep Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Your night, in signals", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BandIllustration(busy: Boolean) {
    val bandColor = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(152.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(82.dp)) {
                drawRoundRect(bandColor.copy(alpha = .22f), Offset(size.width * .35f, 0f), Size(size.width * .3f, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f))
                drawRoundRect(bandColor, Offset(size.width * .19f, size.height * .28f), Size(size.width * .62f, size.height * .44f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f), style = Stroke(width = 7f))
                drawCircle(HeartCoral, radius = 7f, center = center)
            }
            if (busy) CircularProgressIndicator(Modifier.size(138.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ConnectionAction(state: BleConnectionState, onScan: () -> Unit, onConnect: (BleDevice) -> Unit) {
    when (state) {
        BleConnectionState.Scanning, is BleConnectionState.Connecting -> CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
        is BleConnectionState.DeviceFound -> if (state.devices.isEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No virtual devices discovered", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Scan again") }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.devices.forEach { device ->
                    OutlinedButton(
                        onClick = { onConnect(device) },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) { Text("${device.name} · ${device.address}") }
                }
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Scan again") }
            }
        }
        else -> Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (state is BleConnectionState.Error) "Try again" else "Find Sleep Band") }
    }
}

@Composable
private fun PhoneSensorStrip(state: SensorUiState) {
    val analysis = (state as? SensorUiState.Active)?.analysis
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.Sensors, contentDescription = null, tint = if (analysis != null) MotionAmber else MaterialTheme.colorScheme.outline)
                Column(Modifier.weight(1f)) {
                    Text("Accelerometer diagnostics", fontWeight = FontWeight.SemiBold)
                    Text(sensorDescription(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (analysis != null) {
                val sample = analysis.sample
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("X" to sample.x, "Y" to sample.y, "Z" to sample.z).forEach { (axis, value) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(axis, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(format(value), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                                Text("m/s²", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val gridColor = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(72.dp)) {
                    val values = listOf(sample.x, sample.y, sample.z)
                    val colors = listOf(MotionAmber, CalmGreen, HeartCoral)
                    val rowHeight = size.height / 3f
                    values.forEachIndexed { index, value ->
                        val y = rowHeight * (index + .5f)
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 2f)
                        val normalized = (value / 20f).coerceIn(-1f, 1f)
                        drawLine(colors[index], Offset(size.width / 2f, y), Offset(size.width / 2f + normalized * size.width / 2f, y), 10f, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedDashboard(
    sensorState: SensorUiState,
    device: BleDevice,
    measurement: HeartRateMeasurement?,
    history: List<Int>,
    restState: RestStateResult,
    mlState: MlUiState,
    cloudSyncState: CloudSyncUiState,
    actuatorState: ActuatorUiState,
    recentRecords: List<SensorRecord>,
    modifier: Modifier = Modifier,
    onDiagnostics: () -> Unit,
    onDisconnect: () -> Unit,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
    onAutomaticControlChanged: (Boolean) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onFlashlightChanged: (Boolean) -> Unit,
) {
    val compact = LocalConfiguration.current.screenHeightDp < 760
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 14.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Sleep Tracker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("Tonight · ${device.name}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Surface(color = CalmGreen.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(MotionAmber, CircleShape)); Text("LEGACY MOCK BLE · SIMULATED", color = MotionAmber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        HeartRateHero(measurement)
        RestEstimate(restState)
        HeartRateChart(history)
        PhoneSensorStrip(sensorState)
        MovementSummary(sensorState)
        AiInsightsCard(mlState)
        RecentRecordsCard(recentRecords)
        CloudSyncCard(cloudSyncState)
        MonitoringAction(cloudSyncState, onStartMonitoring, onEndMonitoring)
        ActuatorPanel(
            actuatorState, onAutomaticControlChanged, onVibrationChanged, onFlashlightChanged,
        )
        DeviceSummary(device, onDiagnostics)
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Disconnect Sleep Band") }
        Text("Educational estimate · not medical sleep analysis", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RestEstimate(result: RestStateResult) {
    val color = when (result.state) {
        RestState.MONITORING -> MaterialTheme.colorScheme.primary
        RestState.ACTIVE -> MotionAmber
        RestState.RESTING -> CalmGreen
        RestState.POSSIBLE_SLEEP -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = result.state.name.replace('_', ' '),
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Text(
            text = "Heuristic academic estimate · ${result.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeartRateHero(measurement: HeartRateMeasurement?) {
    Surface(color = HeartCoral.copy(alpha = .08f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("♥", color = HeartCoral, fontSize = 26.sp)
            Spacer(Modifier.size(12.dp))
            Text(measurement?.beatsPerMinute?.toString() ?: "—", fontSize = 46.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Column { Text("BPM", fontWeight = FontWeight.Bold); Text("Legacy simulated HR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun HeartRateChart(samples: List<Int>) {
    val line = HeartCoral
    val grid = MaterialTheme.colorScheme.outlineVariant
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Heart rate over time", fontWeight = FontWeight.SemiBold)
            Text("Y: heart rate (BPM) · X: elapsed seconds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val low = ((samples.minOrNull() ?: 45) - 4).coerceAtLeast(30)
            val high = ((samples.maxOrNull() ?: 90) + 4).coerceAtMost(240)
            val mid = (low + high) / 2
            Row(Modifier.fillMaxWidth().height(104.dp)) {
                Column(Modifier.padding(end = 6.dp).height(104.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    Text("$high", style = MaterialTheme.typography.labelSmall)
                    Text("$mid", style = MaterialTheme.typography.labelSmall)
                    Text("$low", style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.weight(1f).height(104.dp)) {
                repeat(3) { i -> val y = size.height * i / 2f; drawLine(grid.copy(alpha = .35f), Offset(0f, y), Offset(size.width, y), 1f) }
                if (samples.size < 2) return@Canvas
                val range = (high - low).coerceAtLeast(1).toFloat()
                val path = Path()
                samples.forEachIndexed { index, bpm ->
                    val x = size.width * index / samples.lastIndex
                    val y = size.height - (bpm - low) / range * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(5f, cap = StrokeCap.Round))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 26.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("-${(samples.size - 1).coerceAtLeast(0)} s", style = MaterialTheme.typography.labelSmall)
                Text("now", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MovementSummary(state: SensorUiState) {
    val analysis = (state as? SensorUiState.Active)?.analysis
    val moving = analysis?.state == MovementState.MOVEMENT
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(analysis?.let { if (moving) "MOVEMENT" else "STILL" } ?: "UNAVAILABLE", style = MaterialTheme.typography.headlineSmall, color = if (moving) MotionAmber else if (analysis != null) CalmGreen else MaterialTheme.colorScheme.error); Text("Phone accelerometer", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(horizontalAlignment = Alignment.End) { Text(analysis?.let { format(it.motionIntensity) } ?: "—", style = MaterialTheme.typography.headlineSmall); Text("intensity m/s²", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AiInsightsCard(state: MlUiState) {
    val ready = state as? MlUiState.Ready
    val color = when (ready?.prediction?.activity) {
        ActivityClass.REST -> CalmGreen
        ActivityClass.RESTLESS -> MotionAmber
        ActivityClass.ACTIVE -> HeartCoral
        null -> MaterialTheme.colorScheme.outline
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = color)
                Text("AI Insights", fontWeight = FontWeight.SemiBold)
            }
            Text(
                when (state) {
                    MlUiState.Collecting -> "Collecting 20 accelerometer samples"
                    is MlUiState.Error -> "Model error: ${state.message}"
                    is MlUiState.Ready -> "${state.prediction.activity.name} · ${String.format(Locale.US, "%.1f%%", state.prediction.confidence * 100f)}"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = color,
            )
            ready?.prediction?.let { prediction ->
                Text(
                    "On-device TFLite · ${String.format(Locale.US, "%.2f ms", prediction.inferenceNanos / 1_000_000.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecentRecordsCard(records: List<SensorRecord>) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last five Room records", fontWeight = FontWeight.SemiBold)
            if (records.isEmpty()) Text("No persisted measurements yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            records.take(5).forEach { record ->
                Text("#${record.id} · ${format(record.sensorValue)} m/s²${if (record.actionTriggered) " · action" else ""}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeviceSummary(device: BleDevice, onDiagnostics: () -> Unit) {
    Surface(onClick = onDiagnostics, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(device.name, fontWeight = FontWeight.SemiBold); Text("Virtual device · Simulated Heart Rate Service", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Diagnostics  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(color = androidx.compose.ui.graphics.Color(0xFF1E2A3A), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
            content()
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String, mono: Boolean = false, valueColor: androidx.compose.ui.graphics.Color = DiagText) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = DiagMuted)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (mono) 12.sp else 14.sp, lineHeight = if (mono) 17.sp else 20.sp),
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = valueColor,
        )
    }
}

private val DiagText = androidx.compose.ui.graphics.Color(0xFFE8EEF7)
private val DiagMuted = androidx.compose.ui.graphics.Color(0xFFA9B8CC)

@Composable
private fun DiagnosticsContent(
    state: BleConnectionState,
    logs: List<String>,
    notify: Boolean,
    heartRate: HeartRateMeasurement?,
    restState: RestStateResult,
    cloudSyncState: CloudSyncUiState,
    onRead: () -> BleOperationResult,
    onWrite: (String) -> BleOperationResult,
) {
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val connected = state is BleConnectionState.Connected
    val health = DiagnosticsModel.hrStreamHealth(heartRate?.timestampNanos, System.nanoTime())
    val cloudError = DiagnosticsModel.cloudErrorSummary(cloudSyncState.pendingCount, cloudSyncState.lastErrorMessage, cloudSyncState.storageErrorMessage)
    fun run(op: () -> BleOperationResult) {
        scope.launch { busy = true; kotlinx.coroutines.yield(); result = operationText(op()); busy = false }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Device & BLE diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color.White)

        DiagCard("BLE") {
            DiagLine("Transport", "LEGACY MOCK BLE · SIMULATED — not physical BLE, not WebSocket", valueColor = MotionAmber)
            DiagLine("Connection", if (connected) "Connected (mock link)" else "Disconnected")
            DiagLine("HR stream", DiagnosticsModel.healthLabel(health, notify), valueColor = if (health == HrStreamHealth.LIVE) CalmGreen else MotionAmber)
            DiagLine("Notification subscription", if (notify) "Subscribed (not proof of data flow)" else "Not subscribed")
            DiagLine("Heart Rate Service", HeartRateProfile.SERVICE_SHORT_ID, mono = true)
            DiagLine("HR Measurement (Notify)", HeartRateProfile.MEASUREMENT_SHORT_ID, mono = true)
            DiagLine("Body location (Read)", HeartRateProfile.BODY_SENSOR_LOCATION_SHORT_ID, mono = true)
            DiagLine("Command (Write, custom)", HeartRateProfile.COMMAND_ID, mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Read location" to { run(onRead) }, "Write status" to { run { onWrite("REQUEST_STATUS") } }).forEach { (label, action) ->
                    OutlinedButton(
                        onClick = action,
                        enabled = connected && !busy,
                        modifier = Modifier.weight(1f).height(48.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (connected && !busy) androidx.compose.ui.graphics.Color(0xFF8FB4FF) else DiagMuted.copy(alpha = .4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color(0xFFBFD4FF), disabledContentColor = DiagMuted.copy(alpha = .5f)),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = DiagMuted) else Text(label)
                    }
                }
            }
            if (!connected) Text("Buttons are disabled while disconnected.", style = MaterialTheme.typography.bodySmall, color = DiagMuted)
            result?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFBFD4FF), style = MaterialTheme.typography.bodySmall) }
        }

        DiagCard("State estimation") {
            DiagLine("Current (heuristic estimate, not LiteRT inference)", restState.state.name.replace('_', ' '))
            DiagLine("Still duration", "${restState.stillDurationMillis / 1_000}s")
            DiagLine("HR samples", restState.heartRateSampleCount.toString())
            DiagLine("Recent HR average", restState.recentAverageHeartRate?.let { String.format(Locale.US, "%.1f BPM", it) } ?: "—")
            DiagLine("HR variation (σ)", restState.heartRateVariation?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
            Text("POSSIBLE SLEEP is a rule-based heuristic, not a production LiteRT/TFLite model output.", style = MaterialTheme.typography.bodySmall, color = DiagMuted)
        }

        DiagCard("Cloud diagnostics") {
            DiagLine("Status", cloudSyncState.status.name, valueColor = if (cloudError != null) HeartCoral else DiagText)
            DiagLine("Uploaded / Pending", "${cloudSyncState.uploadedCount} / ${cloudSyncState.pendingCount}")
            DiagLine("Dropped telemetry", cloudSyncState.droppedTelemetryCount.toString())
            DiagLine("Session", cloudSyncState.sessionId ?: "not created", mono = true)
            if (cloudError != null) {
                Surface(color = HeartCoral.copy(alpha = .18f), shape = RoundedCornerShape(10.dp)) {
                    Text(cloudError, Modifier.padding(12.dp), color = androidx.compose.ui.graphics.Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                DiagLine("Last error", "none")
            }
        }

        DiagCard("BLE terminal") {
            Surface(color = androidx.compose.ui.graphics.Color(0xFF0E1621), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 220.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val lines = logs.takeLast(50)
                    if (lines.isEmpty()) Text("No BLE events yet", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = DiagMuted)
                    lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = DiagText) }
                }
            }
        }
    }
}
@Composable
private fun CloudSyncCard(state: CloudSyncUiState) {
    val healthy = state.status == CloudSyncStatus.SYNCED
    val color = when (state.status) {
        CloudSyncStatus.SYNCED -> CalmGreen
        CloudSyncStatus.SYNCING -> MaterialTheme.colorScheme.primary
        CloudSyncStatus.IDLE -> MaterialTheme.colorScheme.outline
        CloudSyncStatus.PENDING, CloudSyncStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = color)
            Column(Modifier.weight(1f)) {
                Text("Cloud sync", fontWeight = FontWeight.SemiBold)
                Text(
                    when (state.status) {
                        CloudSyncStatus.IDLE -> "Waiting for first 5-second sample"
                        CloudSyncStatus.SYNCING -> "Uploading sensor snapshot"
                        CloudSyncStatus.SYNCED -> "Synced · ${state.uploadedCount} uploaded"
                        CloudSyncStatus.PENDING -> "Pending · ${state.pendingCount} queued"
                        CloudSyncStatus.ERROR -> "Error · ${state.pendingCount} queued"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (healthy) "SYNCED" else state.status.name, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MonitoringAction(
    state: CloudSyncUiState,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
) {
    OutlinedButton(
        onClick = if (state.monitoringActive) onEndMonitoring else onStartMonitoring,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        enabled = state.status != CloudSyncStatus.SYNCING,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Text(if (state.monitoringActive) "End Monitoring" else "Start Monitoring")
    }
}

@Composable
private fun ActuatorPanel(
    state: ActuatorUiState,
    onAutomaticControlChanged: (Boolean) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onFlashlightChanged: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                    Text("Actuators", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Hardware and remote control",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    }
                }
                StatusBadge(
                    text = when {
                        state.remoteListenerConnected -> "REMOTE CONNECTED"
                        state.lastError != null -> "REMOTE ERROR"
                        else -> "REMOTE CONNECTING"
                    },
                    color = when {
                        state.remoteListenerConnected -> CalmGreen
                        state.lastError != null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                title = "Automatic movement alert",
                supportingText = "Triggers a vibration pulse when movement begins",
                checked = state.automaticControlEnabled,
                enabled = state.vibratorAvailable,
                onCheckedChange = onAutomaticControlChanged,
            )
            Text(
                "System torch state: ${state.observedFlashlightEnabled?.toString() ?: "unavailable"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.lastApiResult?.let { result ->
                Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Text("Manual control", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.Vibration, contentDescription = null) },
                title = "Vibration",
                supportingText = if (state.vibratorAvailable) "Logical state; physical response is not confirmed" else "Vibrator hardware unavailable",
                checked = state.vibrationEnabled,
                enabled = state.vibratorAvailable,
                onCheckedChange = onVibrationChanged,
            )
            SettingSwitchRow(
                icon = { Icon(Icons.Rounded.FlashlightOn, contentDescription = null) },
                title = "Flashlight",
                supportingText = if (state.flashlightAvailable) "Uses the rear camera torch" else "Camera torch unavailable",
                checked = state.flashlightEnabled,
                enabled = state.flashlightAvailable,
                onCheckedChange = onFlashlightChanged,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Text(
                "Last action: ${actionLabel(state.lastActionTriggered)}${state.lastTriggerSource?.let { " · ${sourceLabel(it)}" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.lastError?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun actionLabel(action: ActuatorAction): String = when (action) {
    ActuatorAction.NONE -> "None"
    ActuatorAction.VIBRATION_PULSE -> "Vibration pulse requested"
    ActuatorAction.VIBRATION_ENABLED -> "Vibration enabled"
    ActuatorAction.VIBRATION_DISABLED -> "Vibration disabled"
    ActuatorAction.FLASHLIGHT_ENABLED -> "Flashlight enabled"
    ActuatorAction.FLASHLIGHT_DISABLED -> "Flashlight disabled"
}

private fun sourceLabel(source: TriggerSource): String = when (source) {
    TriggerSource.MANUAL -> "Manual"
    TriggerSource.REMOTE -> "Firebase remote"
    TriggerSource.LOCAL_AUTOMATIC -> "Automatic movement"
}

private fun operationText(result: BleOperationResult): String = when (result) {
    is BleOperationResult.Success -> result.message
    is BleOperationResult.Error -> "Error: ${result.message}"
}

private fun sensorDescription(state: SensorUiState): String = when (state) {
    SensorUiState.Initializing -> "Starting movement monitoring"
    SensorUiState.Paused -> "Movement monitoring paused"
    SensorUiState.Unavailable -> "Accelerometer unavailable"
    is SensorUiState.Error -> state.message
    is SensorUiState.Active -> "${if (state.analysis.state == MovementState.MOVEMENT) "MOVEMENT" else "STILL"} · ${format(state.analysis.motionIntensity)} m/s²"
}

private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
