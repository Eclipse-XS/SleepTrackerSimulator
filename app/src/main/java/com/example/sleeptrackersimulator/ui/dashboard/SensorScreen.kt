package com.example.sleeptrackersimulator.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
