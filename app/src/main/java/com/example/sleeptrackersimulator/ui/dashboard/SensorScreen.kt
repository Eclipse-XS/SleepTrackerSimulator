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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
    cloudSyncState: CloudSyncUiState,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRead: () -> BleOperationResult,
    onWrite: (String) -> BleOperationResult,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
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
                uiState, bleState.device, heartRate, heartRateHistory, restState, cloudSyncState,
                Modifier.padding(padding),
                onDiagnostics = { showDiagnostics = true },
                onDisconnect = onDisconnect,
                onStartMonitoring = onStartMonitoring,
                onEndMonitoring = onEndMonitoring,
            )
        } else {
            DisconnectedExperience(
                uiState, bleState, cloudSyncState, onScan, onConnect,
                onStartMonitoring, onEndMonitoring, Modifier.padding(padding),
            )
        }
    }

    if (showDiagnostics) {
        ModalBottomSheet(
            onDismissRequest = { showDiagnostics = false },
            containerColor = DarkSurfaceRaised,
        ) {
            DiagnosticsContent(bleState, bleLogs, notificationsEnabled, restState, cloudSyncState, onRead, onWrite)
        }
    }
}

@Composable
private fun DisconnectedExperience(
    sensorState: SensorUiState,
    bleState: BleConnectionState,
    cloudSyncState: CloudSyncUiState,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppHeader()
        Spacer(Modifier.height(58.dp))
        BandIllustration(bleState is BleConnectionState.Scanning || bleState is BleConnectionState.Connecting)
        Spacer(Modifier.height(26.dp))
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
                is BleConnectionState.DeviceFound -> "Ready to begin live monitoring"
                is BleConnectionState.Error -> bleState.message
                else -> "Connect your virtual Sleep Band to start live heart-rate monitoring."
            },
            modifier = Modifier.padding(top = 9.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        ConnectionAction(bleState, onScan, onConnect)
        Spacer(Modifier.height(56.dp))
        PhoneSensorStrip(sensorState)
        Spacer(Modifier.height(18.dp))
        CloudSyncCard(cloudSyncState)
        Spacer(Modifier.height(12.dp))
        MonitoringAction(cloudSyncState, onStartMonitoring, onEndMonitoring)
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
        is BleConnectionState.DeviceFound -> Button(onClick = { onConnect(state.devices.first()) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Connect Sleep Band") }
        else -> Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (state is BleConnectionState.Error) "Try again" else "Find Sleep Band") }
    }
}

@Composable
private fun PhoneSensorStrip(state: SensorUiState) {
    val analysis = (state as? SensorUiState.Active)?.analysis
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(10.dp).background(if (analysis != null) MotionAmber else MaterialTheme.colorScheme.outline, CircleShape))
            Column(Modifier.weight(1f)) {
                Text("Phone movement sensor", fontWeight = FontWeight.SemiBold)
                Text(sensorDescription(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (analysis != null) Text(format(analysis.motionIntensity), fontWeight = FontWeight.SemiBold)
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
    cloudSyncState: CloudSyncUiState,
    modifier: Modifier = Modifier,
    onDiagnostics: () -> Unit,
    onDisconnect: () -> Unit,
    onStartMonitoring: () -> Unit,
    onEndMonitoring: () -> Unit,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Sleep Tracker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("Tonight · ${device.name}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Surface(color = CalmGreen.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(CalmGreen, CircleShape)); Text("LIVE", color = CalmGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        HeartRateHero(measurement)
        RestEstimate(restState)
        HeartRateChart(history)
        MovementSummary(sensorState)
        CloudSyncCard(cloudSyncState)
        MonitoringAction(cloudSyncState, onStartMonitoring, onEndMonitoring)
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
            text = result.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeartRateHero(measurement: HeartRateMeasurement?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("♥", color = HeartCoral, fontSize = 34.sp)
        Text(measurement?.beatsPerMinute?.toString() ?: "…", fontSize = 78.sp, lineHeight = 82.sp, fontWeight = FontWeight.Light)
        Text("BPM", letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Live heart rate", modifier = Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeartRateChart(samples: List<Int>) {
    val line = HeartCoral
    val grid = MaterialTheme.colorScheme.outlineVariant
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                repeat(3) { i -> val y = size.height * i / 2f; drawLine(grid.copy(alpha = .35f), Offset(0f, y), Offset(size.width, y), 1f) }
                if (samples.size < 2) return@Canvas
                val low = (samples.minOrNull() ?: 45) - 4
                val high = (samples.maxOrNull() ?: 90) + 4
                val range = (high - low).coerceAtLeast(1).toFloat()
                val path = Path()
                samples.forEachIndexed { index, bpm ->
                    val x = size.width * index / samples.lastIndex
                    val y = size.height - (bpm - low) / range * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(5f, cap = StrokeCap.Round))
            }
            Text("Live timeline · last ${samples.size.coerceAtMost(60)} samples", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MovementSummary(state: SensorUiState) {
    val analysis = (state as? SensorUiState.Active)?.analysis
    val moving = analysis?.state == MovementState.MOVEMENT
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text(if (moving) "MOVEMENT" else "STILL", style = MaterialTheme.typography.headlineSmall, color = if (moving) MotionAmber else CalmGreen); Text("Phone accelerometer", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(horizontalAlignment = Alignment.End) { Text(analysis?.let { format(it.motionIntensity) } ?: "—", style = MaterialTheme.typography.headlineSmall); Text("intensity m/s²", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DeviceSummary(device: BleDevice, onDiagnostics: () -> Unit) {
    Surface(onClick = onDiagnostics, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(device.name, fontWeight = FontWeight.SemiBold); Text("Connected · Heart Rate Service", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Diagnostics  ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DiagnosticsContent(
    state: BleConnectionState,
    logs: List<String>,
    notify: Boolean,
    restState: RestStateResult,
    cloudSyncState: CloudSyncUiState,
    onRead: () -> BleOperationResult,
    onWrite: (String) -> BleOperationResult,
) {
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Device & BLE diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Connection: ${if (state is BleConnectionState.Connected) "Connected" else "Disconnected"}\nHeart Rate Service: ${HeartRateProfile.SERVICE_SHORT_ID}\nHR Measurement: ${HeartRateProfile.MEASUREMENT_SHORT_ID}\nNotifications: ${if (notify) "Active" else "Stopped"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { result = operationText(onRead()) }, Modifier.weight(1f)) { Text("Read location") }
            OutlinedButton(onClick = { result = operationText(onWrite("REQUEST_STATUS")) }, Modifier.weight(1f)) { Text("Write status") }
        }
        result?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        Text("State estimation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Current: ${restState.state.name.replace('_', ' ')}\n" +
                "Still duration: ${restState.stillDurationMillis / 1_000}s\n" +
                "HR samples: ${restState.heartRateSampleCount}\n" +
                "Recent HR average: ${restState.recentAverageHeartRate?.let { String.format(Locale.US, "%.1f BPM", it) } ?: "—"}\n" +
                "HR variation (σ): ${restState.heartRateVariation?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Cloud diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Status: ${cloudSyncState.status}\nUploaded: ${cloudSyncState.uploadedCount}\nPending: ${cloudSyncState.pendingCount}\nSession: ${cloudSyncState.sessionId ?: "not created"}\nLast error: ${cloudSyncState.lastErrorMessage ?: "none"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("BLE terminal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .55f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                (logs.takeLast(18).ifEmpty { listOf("No BLE events") }).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val analysisHint = "Raw XYZ remains available through SensorViewModel; it is intentionally omitted from the primary product UI."
        Text(analysisHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Box(Modifier.size(10.dp).background(color, CircleShape))
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

private fun operationText(result: BleOperationResult): String = when (result) {
    is BleOperationResult.Success -> result.message
    is BleOperationResult.Error -> "Error: ${result.message}"
}

private fun sensorDescription(state: SensorUiState): String = when (state) {
    SensorUiState.Initializing -> "Starting movement monitoring"
    SensorUiState.Unavailable -> "Accelerometer unavailable"
    is SensorUiState.Error -> state.message
    is SensorUiState.Active -> "${if (state.analysis.state == MovementState.MOVEMENT) "MOVEMENT" else "STILL"} · ${format(state.analysis.motionIntensity)} m/s²"
}

private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
