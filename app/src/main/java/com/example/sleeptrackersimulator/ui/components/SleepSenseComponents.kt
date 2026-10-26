package com.example.sleeptrackersimulator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleeptrackersimulator.core.model.ActivityClass
import com.example.sleeptrackersimulator.core.model.SourceKind
import com.example.sleeptrackersimulator.ui.theme.CalmGreen
import com.example.sleeptrackersimulator.ui.theme.DarkSurface
import com.example.sleeptrackersimulator.ui.theme.DarkSurfaceRaised
import com.example.sleeptrackersimulator.ui.theme.HeartCoral
import com.example.sleeptrackersimulator.ui.theme.MistBlue
import com.example.sleeptrackersimulator.ui.theme.MoonBlue
import com.example.sleeptrackersimulator.ui.theme.MotionAmber

@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String = "",
    subtitle: String? = null,
    icon: ImageVector? = null,
    accentColor: Color = MoonBlue,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MistBlue.copy(alpha = 0.65f),
                )
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MistBlue,
                )
                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MistBlue.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }

            if (subtitle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MistBlue.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MistBlue,
            )
        }
    }
}

@Composable
fun ProvenanceChip(
    sourceKind: SourceKind,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (sourceKind) {
        SourceKind.PHONE_REAL -> "PHONE" to CalmGreen
        SourceKind.BLE_REAL -> "WEARABLE" to MoonBlue
        SourceKind.SIMULATED -> "SIMULATED" to HeartCoral
        SourceKind.REPLAY -> "REPLAY" to MotionAmber
        SourceKind.IMPORTED -> "IMPORTED" to Color(0xFFC084FC)
    }
    StatusBadge(text = label, color = color, modifier = modifier)
}

@Composable
fun HorizontalActivityTimeline(
    classes: List<ActivityClass>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(DarkSurface, RoundedCornerShape(8.dp))
                .border(1.dp, DarkSurfaceRaised, RoundedCornerShape(8.dp)),
        ) {
            if (classes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No recorded activity intervals",
                        style = MaterialTheme.typography.labelSmall,
                        color = MistBlue.copy(alpha = 0.4f),
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                    classes.forEach { act ->
                        val segmentColor = when (act) {
                            ActivityClass.REST -> CalmGreen
                            ActivityClass.RESTLESS -> MotionAmber
                            ActivityClass.ACTIVE -> MoonBlue
                            ActivityClass.UNCERTAIN -> Color.Gray.copy(alpha = 0.5f)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .background(segmentColor),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LegendItem(label = "Low motion", color = CalmGreen)
            LegendItem(label = "Movement", color = MotionAmber)
            LegendItem(label = "Active", color = MoonBlue)
            LegendItem(label = "Data gap", color = Color.Gray.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MistBlue.copy(alpha = 0.6f),
        )
    }
}

@Composable
fun EmptyStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceRaised),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MistBlue,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MistBlue.copy(alpha = 0.65f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
