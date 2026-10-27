package com.example.sleeptrackersimulator.ui.devices

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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.core.model.DeviceConnectionState
import com.example.sleeptrackersimulator.core.model.DeviceInfo
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.data.ble.BleMode
import com.example.sleeptrackersimulator.ui.components.ProvenanceChip
import com.example.sleeptrackersimulator.ui.components.StatusBadge
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.HeartCoral
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.NightBlue

@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onConnectDevice: (DeviceInfo) -> Unit,
    onDisconnectDevice: () -> Unit,
    onStartScan: () -> Unit,
    onSelectMode: (BleMode) -> Unit,
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
                text = "Connected Sources",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MistBlue,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: This Phone
            Text(
                text = "THIS PHONE",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MistBlue.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = Icons.Rounded.PhoneAndroid, contentDescription = null, tint = CalmGreen)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Android Accelerometer",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MistBlue,
                        )
                        Text(
                            text = "3-axis continuous hardware · 20 Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MistBlue.copy(alpha = 0.65f),
                        )
                    }
                    ProvenanceChip(sourceKind = SourceKind.PHONE_REAL)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Wearables & Virtual Band
            Text(
                text = "BLUETOOTH WEARABLES",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MistBlue.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelectMode(BleMode.REAL) }) { Text(if (state.mode == BleMode.REAL) "REAL BLE ✓" else "REAL BLE") }
                OutlinedButton(onClick = { onSelectMode(BleMode.DEMO) }) { Text(if (state.mode == BleMode.DEMO) "DEMO ✓" else "DEMO") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("State: ${state.connectionState.name}", color = MistBlue)
            state.errorMessage?.let { Text(it, color = HeartCoral, style = MaterialTheme.typography.bodySmall) }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onStartScan, enabled = !state.connectionState.isBusy) { Text("Search for devices") }
            state.discoveredDevices.forEach { device ->
                OutlinedButton(onClick = { onConnectDevice(device) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${device.displayName} · ${device.sourceKind.name} · ${device.rssi ?: 0} dBm")
                }
            }

            val isConnected = state.connectionState == DeviceConnectionState.CONNECTED
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Rounded.Watch, contentDescription = null, tint = MoonBlue)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                text = state.connectedDevice?.displayName ?: "Selected BLE wearable",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MistBlue,
                                )
                                Text(
                                    text = "GATT Service 0x180D · 0x2A37",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MistBlue.copy(alpha = 0.65f),
                                )
                            }
                        }
                        ProvenanceChip(sourceKind = state.connectedDevice?.sourceKind ?: SourceKind.BLE_UNVERIFIED)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isConnected && state.heartRate != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Rounded.Favorite, contentDescription = null, tint = HeartCoral)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${state.heartRate} BPM · ${state.connectedDevice?.sourceKind?.name ?: "UNKNOWN"}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = HeartCoral,
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (isConnected) {
                            OutlinedButton(
                                onClick = onDisconnectDevice,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Disconnect")
                            }
                        } else {
                            Button(
                                onClick = onStartScan,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MoonBlue),
                            ) {
                                Text("Scan", color = NightBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "For multi-device physical testing, use our standalone companion application VirtualSleepBand (package com.example.virtualsleepband) running on a separate phone or emulator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistBlue.copy(alpha = 0.65f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Data Source Rules
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Rounded.Security, contentDescription = null, tint = CalmGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Data Integrity Policy",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MistBlue,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Real phone measurements take priority\n• Simulated signals (like Virtual Band HR) are strictly isolated and never mixed into personal sleep trends or baseline statistics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MistBlue.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
