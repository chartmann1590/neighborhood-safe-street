package com.neighborhood.safestreet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.ui.theme.*

@Composable
fun IncidentCard(
    incident: Incident,
    onConfirm: () -> Unit,
    onFlag: (reason: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showFlagDialog by remember { mutableStateOf(false) }

    val categoryColor = when (incident.category) {
        IncidentCategory.FIRE_SMOKE -> AlertRed
        IncidentCategory.MEDICAL_RESPONSE -> AlertRed
        IncidentCategory.POLICE_ACTIVITY -> PoliceBlue
        IncidentCategory.VEHICLE_CRASH -> AlertAmber
        IncidentCategory.ROAD_HAZARD -> WarningYellow
        IncidentCategory.HAZARD_CONDITION -> AlertAmber
        IncidentCategory.WEATHER_HAZARD -> AccentCyan
        IncidentCategory.OTHER_SAFETY -> PrimaryBlue
    }

    val categoryIcon: ImageVector = when (incident.category) {
        IncidentCategory.FIRE_SMOKE -> Icons.Default.LocalFireDepartment
        IncidentCategory.MEDICAL_RESPONSE -> Icons.Default.MedicalServices
        IncidentCategory.POLICE_ACTIVITY -> Icons.Default.LocalPolice
        IncidentCategory.VEHICLE_CRASH -> Icons.Default.CarCrash
        IncidentCategory.ROAD_HAZARD -> Icons.Default.Warning
        IncidentCategory.HAZARD_CONDITION -> Icons.Default.ReportProblem
        IncidentCategory.WEATHER_HAZARD -> Icons.Default.Thunderstorm
        IncidentCategory.OTHER_SAFETY -> Icons.Default.Shield
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Provenance badge & relative occurrence time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProvenanceBadge(provenance = incident.provenance, expiresAtEpochMs = incident.expiresAtEpochMs)
                Text(
                    text = formatRelativeTime(incident.occurredAtEpochMs),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Icon + Title + Subcategory
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(categoryColor.copy(alpha = 0.15f))
                        .border(1.dp, categoryColor.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null,
                        tint = categoryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = incident.title,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = incident.displayAddress,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Timestamps row (as emphasized in the deep-research plan: Occurred, Source Updated, Received)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TimestampItem(label = "Occurred", timeMs = incident.occurredAtEpochMs)
                TimestampItem(label = "Source Updated", timeMs = incident.sourceUpdatedAtEpochMs)
                TimestampItem(label = "App Ingested", timeMs = incident.receivedAtEpochMs)
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    val desc = incident.description
                    if (!desc.isNullOrBlank()) {
                        Text(
                            text = desc,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Attribution & Provenance Info
                    Text(
                        text = "Source: ${incident.agency} • ${incident.licenseInfo}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Actions: Confirm & Flag
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onConfirm,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SafeGreen
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(SafeGreen.copy(alpha = 0.5f))
                            ),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Confirm (${incident.communityConfirmations})", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { showFlagDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Flag,
                                contentDescription = "Report abuse",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFlagDialog) {
        AlertDialog(
            onDismissRequest = { showFlagDialog = false },
            title = { Text("Report Community Incident", color = TextPrimary) },
            text = {
                Text(
                    "Does this report contain inappropriate content, personal identification (names, license plates), or false information?",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onFlag("Inaccurate or contains personal information")
                        showFlagDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Report / Hide")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFlagDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun ProvenanceBadge(provenance: ProvenanceType, expiresAtEpochMs: Long?) {
    val (badgeBg, badgeText, label) = when (provenance) {
        ProvenanceType.OFFICIAL_LIVE -> Triple(PrimaryBlue.copy(alpha = 0.2f), AccentCyan, "OFFICIAL LIVE")
        ProvenanceType.OFFICIAL_DELAYED -> Triple(WarningYellow.copy(alpha = 0.2f), WarningYellow, "OFFICIAL DELAYED")
        ProvenanceType.COMMUNITY_CONFIRMED -> Triple(SafeGreen.copy(alpha = 0.2f), SafeGreen, "COMMUNITY CONFIRMED")
        ProvenanceType.COMMUNITY_UNVERIFIED -> {
            val remainingHours = if (expiresAtEpochMs != null) {
                maxOf(1L, (expiresAtEpochMs - System.currentTimeMillis()) / (3600 * 1000L))
            } else 24L
            Triple(AlertAmber.copy(alpha = 0.2f), AlertAmber, "COMMUNITY (${remainingHours}h left)")
        }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badgeBg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = badgeText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TimestampItem(label: String, timeMs: Long) {
    Column {
        Text(text = label, color = TextMuted, fontSize = 9.sp)
        Text(text = formatRelativeTime(timeMs), color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatRelativeTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        diff < 0 -> "just now"
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}
