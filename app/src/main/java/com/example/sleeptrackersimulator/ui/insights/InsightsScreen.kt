package com.example.sleeptrackersimulator.ui.insights

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.ui.components.EmptyStateCard
import com.example.sleeptrackersimulator.ui.components.MetricCard
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import com.example.sleeptrackersimulator.ui.theme.NightBlue

@Composable
fun InsightsScreen(
    state: InsightsUiState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        containerColor = NightBlue,
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
                color = MistBlue,
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Full retained history · ${state.summaries.size} completed session(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MistBlue.copy(alpha = 0.7f),
            )

            Spacer(modifier = Modifier.height(16.dp))

            state.errorMessage?.let { message ->
                EmptyStateCard(
                    title = "Insights unavailable",
                    message = message,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (!state.isQualityGateSatisfied) {
                // Quality Gate Notice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Rounded.Info, contentDescription = null, tint = MotionAmber)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Personal baseline in progress",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MistBlue,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "You currently have ${state.comparableSessionCount} comparable session(s). A reliable personal baseline requires at least 3-5 consistent observations with compatible device placement and high data coverage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MistBlue.copy(alpha = 0.75f),
                        )
                    }
                }
            } else {
                // Baseline Comparison Card
                val comp = state.latestComparison
                if (comp != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
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
                                    color = MistBlue,
                                )
                                Icon(imageVector = Icons.Rounded.TrendingUp, contentDescription = null, tint = CalmGreen)
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
                                color = MistBlue.copy(alpha = 0.75f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Summary KPIs
                val medianHrs = state.medianDurationMs / 3_600_000L
                val medianMins = (state.medianDurationMs % 3_600_000L) / 60_000L

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        title = "Avg Low-Motion",
                        value = "${(state.averageLowMotionFraction * 100).toInt()}%",
                        subtitle = "Across ${state.comparableSessionCount} sessions",
                        accentColor = CalmGreen,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricCard(
                        title = "Median Duration",
                        value = "${medianHrs}h ${medianMins}m",
                        subtitle = "Valid observations",
                        accentColor = MoonBlue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
