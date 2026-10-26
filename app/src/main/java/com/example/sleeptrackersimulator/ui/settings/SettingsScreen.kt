package com.example.sleeptrackersimulator.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.NightBlue

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDeveloperLab: () -> Unit,
    onExportData: () -> Unit,
    onDeleteAllData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cloudSyncEnabled by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete All Local Data?") },
            text = { Text("This will permanently remove all recorded sessions, feature windows, and personal baselines from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteAllData()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444)),
                ) {
                    Text("Delete Everything", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = DarkSurfaceRaised,
            titleContentColor = MistBlue,
            textContentColor = MistBlue.copy(alpha = 0.8f),
        )
    }

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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                Text(
                    text = "Settings & Privacy",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MistBlue,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section 1: Privacy & Offline Mode
            Text(
                text = "PRIVACY & SYNC",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MistBlue.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Firebase Cloud Sync (Opt-in)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MistBlue,
                            )
                            Text(
                                text = "Synchronizes only session summaries and rules when enabled. Offline-first by default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MistBlue.copy(alpha = 0.65f),
                            )
                        }
                        Switch(
                            checked = cloudSyncEnabled,
                            onCheckedChange = { cloudSyncEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NightBlue,
                                checkedTrackColor = MoonBlue,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Data Management
            Text(
                text = "DATA MANAGEMENT",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MistBlue.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onExportData)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = Icons.Rounded.Download, contentDescription = null, tint = MoonBlue)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Export Local Data",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MistBlue,
                            )
                            Text(
                                text = "Export sessions and summaries as JSON/CSV",
                                style = MaterialTheme.typography.bodySmall,
                                color = MistBlue.copy(alpha = 0.65f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = { showDeleteDialog = true })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFEF4444))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Delete All Local Data",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFFEF4444),
                            )
                            Text(
                                text = "Permanently remove all local history and baselines",
                                style = MaterialTheme.typography.bodySmall,
                                color = MistBlue.copy(alpha = 0.65f),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Developer & Labs Access
            Text(
                text = "ADVANCED / PORTFOLIO",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MistBlue.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = Icons.Rounded.Build, contentDescription = null, tint = CalmGreen)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Developer Lab (Labs 1–7)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MistBlue,
                            )
                            Text(
                                text = "Sensor live inspector, BLE GATT console, ML latency benchmarks, Actuator controls, and full trace.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MistBlue.copy(alpha = 0.65f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = onOpenDeveloperLab,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CalmGreen),
                    ) {
                        Text(text = "Open Developer Lab", color = NightBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
