package com.example.sleeptrackersimulator.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.RecordingSession
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.core.model.SourceMode
import com.example.sleeptrackersimulator.ui.components.ConfirmationDialog
import com.example.sleeptrackersimulator.ui.components.EmptyStateCard
import com.example.sleeptrackersimulator.ui.components.HorizontalActivityTimeline
import com.example.sleeptrackersimulator.ui.components.MetricCard
import com.example.sleeptrackersimulator.ui.components.ProvenanceChip
import com.example.sleeptrackersimulator.ui.components.StatusBadge
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onSelectFilter: (HistoryFilter) -> Unit,
    onOpenSession: (String) -> Unit,
    onCloseDetails: () -> Unit,
    onDeleteSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.selectedSessionDetails != null) {
        SessionDetailsView(
            details = state.selectedSessionDetails,
            onBack = onCloseDetails,
            onDelete = { onDeleteSession(state.selectedSessionDetails.session.sessionId) },
            modifier = modifier,
        )
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            modifier = modifier.fillMaxSize(),
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Session History",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Filter row
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = state.selectedFilter == HistoryFilter.ALL,
                        onClick = { onSelectFilter(HistoryFilter.ALL) },
                        label = { Text("All (${state.sessions.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = state.selectedFilter == HistoryFilter.REAL_ONLY,
                        onClick = { onSelectFilter(HistoryFilter.REAL_ONLY) },
                        label = { Text("Real Only") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = state.selectedFilter == HistoryFilter.DEMO_ONLY,
                        onClick = { onSelectFilter(HistoryFilter.DEMO_ONLY) },
                        label = { Text("Demo") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (state.sessions.isEmpty()) {
                    EmptyStateCard(
                        title = "No sessions found",
                        message = "Sessions will appear here once you record and finish an observation session.",
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.sessions, key = { it.sessionId }) { session ->
                            SessionListItem(
                                session = session,
                                sourceKind = state.provenanceBySessionId[session.sessionId] ?: fallbackProvenance(session.sourceMode),
                                onClick = { onOpenSession(session.sessionId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: RecordingSession,
    sourceKind: SourceKind,
    onClick: () -> Unit,
) {
    val dateFormatted = remember(session.startUtcMillis) {
        SimpleDateFormat("EEE, d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(session.startUtcMillis))
    }

    val durationFormatted = remember(session.durationMillis) {
        val hrs = session.durationMillis / 3_600_000L
        val mins = (session.durationMillis % 3_600_000L) / 60_000L
        if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    text = dateFormatted,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ProvenanceChip(sourceKind = sourceKind)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Duration: $durationFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Coverage: ${(session.dataCoverageFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = CalmGreen,
                )
            }
        }
    }
}

@Composable
fun SessionDetailsView(
    details: SessionDetailsUiState,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val session = details.session
    val summary = details.summary

    val dateFormatted = remember(session.startUtcMillis) {
        SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(session.startUtcMillis))
    }

    if (showDeleteConfirmation) {
        ConfirmationDialog(
            title = "Delete Recorded Session?",
            message = "Are you sure you want to delete the session from $dateFormatted? This observation and its feature windows will be permanently removed.",
            confirmButtonText = "Delete Session",
            dismissButtonText = "Cancel",
            isDestructive = true,
            onConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
            onDismiss = {
                showDeleteConfirmation = false
            },
        )
    }

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
            // Header with Back and Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to History",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "Session Details",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { showDeleteConfirmation = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete Session",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = dateFormatted,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = session.status.name, color = CalmGreen)
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(text = session.devicePlacement.label, color = MoonBlue)
                Spacer(modifier = Modifier.width(8.dp))
                ProvenanceChip(sourceKind = summary?.sourceKind ?: fallbackProvenance(session.sourceMode))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Metrics row
            if (summary != null) {
                val hrs = summary.observationDurationMs / 3_600_000L
                val mins = (summary.observationDurationMs % 3_600_000L) / 60_000L

                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        title = "Observation",
                        value = "${hrs}h ${mins}m",
                        subtitle = "Valid: ${(summary.dataCoverageFraction * 100).toInt()}%",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricCard(
                        title = "Low motion",
                        value = "${(summary.lowMotionFraction * 100).toInt()}%",
                        subtitle = "${summary.movementEpisodeCount} episodes",
                        accentColor = CalmGreen,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Movement Activity Timeline",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(10.dp))

            val timelineClasses = details.predictions.map { it.predictedClass }
            HorizontalActivityTimeline(
                classes = timelineClasses,
                modifier = Modifier.fillMaxWidth(),
            )

            if (timelineClasses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val restPct = (timelineClasses.count { it == ActivityClass.REST } * 100) / timelineClasses.size
                val restlessPct = (timelineClasses.count { it == ActivityClass.RESTLESS } * 100) / timelineClasses.size
                val activePct = (timelineClasses.count { it == ActivityClass.ACTIVE } * 100) / timelineClasses.size

                Text(
                    text = "Breakdown: $restPct% Low motion · $restlessPct% Restless · $activePct% Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Scientific disclaimer
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Data Interpretation Notice",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "These metrics describe observed device movement and rest patterns from your phone accelerometer. They do not constitute medically verified sleep stages or clinical polysomnography.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

internal fun fallbackProvenance(sourceMode: SourceMode): SourceKind = when (sourceMode) {
    SourceMode.PHONE_ONLY -> SourceKind.PHONE_REAL
    SourceMode.WEARABLE_ONLY -> SourceKind.BLE_UNVERIFIED
    SourceMode.COMBINED -> SourceKind.BLE_UNVERIFIED
    SourceMode.DEMO -> SourceKind.SIMULATED
    SourceMode.REPLAY -> SourceKind.REPLAY
}
