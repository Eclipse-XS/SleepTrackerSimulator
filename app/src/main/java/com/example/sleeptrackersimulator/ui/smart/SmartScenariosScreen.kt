package com.example.sleeptrackersimulator.ui.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleeptrackersimulator.actuator.ActuatorUiState
import com.example.sleeptrackersimulator.smart.RuleAction
import com.example.sleeptrackersimulator.smart.RuleOperator
import com.example.sleeptrackersimulator.smart.RuleSource
import com.example.sleeptrackersimulator.smart.SmartRule
import com.example.sleeptrackersimulator.smart.SmartScenariosUiState
import com.example.sleeptrackersimulator.ui.components.ConfirmationDialog
import com.example.sleeptrackersimulator.ui.components.StatusBadge
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.HeartCoral
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartScenariosScreen(
    state: SmartScenariosUiState,
    onSave: (SmartRule) -> Unit,
    onEnabled: (SmartRule, Boolean) -> Unit,
    onDelete: (SmartRule) -> Unit,
    onBack: (() -> Unit)? = null,
    actuatorState: ActuatorUiState? = null,
    onEmergencyOff: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var editingRule by remember { mutableStateOf<SmartRule?>(null) }
    var creatingRule by remember { mutableStateOf(false) }
    var rulePendingDelete by remember { mutableStateOf<SmartRule?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Automations & Rules",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Local rule engine · Accelerometer & ML triggers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { creatingRule = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Rule", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Safety Banner
            SafetyGuardBanner(
                actuatorState = actuatorState,
                onEmergencyOff = onEmergencyOff,
            )

            // Error notice if present
            state.lastError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Rules List or Rich Empty State
            if (state.rules.isEmpty()) {
                SmartScenariosEmptyState(
                    onCreateRule = { creatingRule = true },
                    onSelectTemplate = { template ->
                        onSave(template)
                    },
                )
            } else {
                Text(
                    text = "ACTIVE RULES (${state.rules.size})",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.rules.forEach { rule ->
                    val lastExecution = state.recentExecutions.firstOrNull { it.ruleId == rule.id }
                    RuleItemCard(
                        rule = rule,
                        lastExecution = lastExecution,
                        onEnabledChanged = { enabled -> onEnabled(rule, enabled) },
                        onEdit = { editingRule = rule },
                        onDelete = { rulePendingDelete = rule },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Rule Creation / Editing Dialog
    if (creatingRule || editingRule != null) {
        RuleEditorDialog(
            initial = editingRule,
            onDismiss = {
                creatingRule = false
                editingRule = null
            },
            onSave = { rule ->
                onSave(rule)
                creatingRule = false
                editingRule = null
            },
        )
    }

    // Delete Confirmation Dialog
    rulePendingDelete?.let { rule ->
        ConfirmationDialog(
            title = "Delete Automation Rule?",
            message = "Are you sure you want to delete \"${rule.name}\"? This local scenario will be permanently removed.",
            confirmButtonText = "Delete Rule",
            dismissButtonText = "Cancel",
            isDestructive = true,
            onConfirm = {
                onDelete(rule)
                rulePendingDelete = null
            },
            onDismiss = {
                rulePendingDelete = null
            },
        )
    }
}

@Composable
private fun SafetyGuardBanner(
    actuatorState: ActuatorUiState?,
    onEmergencyOff: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = CalmGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hardware Safety Guard",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                StatusBadge(text = "ARMED", color = CalmGreen)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Rules run locally on-device. Vibration pulses are strictly limited to max 15 seconds with automatic safety cooldown to protect device actuators and prevent sleep disruption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (actuatorState?.vibrationEnabled == true || onEmergencyOff != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (actuatorState?.vibrationEnabled == true) {
                        StatusBadge(
                            text = "Vibration Active",
                            color = MotionAmber,
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (onEmergencyOff != null) {
                        OutlinedButton(
                            onClick = onEmergencyOff,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = HeartCoral,
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HeartCoral.copy(alpha = 0.5f)),
                        ) {
                            Text("Emergency Stop", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartScenariosEmptyState(
    onCreateRule: () -> Unit,
    onSelectTemplate: (SmartRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Automate Your Sleep Environment",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Define proactive rules that evaluate continuous phone motion or on-device TFLite predictions to trigger gentle haptic pulses or wearable notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onCreateRule,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create Your First Rule", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Quick-start templates
        Text(
            text = "RECOMMENDED TEMPLATES",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start),
        )

        Spacer(modifier = Modifier.height(10.dp))

        val now = remember { System.currentTimeMillis() }

        TemplateCard(
            title = "Restless Motion Vibration Pulse",
            description = "Triggers a single haptic feedback pulse when the on-device ML model classifies activity as RESTLESS.",
            icon = Icons.Rounded.Vibration,
            badge = "ML Trigger",
            onClick = {
                onSelectTemplate(
                    SmartRule(
                        id = UUID.randomUUID().toString(),
                        name = "Restless Motion Haptic",
                        enabled = true,
                        source = RuleSource.ML_CLASS,
                        operator = RuleOperator.EQUALS,
                        threshold = null,
                        targetClass = "RESTLESS",
                        action = RuleAction.VIBRATION_PULSE,
                        cooldownMillis = 10_000L,
                        requiredConsecutiveMatches = 2,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.height(10.dp))

        TemplateCard(
            title = "High Movement Alert",
            description = "Triggers when raw phone accelerometer intensity exceeds 12.0 m/s² for 3 consecutive samples.",
            icon = Icons.Rounded.Bolt,
            badge = "Sensor Trigger",
            onClick = {
                onSelectTemplate(
                    SmartRule(
                        id = UUID.randomUUID().toString(),
                        name = "High Movement Guard",
                        enabled = true,
                        source = RuleSource.MOVEMENT_INTENSITY,
                        operator = RuleOperator.GREATER_THAN,
                        threshold = 12.0f,
                        targetClass = null,
                        action = RuleAction.VIBRATION_PULSE,
                        cooldownMillis = 5_000L,
                        requiredConsecutiveMatches = 3,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.height(10.dp))

        TemplateCard(
            title = "Waking Notification to Band",
            description = "Sends a mock BLE wearable alert via GATT 0x180D when the user enters the ACTIVE waking state.",
            icon = Icons.Rounded.Watch,
            badge = "Wearable Action",
            onClick = {
                onSelectTemplate(
                    SmartRule(
                        id = UUID.randomUUID().toString(),
                        name = "Active Waking Wearable Alert",
                        enabled = true,
                        source = RuleSource.ML_CLASS,
                        operator = RuleOperator.EQUALS,
                        threshold = null,
                        targetClass = "ACTIVE",
                        action = RuleAction.MOCK_BLE_ALERT,
                        cooldownMillis = 15_000L,
                        requiredConsecutiveMatches = 3,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            },
        )
    }
}

@Composable
private fun TemplateCard(
    title: String,
    description: String,
    icon: ImageVector,
    badge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    StatusBadge(text = badge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuleItemCard(
    rule: SmartRule,
    lastExecution: com.example.sleeptrackersimulator.smart.SmartRuleExecutionEntity?,
    onEnabledChanged: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header with Rule Name and Enable Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = formatHumanCondition(rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onEnabledChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            // Badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionBadge(action = rule.action)

                StatusBadge(
                    text = "${rule.requiredConsecutiveMatches} matches",
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                StatusBadge(
                    text = "${rule.cooldownMillis / 1000}s cooldown",
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            // Execution status row if available
            if (lastExecution != null) {
                val formattedTime = remember(lastExecution.timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastExecution.timestamp))
                }
                val isSuccess = lastExecution.result.contains("SUCCESS", ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (isSuccess) CalmGreen else HeartCoral,
                                    CircleShape,
                                ),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Last fired at $formattedTime · ${lastExecution.result}${lastExecution.failureReason?.let { " ($it)" }.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.width(8.dp))

                TextButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ActionBadge(action: RuleAction) {
    val (label, icon, color) = when (action) {
        RuleAction.VIBRATION_PULSE -> Triple("Haptic Pulse", Icons.Rounded.Vibration, MoonBlue)
        RuleAction.MOCK_BLE_ALERT -> Triple("BLE Wearable", Icons.Rounded.Watch, CalmGreen)
        RuleAction.VISIBLE_ALERT -> Triple("Screen Dialog", Icons.Rounded.Notifications, MotionAmber)
        RuleAction.CRITICAL_ALERT -> Triple("Critical Alert", Icons.Rounded.Warning, HeartCoral)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatHumanCondition(rule: SmartRule): String {
    return if (rule.source == RuleSource.ML_CLASS) {
        "When ML predicts ${rule.targetClass}"
    } else {
        val op = when (rule.operator) {
            RuleOperator.GREATER_THAN -> ">"
            RuleOperator.GREATER_OR_EQUAL -> "≥"
            RuleOperator.LESS_THAN -> "<"
            RuleOperator.LESS_OR_EQUAL -> "≤"
            RuleOperator.EQUALS -> "="
        }
        val sourceLabel = when (rule.source) {
            RuleSource.MOVEMENT_INTENSITY -> "Movement Intensity"
            RuleSource.ACCELEROMETER_X -> "Accelerometer X"
            RuleSource.ACCELEROMETER_Y -> "Accelerometer Y"
            RuleSource.ACCELEROMETER_Z -> "Accelerometer Z"
            RuleSource.ML_CLASS -> "ML Class"
        }
        "When $sourceLabel $op ${rule.threshold} m/s²"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorDialog(
    initial: SmartRule?,
    onDismiss: () -> Unit,
    onSave: (SmartRule) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var isMlSource by remember(initial) { mutableStateOf(initial?.source == RuleSource.ML_CLASS) }
    var selectedSensorSource by remember(initial) {
        mutableStateOf(
            if (initial?.source != RuleSource.ML_CLASS && initial?.source != null) initial.source
            else RuleSource.MOVEMENT_INTENSITY,
        )
    }
    var targetClass by remember(initial) { mutableStateOf(initial?.targetClass ?: "RESTLESS") }
    var operator by remember(initial) {
        mutableStateOf(
            if (initial?.operator != RuleOperator.EQUALS && initial?.operator != null) initial.operator
            else RuleOperator.GREATER_THAN,
        )
    }
    var thresholdText by remember(initial) {
        mutableStateOf(initial?.threshold?.toString() ?: "10.0")
    }
    var action by remember(initial) { mutableStateOf(initial?.action ?: RuleAction.VIBRATION_PULSE) }
    var consecutiveText by remember(initial) {
        mutableStateOf(initial?.requiredConsecutiveMatches?.toString() ?: "3")
    }
    var cooldownSecondsText by remember(initial) {
        mutableStateOf(((initial?.cooldownMillis ?: 5000L) / 1000L).toString())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial == null) "Create Automation Rule" else "Edit Automation Rule",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text("Rule Name") },
                    placeholder = { Text("e.g. Restless Motion Vibration") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    singleLine = true,
                )

                // Trigger Source Category
                Text(
                    text = "TRIGGER SOURCE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = isMlSource,
                        onClick = {
                            isMlSource = true
                            validationError = null
                        },
                        label = { Text("ML State") },
                        leadingIcon = if (isMlSource) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = !isMlSource,
                        onClick = {
                            isMlSource = false
                            validationError = null
                        },
                        label = { Text("Accelerometer") },
                        leadingIcon = if (!isMlSource) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Detailed Source Options
                if (isMlSource) {
                    Text(
                        text = "Target Predicted Class",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("REST", "RESTLESS", "ACTIVE").forEach { cls ->
                            FilterChip(
                                selected = targetClass == cls,
                                onClick = { targetClass = cls },
                                label = { Text(cls) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Sensor Axis / Metric",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            RuleSource.MOVEMENT_INTENSITY to "Intensity",
                            RuleSource.ACCELEROMETER_X to "X Axis",
                            RuleSource.ACCELEROMETER_Y to "Y Axis",
                            RuleSource.ACCELEROMETER_Z to "Z Axis",
                        ).forEach { (src, label) ->
                            FilterChip(
                                selected = selectedSensorSource == src,
                                onClick = { selectedSensorSource = src },
                                label = { Text(label) },
                            )
                        }
                    }

                    // Operator & Threshold
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Operator",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    RuleOperator.GREATER_THAN to ">",
                                    RuleOperator.GREATER_OR_EQUAL to "≥",
                                    RuleOperator.LESS_THAN to "<",
                                    RuleOperator.LESS_OR_EQUAL to "≤",
                                ).forEach { (op, sym) ->
                                    FilterChip(
                                        selected = operator == op,
                                        onClick = { operator = op },
                                        label = { Text(sym) },
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = {
                            thresholdText = it
                            validationError = null
                        },
                        label = { Text("Threshold (m/s²)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }

                // Action
                Text(
                    text = "ACTION TO PERFORM",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        RuleAction.VIBRATION_PULSE to "Phone Haptic Pulse",
                        RuleAction.MOCK_BLE_ALERT to "Wearable BLE Alert",
                        RuleAction.VISIBLE_ALERT to "In-App Dialog",
                        RuleAction.CRITICAL_ALERT to "Critical Alert",
                    ).forEach { (act, label) ->
                        FilterChip(
                            selected = action == act,
                            onClick = { action = act },
                            label = { Text(label) },
                        )
                    }
                }

                // Debounce & Cooldown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = consecutiveText,
                        onValueChange = {
                            consecutiveText = it
                            validationError = null
                        },
                        label = { Text("Consecutive Matches") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = cooldownSecondsText,
                        onValueChange = {
                            cooldownSecondsText = it
                            validationError = null
                        },
                        label = { Text("Cooldown (sec)") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                // Error message display
                validationError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isEmpty()) {
                        validationError = "Rule name cannot be empty"
                        return@Button
                    }
                    val consecutive = consecutiveText.toIntOrNull()
                    if (consecutive == null || consecutive !in 1..100) {
                        validationError = "Consecutive matches must be between 1 and 100"
                        return@Button
                    }
                    val cooldownSec = cooldownSecondsText.toLongOrNull()
                    if (cooldownSec == null || cooldownSec !in 0..3600) {
                        validationError = "Cooldown must be between 0 and 3600 seconds"
                        return@Button
                    }
                    val cooldownMs = cooldownSec * 1000L

                    val thresholdVal = if (!isMlSource) {
                        val parsed = thresholdText.toFloatOrNull()
                        if (parsed == null || !parsed.isFinite()) {
                            validationError = "Threshold must be a valid number"
                            return@Button
                        }
                        parsed
                    } else null

                    val now = System.currentTimeMillis()
                    val rule = SmartRule(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = trimmedName,
                        enabled = initial?.enabled ?: true,
                        source = if (isMlSource) RuleSource.ML_CLASS else selectedSensorSource,
                        operator = if (isMlSource) RuleOperator.EQUALS else operator,
                        threshold = thresholdVal,
                        targetClass = if (isMlSource) targetClass else null,
                        action = action,
                        cooldownMillis = cooldownMs,
                        requiredConsecutiveMatches = consecutive,
                        createdAt = initial?.createdAt ?: now,
                        updatedAt = now,
                    )

                    runCatching { rule.validate() }
                        .onSuccess { onSave(rule) }
                        .onFailure { validationError = it.message ?: "Invalid rule configuration" }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Save Rule", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
