package com.example.sleeptrackersimulator.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.sleeptrackersimulator.core.model.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleeptrackersimulator.ui.components.EmptyStateCard
import com.example.sleeptrackersimulator.ui.components.MetricCard
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber

@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onSelectMode: (InsightsMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Personal Insights",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Full retained history · ${state.summaries.size} completed session(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedMode == InsightsMode.REAL,
                    onClick = { onSelectMode(InsightsMode.REAL) },
                    label = { Text("Real") },
                )
                FilterChip(
                    selected = state.selectedMode == InsightsMode.DEMO,
                    onClick = { onSelectMode(InsightsMode.DEMO) },
                    label = { Text("Demo (${state.demoMetrics.sessionCount})") },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            state.errorMessage?.let { message ->
                EmptyStateCard(
                    title = "Insights unavailable",
                    message = message,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.selectedMetrics.sessionCount == 0) {
                EmptyStateCard(
                    title = "No ${state.selectedMode.name.lowercase()} analytics",
                    message = "Import demo history or complete a recording to populate descriptive metrics.",
                )
            } else {
                val metrics = state.selectedMetrics
                val hours = metrics.averageDurationMs / 3_600_000L
                val minutes = (metrics.averageDurationMs % 3_600_000L) / 60_000L

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        title = "Avg Duration",
                        value = "${hours}h ${minutes}m",
                        subtitle = "${metrics.sessionCount} session(s)",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricCard(
                        title = "Avg Low-Motion",
                        value = "${(metrics.averageLowMotionFraction * 100).toInt()}%",
                        subtitle = "Descriptive only",
                        accentColor = CalmGreen,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        title = "Movement",
                        value = String.format("%.1f", metrics.averageMovementEpisodes),
                        subtitle = "episodes/night",
                        accentColor = MotionAmber,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricCard(
                        title = "Coverage",
                        value = "${(metrics.averageCoverageFraction * 100).toInt()}%",
                        subtitle = metrics.averageHeartRateBpm?.let { "Avg BPM ${it.toInt()}" } ?: "No BPM",
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Labeled Trend Chart Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Nightly low-motion trend",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Target: 70-90%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Y: low-motion fraction (%) · X: night (date) · line: 7-night moving average",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val points = metrics.nightlyTrend
                        val chartSemanticsDescription = if (points.isEmpty()) {
                            "Nightly trend chart: no data points"
                        } else {
                            val minVal = (points.minOf { it.lowMotionFraction } * 100).toInt()
                            val maxVal = (points.maxOf { it.lowMotionFraction } * 100).toInt()
                            "Nightly low-motion trend chart across ${points.size} nights, ranging from $minVal% to $maxVal%."
                        }

                        if (points.isEmpty()) {
                            Text(
                                text = "No full-night recordings (≥30 min, ≥85% coverage) to plot yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LowMotionTrendChart(points = points, description = chartSemanticsDescription)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Shaded band: 70–90% reference range (descriptive, not a clinical threshold). ${metrics.trendEligibleCount} eligible; ${metrics.trendExcludedCount} excluded (<30 min or <85% coverage).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state.selectedMode == InsightsMode.REAL && !state.isQualityGateSatisfied) {
                // Quality Gate Notice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MotionAmber,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Personal baseline in progress",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "You currently have ${state.comparableSessionCount} of 3 required comparable session(s). A reliable personal baseline requires at least 3-5 consistent observations with compatible device placement and high data coverage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (state.selectedMode == InsightsMode.REAL) {
                // Baseline Comparison Card
                val comp = state.latestComparison
                if (comp != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Compared with Baseline",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                                    contentDescription = null,
                                    tint = CalmGreen,
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            val diffStr = if (comp.differencePercentagePoints >= 0) "+${String.format("%.1f", comp.differencePercentagePoints)} pp"
                            else "${String.format("%.1f", comp.differencePercentagePoints)} pp"

                            Text(
                                text = "Current low-motion: ${(comp.currentLowMotionFraction * 100).toInt()}% ($diffStr)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (comp.isWithinNormalVariation) CalmGreen else MotionAmber,
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Your ${comp.sampleCount}-session personal baseline is ${(comp.baselineLowMotionFraction * 100).toInt()}%. Movement is within your individual normal variation range.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun LowMotionTrendChart(points: List<SessionSummary>, description: String) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val band = CalmGreen.copy(alpha = 0.12f)
    val avgColor = MotionAmber.copy(alpha = 0.9f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = labelColor)
    val fractions = points.map { it.lowMotionFraction.coerceIn(0f, 1f) }
    val average = LowMotionTrend.movingAverage(fractions)
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = description },
    ) {
        val leftPad = 36.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val topPad = 6.dp.toPx()
        val rightPad = 8.dp.toPx()
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - bottomPad - topPad
        fun yOf(v: Float) = topPad + (1f - v) * plotH
        fun xOf(i: Int) = if (points.size == 1) leftPad + plotW / 2 else leftPad + i * plotW / (points.size - 1)

        drawRect(
            color = band,
            topLeft = Offset(leftPad, yOf(LowMotionTrend.TARGET_HIGH)),
            size = Size(plotW, yOf(LowMotionTrend.TARGET_LOW) - yOf(LowMotionTrend.TARGET_HIGH)),
        )
        for (pct in listOf(0, 25, 50, 75, 100)) {
            val y = yOf(pct / 100f)
            drawLine(grid, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
            val layout = measurer.measure("$pct%", labelStyle)
            drawText(
                layout,
                topLeft = Offset(leftPad - layout.size.width - 4.dp.toPx(), (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)),
            )
        }

        val maxLabels = if (size.width > 600.dp.toPx()) 6 else 4
        for (i in LowMotionTrend.labelIndices(points.size, maxLabels)) {
            val text = dateFormat.format(Date(points[i].calculatedAtUtcMillis)) + " · #${i + 1}"
            val layout = measurer.measure(text, labelStyle)
            val x = (xOf(i) - layout.size.width / 2f).coerceIn(leftPad - 4.dp.toPx(), size.width - layout.size.width.toFloat())
            drawText(layout, topLeft = Offset(x, topPad + plotH + 4.dp.toPx()))
        }

        val dense = points.size > 30
        for (i in 0 until points.lastIndex) {
            drawLine(
                color = primary.copy(alpha = if (dense) 0.7f else 1f),
                start = Offset(xOf(i), yOf(fractions[i])),
                end = Offset(xOf(i + 1), yOf(fractions[i + 1])),
                strokeWidth = (if (dense) 1.5.dp else 2.5.dp).toPx(),
            )
        }
        if (!dense) {
            for (i in points.indices) drawCircle(primary, radius = 3.dp.toPx(), center = Offset(xOf(i), yOf(fractions[i])))
        }
        if (points.size >= 3) {
            for (i in 0 until points.lastIndex) {
                drawLine(
                    color = avgColor,
                    start = Offset(xOf(i), yOf(average[i])),
                    end = Offset(xOf(i + 1), yOf(average[i + 1])),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }
        }
    }
}
