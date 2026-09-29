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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.core.model.MovementAnalysis
import com.example.sleeptrackersimulator.core.model.MovementState
import com.example.sleeptrackersimulator.sensor.MovementClassifier
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import java.util.Locale
import kotlin.math.min

@Composable
fun SensorScreen(
    uiState: SensorUiState,
    bleState: BleConnectionState,
    bleLogs: List<String>,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSendData: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Sleep Tracker Simulator",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Educational movement monitoring — not medical sleep-stage detection",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (uiState) {
                SensorUiState.Initializing -> StatusMessage(
                    title = "Starting accelerometer",
                    message = "Waiting for the first measurement…",
                    showProgress = true,
                )

                SensorUiState.Unavailable -> StatusMessage(
                    title = "Accelerometer unavailable",
                    message = "This device or emulator does not expose an accelerometer sensor.",
                )

                is SensorUiState.Error -> StatusMessage(
                    title = "Sensor error",
                    message = uiState.message,
                )

                is SensorUiState.Active -> ActiveSensorContent(uiState.analysis)
            }

            BleSection(
                bleState = bleState,
                bleLogs = bleLogs,
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onSendData = onSendData,
            )
        }
    }
}

@Composable
private fun BleSection(
    bleState: BleConnectionState,
    bleLogs: List<String>,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSendData: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commandInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Mock BLE connection",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        BleStatusCard(bleState)

        val canScan = bleState !is BleConnectionState.Scanning &&
            bleState !is BleConnectionState.Connecting &&
            bleState !is BleConnectionState.Connected

        Button(
            onClick = onScan,
            enabled = canScan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Search for devices")
        }

        if (bleState is BleConnectionState.Scanning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = "Scanning for virtual devices…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (bleState is BleConnectionState.DeviceFound) {
            Text("Discovered devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            bleState.devices.forEach { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onConnect(device) }) {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        if (bleState is BleConnectionState.Connecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = "Connecting to ${bleState.device.name}…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (bleState is BleConnectionState.Connected) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                label = { Text("Command") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = {
                    val payload = commandInput.trim()
                    if (payload.isNotBlank()) {
                        onSendData(payload)
                        commandInput = ""
                    }
                },
                enabled = commandInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send")
            }
        }

        if (bleState is BleConnectionState.Connecting || bleState is BleConnectionState.Connected) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect")
            }
        }

        BleTerminalCard(bleLogs)
    }
}

@Composable
private fun BleStatusCard(bleState: BleConnectionState) {
    val statusText = when (bleState) {
        BleConnectionState.Idle -> "Disconnected"
        BleConnectionState.Scanning -> "Scanning"
        is BleConnectionState.DeviceFound -> "Device found"
        is BleConnectionState.Connecting -> "Connecting"
        is BleConnectionState.Connected -> "Connected: ${bleState.device.name}"
        is BleConnectionState.Disconnected -> "Disconnected"
        is BleConnectionState.Error -> "Error: ${bleState.message}"
    }

    val isConnected = bleState is BleConnectionState.Connected
    val isScanningOrConnecting = bleState is BleConnectionState.Scanning || bleState is BleConnectionState.Connecting
    val isError = bleState is BleConnectionState.Error

    val indicatorColor = when {
        isConnected -> CalmGreen
        isScanningOrConnecting -> MotionAmber
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Connection status", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(indicatorColor, CircleShape),
            )
        }
    }
}

@Composable
private fun BleTerminalCard(logs: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "BLE terminal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (logs.isEmpty()) {
                Text(
                    text = "No BLE messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logs.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSensorContent(analysis: MovementAnalysis) {
    StateCard(analysis)
    AxisValues(analysis)
    MotionIndicator(analysis)
    Text(
        text = "Movement threshold: ${format(MovementClassifier.DEFAULT_MOVEMENT_THRESHOLD)} m/s² · " +
            "Still below ${format(MovementClassifier.DEFAULT_STILL_THRESHOLD)} m/s²",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StateCard(analysis: MovementAnalysis) {
    val moving = analysis.state == MovementState.MOVEMENT
    val stateColor = if (moving) MotionAmber else CalmGreen
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = stateColor.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Movement state", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (moving) "MOVEMENT" else "STILL",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )
            }
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(stateColor, CircleShape),
            )
        }
    }
}

@Composable
private fun AxisValues(analysis: MovementAnalysis) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AxisCard("X", analysis.sample.x, Modifier.weight(1f))
        AxisCard("Y", analysis.sample.y, Modifier.weight(1f))
        AxisCard("Z", analysis.sample.z, Modifier.weight(1f))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Acceleration magnitude")
            Text("${format(analysis.sample.magnitude)} m/s²", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AxisCard(label: String, value: Float, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(format(value), fontWeight = FontWeight.SemiBold)
            Text("m/s²", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MotionIndicator(analysis: MovementAnalysis) {
    val moving = analysis.state == MovementState.MOVEMENT
    val stateColor = if (moving) MotionAmber else CalmGreen
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val bubbleColor = MaterialTheme.colorScheme.primary
    val intensityFraction = min(
        analysis.motionIntensity / (MovementClassifier.DEFAULT_MOVEMENT_THRESHOLD * 2f),
        1f,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Position and motion indicator", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Canvas(modifier = Modifier.size(190.dp)) {
                val radius = size.minDimension * 0.43f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(trackColor, radius = radius, center = center, style = Stroke(width = 5f))
                drawLine(
                    color = trackColor,
                    start = Offset(center.x - radius, center.y),
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = 2f,
                )
                drawLine(
                    color = trackColor,
                    start = Offset(center.x, center.y - radius),
                    end = Offset(center.x, center.y + radius),
                    strokeWidth = 2f,
                )

                val normalizedX = (analysis.sample.x / 9.81f).coerceIn(-1f, 1f)
                val normalizedY = (analysis.sample.y / 9.81f).coerceIn(-1f, 1f)
                val bubbleCenter = Offset(
                    x = center.x + normalizedX * radius * 0.78f,
                    y = center.y - normalizedY * radius * 0.78f,
                )
                drawCircle(bubbleColor.copy(alpha = 0.20f), radius = 24f, center = bubbleCenter)
                drawCircle(bubbleColor, radius = 13f, center = bubbleCenter)

                drawArc(
                    color = stateColor,
                    startAngle = -90f,
                    sweepAngle = intensityFraction * 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius - 10f, center.y - radius - 10f),
                    size = Size((radius + 10f) * 2f, (radius + 10f) * 2f),
                    style = Stroke(width = 10f, cap = StrokeCap.Round),
                )
            }
            Text(
                text = "Filtered motion: ${format(analysis.motionIntensity)} m/s²",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusMessage(
    title: String,
    message: String,
    showProgress: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showProgress) {
                CircularProgressIndicator()
                Spacer(Modifier.height(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
