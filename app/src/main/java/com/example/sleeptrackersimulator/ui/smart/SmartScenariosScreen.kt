package com.example.sleeptrackersimulator.ui.smart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sleeptrackersimulator.smart.RuleAction
import com.example.sleeptrackersimulator.smart.RuleOperator
import com.example.sleeptrackersimulator.smart.RuleSource
import com.example.sleeptrackersimulator.smart.SmartRule
import com.example.sleeptrackersimulator.smart.SmartScenariosUiState
import java.util.UUID

@Composable
fun SmartScenariosScreen(
    state: SmartScenariosUiState,
    onSave: (SmartRule) -> Unit,
    onEnabled: (SmartRule, Boolean) -> Unit,
    onDelete: (SmartRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<SmartRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Smart Scenarios")
        Text("Persistent rules evaluate real accelerometer and TFLite state.")
        Button(onClick = { creating = true }) { Text("Add rule") }
        state.lastError?.let { Text("Error: $it") }
        state.rules.forEach { rule ->
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name)
                            Text(ruleDescription(rule))
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { onEnabled(rule, it) })
                    }
                    state.recentExecutions.firstOrNull { it.ruleId == rule.id }?.let {
                        Text("Last: ${it.result}${it.failureReason?.let { reason -> " · $reason" }.orEmpty()}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = rule }) { Text("Edit") }
                        TextButton(onClick = { onDelete(rule) }) { Text("Delete") }
                    }
                }
            }
        }
        if (state.rules.isEmpty()) Text("No saved rules")
    }
    if (creating || editing != null) RuleEditor(
        initial = editing,
        onDismiss = { creating = false; editing = null },
        onSave = { onSave(it); creating = false; editing = null },
    )
}

@Composable
private fun RuleEditor(initial: SmartRule?, onDismiss: () -> Unit, onSave: (SmartRule) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "X threshold alert") }
    var source by remember(initial) { mutableStateOf(initial?.source ?: RuleSource.ACCELEROMETER_X) }
    var operator by remember(initial) { mutableStateOf(initial?.operator ?: RuleOperator.GREATER_THAN) }
    var threshold by remember(initial) { mutableStateOf(initial?.threshold?.toString() ?: "10") }
    var targetClass by remember(initial) { mutableStateOf(initial?.targetClass ?: "ACTIVE") }
    var action by remember(initial) { mutableStateOf(initial?.action ?: RuleAction.VIBRATION_PULSE) }
    var cooldown by remember(initial) { mutableStateOf(initial?.cooldownMillis?.toString() ?: "5000") }
    var consecutive by remember(initial) { mutableStateOf(initial?.requiredConsecutiveMatches?.toString() ?: "3") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Smart Scenario" else "Edit Smart Scenario") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                CycleButton("Source: ${source.name}") { source = RuleSource.entries[(source.ordinal + 1) % RuleSource.entries.size] }
                if (source == RuleSource.ML_CLASS) {
                    operator = RuleOperator.EQUALS
                    CycleButton("Class: $targetClass") {
                        val classes = listOf("REST", "RESTLESS", "ACTIVE")
                        targetClass = classes[(classes.indexOf(targetClass) + 1) % classes.size]
                    }
                } else {
                    if (operator == RuleOperator.EQUALS) operator = RuleOperator.GREATER_THAN
                    CycleButton("Operator: ${operatorLabel(operator)}") {
                        val numeric = RuleOperator.entries.filter { it != RuleOperator.EQUALS }
                        operator = numeric[(numeric.indexOf(operator) + 1) % numeric.size]
                    }
                    OutlinedTextField(threshold, { threshold = it }, label = { Text("Threshold m/s²") })
                }
                CycleButton("Action: ${action.name}") { action = RuleAction.entries[(action.ordinal + 1) % RuleAction.entries.size] }
                OutlinedTextField(consecutive, { consecutive = it }, label = { Text("Consecutive matches") })
                OutlinedTextField(cooldown, { cooldown = it }, label = { Text("Cooldown ms") })
                error?.let { Text(it) }
            }
        },
        confirmButton = { Button(onClick = {
            val now = System.currentTimeMillis()
            val rule = SmartRule(
                id = initial?.id ?: UUID.randomUUID().toString(), name = name.trim(), enabled = initial?.enabled ?: true,
                source = source, operator = if (source == RuleSource.ML_CLASS) RuleOperator.EQUALS else operator,
                threshold = if (source == RuleSource.ML_CLASS) null else threshold.toFloatOrNull(),
                targetClass = if (source == RuleSource.ML_CLASS) targetClass else null, action = action,
                cooldownMillis = cooldown.toLongOrNull() ?: -1, requiredConsecutiveMatches = consecutive.toIntOrNull() ?: -1,
                createdAt = initial?.createdAt ?: now, updatedAt = now,
            )
            runCatching { rule.validate() }.onSuccess { onSave(rule) }.onFailure { error = it.message ?: "Invalid rule" }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable private fun CycleButton(text: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
private fun operatorLabel(value: RuleOperator) = when (value) {
    RuleOperator.GREATER_THAN -> ">"; RuleOperator.GREATER_OR_EQUAL -> ">="
    RuleOperator.LESS_THAN -> "<"; RuleOperator.LESS_OR_EQUAL -> "<="; RuleOperator.EQUALS -> "="
}
private fun ruleDescription(rule: SmartRule): String = if (rule.source == RuleSource.ML_CLASS) {
    "${rule.source.name} = ${rule.targetClass} → ${rule.action.name}"
} else "${rule.source.name} ${operatorLabel(rule.operator)} ${rule.threshold} m/s² → ${rule.action.name}"
