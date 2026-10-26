package com.example.sleeptrackersimulator.ui.today

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleeptrackersimulator.core.model.SessionStatus
import com.example.sleeptrackersimulator.ui.components.MetricCard
import com.example.sleeptrackersimulator.ui.components.ProvenanceChip
import com.example.sleeptrackersimulator.ui.components.StatusBadge
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurface
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import com.example.sleeptrackersimulator.ui.theme.NightBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TodayScreen(
    state: TodayUiState,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAutomations: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onDismissError()
        }
    }

    val todayFormatted = remember {
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = todayFormatted.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MistBlue.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "SleepSense",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MistBlue,
                    )
                }

                Row {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MistBlue.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main Hero Recording Card
            SessionHeroCard(
                state = state,
                onStart = onStartRecording,
                onPause = onPauseRecording,
                onResume = onResumeRecording,
                onStop = onStopRecording,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Last session overview
            Text(
                text = "Last Session Summary",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MistBlue,
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (state.lastSummary != null) {
                val sum = state.lastSummary
                val hours = sum.observationDurationMs / 3_600_000L
                val mins = (sum.observationDurationMs % 3_600_000L) / 60_000L

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        title = "Observation",
                        value = "${hours}h ${mins}m",
                        subtitle = "Data coverage ${(sum.dataCoverageFraction * 100).toInt()}%",
                        icon = Icons.Rounded.NightsStay,
                        accentColor = MoonBlue,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricCard(
                        title = "Low motion",
                        value = "${(sum.lowMotionFraction * 100).toInt()}%",
                        subtitle = "${sum.movementEpisodeCount} episodes observed",
                        icon = Icons.Rounded.CheckCircle,
                        accentColor = CalmGreen,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "No recorded sessions yet",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MistBlue,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Start tonight's session above to observe phone movement and review your activity timeline in the morning.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MistBlue.copy(alpha = 0.65f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Quick shortcut to automations
            OutlinedButton(
                onClick = onOpenAutomations,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MoonBlue),
            ) {
                Text(text = "Smart Automation Rules")
            }
        }
    }
}

@Composable
private fun SessionHeroCard(
    state: TodayUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            when (state.sessionStatus) {
                SessionStatus.IDLE, SessionStatus.COMPLETED, SessionStatus.FAILED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(text = "READY TO RECORD", color = CalmGreen)
                        StatusBadge(text = "LOCAL ONLY", color = MistBlue.copy(alpha = 0.7f))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Tonight's Session",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MistBlue,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Monitor device movement and view your activity timeline afterward.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MistBlue.copy(alpha = 0.75f),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProvenanceChip(sourceKind = com.example.sleeptrackersimulator.core.model.SourceKind.PHONE_REAL)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Phone Accelerometer · Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MistBlue.copy(alpha = 0.7f),
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoonBlue),
                    ) {
                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = DarkSurface)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Recording",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = DarkSurface,
                        )
                    }
                }

                SessionStatus.RECORDING, SessionStatus.PAUSED, SessionStatus.PREPARING -> {
                    val isPaused = state.sessionStatus == SessionStatus.PAUSED

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(
                            text = if (isPaused) "SESSION PAUSED" else "RECORDING IN PROGRESS",
                            color = if (isPaused) MotionAmber else CalmGreen,
                        )
                        ProvenanceChip(sourceKind = com.example.sleeptrackersimulator.core.model.SourceKind.PHONE_REAL)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val hrs = state.activeDurationSeconds / 3600
                    val mins = (state.activeDurationSeconds % 3600) / 60
                    val secs = state.activeDurationSeconds % 60
                    val formattedDuration = String.format("%02d:%02d:%02d", hrs, mins, secs)

                    Text(
                        text = formattedDuration,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                        ),
                        color = MistBlue,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Phone sensor active · 20 Hz · On-device TFLite model",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistBlue.copy(alpha = 0.7f),
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (isPaused) {
                            Button(
                                onClick = onResume,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CalmGreen),
                            ) {
                                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = DarkSurface)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Resume", fontWeight = FontWeight.Bold, color = DarkSurface)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onPause,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(imageVector = Icons.Rounded.Pause, contentDescription = null, tint = MistBlue)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Pause", fontWeight = FontWeight.SemiBold, color = MistBlue)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        ) {
                            Icon(imageVector = Icons.Rounded.Stop, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Finish", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                SessionStatus.FINALIZING, SessionStatus.INTERRUPTED, SessionStatus.RECOVERABLE -> {
                    StatusBadge(text = "FINALIZING", color = MotionAmber)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Committing local summaries...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MistBlue,
                    )
                }
            }
        }
    }
}
