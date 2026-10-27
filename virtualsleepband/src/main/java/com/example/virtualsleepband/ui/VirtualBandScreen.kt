package com.example.virtualsleepband.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.virtualsleepband.gatt.BandPeripheralState
import com.example.virtualsleepband.gatt.SimulationScenario

val DarkBg = Color(0xFF0A0E17)
val CardBg = Color(0xFF141C2B)
val CardBorder = Color(0xFF1E293B)
val VioletAccent = Color(0xFF6366F1)
val EmeraldGreen = Color(0xFF10B981)
val CoralRed = Color(0xFFEF4444)
val AmberYellow = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualBandScreen(
    state: BandPeripheralState,
    onToggleAdvertising: () -> Unit,
    onSetBpm: (Int) -> Unit,
    onSetBattery: (Int) -> Unit,
    onSetInterval: (Long) -> Unit,
    onSetScenario: (SimulationScenario) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Virtual SleepBand",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                        )
                        Text(
                            text = "GATT Peripheral Simulator (0x180D & 0x180F)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
            )
        },
        containerColor = DarkBg,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text("Automatic scenario", color = TextPrimary, fontWeight = FontWeight.Bold)
            SimulationScenario.values().forEach { scenario ->
                FilterChip(
                    selected = state.scenario == scenario,
                    onClick = { onSetScenario(scenario) },
                    label = { Text(scenario.label) },
                )
            }
            Text(
                "Elapsed ${state.elapsedSeconds}s · transmitted ${state.transmittedPacketCount} packets · synthetic source",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Advertising Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.isAdvertising) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = if (state.isAdvertising) EmeraldGreen else TextMuted,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Advertising Status",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (state.isAdvertising) EmeraldGreen.copy(alpha = 0.2f) else CardBorder,
                        ) {
                            Text(
                                text = if (state.isAdvertising) "ADVERTISING" else "STOPPED",
                                color = if (state.isAdvertising) EmeraldGreen else TextMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Connected Centrals:", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${state.connectedCentrals} device(s)",
                            color = if (state.connectedCentrals > 0) EmeraldGreen else TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onToggleAdvertising,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isAdvertising) CoralRed else VioletAccent,
                        ),
                    ) {
                        Text(
                            text = if (state.isAdvertising) "STOP BLE ADVERTISING" else "START BLE ADVERTISING",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }

                    if (state.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.errorMessage,
                            color = CoralRed,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Heart Rate Simulation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = CoralRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Heart Rate Sensor (0x180D)", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("Simulated Pulse:", color = TextMuted)
                        Text(
                            text = "${state.currentBpm} BPM",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = CoralRed,
                        )
                    }

                    Text(
                        "Automatic simulated waveform; values are generated independently of user input.",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Packet Transmission Interval:", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(250L, 500L, 1000L, 2000L).forEach { interval ->
                            FilterChip(
                                selected = state.packetIntervalMs == interval,
                                onClick = { onSetInterval(interval) },
                                label = { Text("${interval}ms") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VioletAccent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Battery Service Simulation
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryFull, contentDescription = null, tint = EmeraldGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Battery Service (0x180F)", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text("Charge Level:", color = TextMuted)
                        Text(
                            text = "${state.batteryLevel}%",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldGreen,
                        )
                    }

                    Text(
                        "Simulated battery drain (not a hardware reading).",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Command Log Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = AmberYellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Received Commands Log", fontWeight = FontWeight.Bold, color = TextPrimary)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (state.commandLogs.isEmpty()) {
                        Text(
                            text = "No central writes received yet (awaits 0x2A38 or custom command write)",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        state.commandLogs.take(5).forEach { log ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = CardBorder.copy(alpha = 0.5f),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(log.timestamp, color = AmberYellow, style = MaterialTheme.typography.labelSmall)
                                        Text(log.sourceDevice, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        text = log.command,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Hardware Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "ℹ️ Dual-Role BLE Hardware Note",
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "To test real Bluetooth over-the-air with SleepSense, run this VirtualSleepBand app on a secondary physical phone or tablet while running SleepSense as Central on your primary device.",
                        color = TextMuted.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
