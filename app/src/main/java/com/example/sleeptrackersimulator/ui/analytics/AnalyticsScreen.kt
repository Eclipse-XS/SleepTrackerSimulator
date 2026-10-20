package com.example.sleeptrackersimulator.ui.analytics

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.history.SensorRecord
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AnalyticsScreen(state: AnalyticsUiState, recordingError: String?, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
      Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
                Icon(Icons.Rounded.Analytics, null, Modifier.padding(11.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text("Analytics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Rolling one-hour movement window", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SummaryRow(state.recordCount, state.actionCount)
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.errorMessage != null -> StatusCard(state.errorMessage, true)
            state.records.isEmpty() -> EmptyAnalyticsState()
            else -> MovementChart(state.records)
        }
        recordingError?.let { StatusCard("Recording error: $it", true) }
        Text(
            "Local Room data · newest 50 points · X local time · Y movement intensity in m/s²",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
      }
    }
}

@Composable
private fun SummaryRow(recordCount: Int, actionCount: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard("Measurements", recordCount.toString(), Icons.Rounded.QueryStats, MotionAmber, Modifier.weight(1f))
        SummaryCard("Activations", actionCount.toString(), Icons.Rounded.Bolt, CalmGreen, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = accent)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyAnalyticsState() {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.QueryStats, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("No measurements yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Keep the app open with an accelerometer available. A local point is stored about once per second.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StatusCard(message: String, error: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(24.dp), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MovementChart(records: List<SensorRecord>) {
    val orderedRecords = remember(records) { records.sortedWith(compareBy<SensorRecord> { it.timestamp }.thenBy { it.id }) }
    val scale = remember(orderedRecords) { requireNotNull(MovementChartScaleCalculator.calculate(orderedRecords.map(SensorRecord::sensorValue))) }
    val modelProducer = remember { CartesianChartModelProducer() }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeOriginMillis = orderedRecords.first().timestamp
    val xValues = remember(orderedRecords, timeOriginMillis) { orderedRecords.map { (it.timestamp - timeOriginMillis) / 1_000.0 } }
    val rangeProvider = remember(scale, xValues) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = (xValues.maxOrNull() ?: 0.0).coerceAtLeast(1.0), minY = scale.minY, maxY = scale.maxY)
    }
    LaunchedEffect(orderedRecords, scale) {
        modelProducer.runTransaction { lineModel { series(x = xValues, y = orderedRecords.map { it.sensorValue.toDouble() }) } }
    }

    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Movement intensity", fontWeight = FontWeight.SemiBold)
                    Text("Stable scale · 0–${formatAxisValue(scale.maxY)} m/s²", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.background(MotionAmber.copy(alpha = .16f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("${orderedRecords.size} points", style = MaterialTheme.typography.labelMedium, color = MotionAmber)
                }
            }
            Spacer(Modifier.height(14.dp))
            ProvideVicoTheme(rememberM3VicoTheme()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(
                            lineProvider = LineCartesianLayer.LineProvider.series(LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(Fill(MotionAmber)))),
                            rangeProvider = rangeProvider,
                        ),
                        startAxis = VerticalAxis.rememberStart(
                            title = { "m/s²" }, valueFormatter = { _, value, _ -> formatAxisValue(value) },
                            itemPlacer = VerticalAxis.ItemPlacer.step(step = { scale.tickStep }, shiftTopLines = false),
                        ),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = { _, value, _ -> timeFormatter.format(Date(timeOriginMillis + (value * 1_000).toLong())) },
                            itemPlacer = HorizontalAxis.ItemPlacer.extremes(shiftExtremeLines = false),
                        ),
                    ),
                    modelProducer = modelProducer, modifier = Modifier.fillMaxWidth().height(280.dp),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                )
            }
        }
    }
}

internal fun formatAxisValue(value: Double): String = String.format(Locale.US, "%.2f", value)
