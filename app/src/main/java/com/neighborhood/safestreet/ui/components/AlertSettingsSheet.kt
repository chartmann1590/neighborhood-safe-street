package com.neighborhood.safestreet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.data.alerts.AlertPreferences
import com.neighborhood.safestreet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSettingsSheet(
    preferences: AlertPreferences,
    onToggleCategory: (IncidentCategory) -> Unit,
    onSetRadius: (Double) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetPushToWatch: (Boolean) -> Unit,
    onSetSoundVibration: (Boolean) -> Unit,
    onSendTestAlert: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Safety Alert Preferences",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Configure push notifications for nearby incidents",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Master Switch
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkBackground),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Proximity Push Alerts",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Receive notifications when verified safety events occur nearby",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = preferences.enabled,
                        onCheckedChange = onSetEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AccentCyan,
                            checkedTrackColor = AccentCyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = DarkSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Proximity Radius
            Text(
                text = "ALERT RADIUS (${preferences.radiusMiles.toInt()} MILES)",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            val radii = listOf(1.0, 3.0, 5.0, 10.0, 25.0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                radii.forEach { r ->
                    val isSelected = preferences.radiusMiles == r
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSetRadius(r) },
                        label = { Text("${r.toInt()} mi", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkBackground,
                            labelColor = TextSecondary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Incident Categories to receive alerts for
            Text(
                text = "ALERT FOR INCIDENT TYPES",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            val availableCategories = listOf(
                IncidentCategory.FIRE_SMOKE,
                IncidentCategory.POLICE_ACTIVITY,
                IncidentCategory.VEHICLE_CRASH,
                IncidentCategory.MEDICAL_RESPONSE,
                IncidentCategory.HAZARD_CONDITION,
                IncidentCategory.WEATHER_HAZARD
            )

            availableCategories.forEach { cat ->
                val isChecked = preferences.categories.contains(cat)
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkBackground),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val iconTint = when (cat) {
                                IncidentCategory.FIRE_SMOKE -> AlertRed
                                IncidentCategory.POLICE_ACTIVITY -> PoliceBlue
                                IncidentCategory.VEHICLE_CRASH -> AlertAmber
                                IncidentCategory.MEDICAL_RESPONSE -> AlertRed
                                else -> WarningYellow
                            }
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = cat.displayName,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onToggleCategory(cat) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentCyan,
                                checkmarkColor = DarkBackground,
                                uncheckedColor = TextMuted
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Wear OS Companion & Sound Switches
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkBackground),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Watch, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Push to Wear OS Companion", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Vibrate and sync alert to smartwatch", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                        Switch(
                            checked = preferences.pushToWatch,
                            onCheckedChange = onSetPushToWatch,
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentCyan.copy(alpha = 0.3f))
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = DarkSurfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Sound & Vibration", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Play alarm vibration pattern", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                        Switch(
                            checked = preferences.soundVibration,
                            onCheckedChange = onSetSoundVibration,
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlue, checkedTrackColor = PrimaryBlue.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Test Alert Button
            OutlinedButton(
                onClick = onSendTestAlert,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertAmber),
                border = androidx.compose.foundation.BorderStroke(1.dp, AlertAmber.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Test Push Alert (Phone + Watch)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
