package com.neighborhood.safestreet.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType

class WearMainActivity : ComponentActivity() {
    private lateinit var dataManager: WearDataManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        dataManager.checkAndFetchLocation()
        dataManager.refreshData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataManager = WearDataManager(applicationContext, lifecycleScope)

        requestWearPermissions()

        setContent {
            MaterialTheme {
                WearApp(
                    dataManager = dataManager,
                    onRequestPermissions = { requestWearPermissions() },
                    onCallEmergency = {
                        try {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))
                            startActivity(intent)
                        } catch (e: Exception) {
                            // ignore on emulator without dialer
                        }
                    }
                )
            }
        }
    }

    private fun requestWearPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        dataManager.checkAndFetchLocation()
        dataManager.refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()
        dataManager.cleanup()
    }
}

@Composable
fun WearApp(
    dataManager: WearDataManager,
    onRequestPermissions: () -> Unit = {},
    onCallEmergency: () -> Unit
) {
    val incidents by dataManager.incidents.collectAsState()
    val mode by dataManager.connectionMode.collectAsState()
    val statusMsg by dataManager.statusMessage.collectAsState()
    val activeAlert by dataManager.activeAlertIncident.collectAsState()
    val hasLocationPermission by dataManager.hasLocationPermission.collectAsState()
    val watchLocation by dataManager.watchLocation.collectAsState()
    val phoneLocation by dataManager.phoneLocation.collectAsState()
    val listState = rememberScalingLazyListState()

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: SafeStreet Wear Logo & Connection Status
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "SafeStreet",
                        color = Color(0xFF00E5FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    val (dotColor, modeLabel) = when (mode) {
                        WearConnectionMode.BLUETOOTH_PHONE -> Pair(Color(0xFF00E676), "Phone Bluetooth")
                        WearConnectionMode.INTERNET_FALLBACK -> Pair(Color(0xFF00B0FF), "Internet Fallback")
                        WearConnectionMode.SEARCHING -> Pair(Color(0xFFFF9100), "Standby")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = modeLabel, color = Color.Gray, fontSize = 10.sp)
                    }

                    // Location coordinate indicator
                    val activeCoord = watchLocation ?: phoneLocation
                    if (activeCoord != null) {
                        val providerLabel = if (watchLocation != null) "Watch GPS" else "Phone GPS"
                        Text(
                            text = "📍 $providerLabel [${String.format(java.util.Locale.US, "%.3f", activeCoord.first)}, ${String.format(java.util.Locale.US, "%.3f", activeCoord.second)}]",
                            color = Color(0xFF80D8FF),
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }

            // Location Access Permission chip if GPS permission is not granted on watch
            if (!hasLocationPermission) {
                item {
                    CompactChip(
                        onClick = onRequestPermissions,
                        label = { Text("📍 Grant Watch GPS", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF451A03)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // CRITICAL WRIST ALERT BANNER (If high-priority alert received from phone or network)
            if (activeAlert != null) {
                val alert = activeAlert!!
                item {
                    Card(
                        onClick = { dataManager.dismissAlert() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                        backgroundPainter = CardDefaults.cardBackgroundPainter(
                            startBackgroundColor = Color(0xFF5C0A0A),
                            endBackgroundColor = Color(0xFF2E0505)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🚨 CRITICAL ALERT",
                                    color = Color(0xFFFF1744),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "DISMISS ✕",
                                    color = Color.LightGray,
                                    fontSize = 7.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = alert.title,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2
                            )
                            Text(
                                text = alert.displayAddress,
                                color = Color(0xFFFFD54F),
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Quick Emergency SOS Button
            item {
                Button(
                    onClick = onCallEmergency,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFF1744)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Text(
                        text = "EMERGENCY SOS",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }
            }

            // Quick Observation Report Chips from Wrist
            item {
                Text(
                    text = "QUICK REPORT FROM WRIST",
                    color = Color.LightGray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactChip(
                        onClick = { dataManager.quickReportFromWrist(IncidentCategory.FIRE_SMOKE) },
                        label = { Text("Fire", fontSize = 10.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF371B1B)),
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = { dataManager.quickReportFromWrist(IncidentCategory.VEHICLE_CRASH) },
                        label = { Text("Crash", fontSize = 10.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF3E2713)),
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = { dataManager.quickReportFromWrist(IncidentCategory.POLICE_ACTIVITY) },
                        label = { Text("Police", fontSize = 10.sp) },
                        colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF13233E)),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Text(
                    text = "ACTIVE LOCAL ALERTS (${incidents.size})",
                    color = Color.LightGray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // Incident cards for Wear OS
            items(incidents) { incident ->
                val dist = dataManager.calculateDistanceMiles(incident.latitude, incident.longitude)
                WearIncidentCard(incident = incident, distanceMiles = dist)
            }

            // Refresh & fallback fetch trigger
            item {
                CompactChip(
                    onClick = { dataManager.refreshData() },
                    label = { Text("Sync / Direct Fetch", fontSize = 10.sp) },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
fun WearIncidentCard(incident: Incident, distanceMiles: Double? = null) {
    val borderColor = when (incident.category) {
        IncidentCategory.FIRE_SMOKE -> Color(0xFFFF3D00)
        IncidentCategory.POLICE_ACTIVITY -> Color(0xFF2979FF)
        IncidentCategory.MEDICAL_RESPONSE -> Color(0xFFFF3D00)
        IncidentCategory.VEHICLE_CRASH -> Color(0xFFFF9100)
        else -> Color(0xFF00E5FF)
    }

    val provenanceTag = when (incident.provenance) {
        ProvenanceType.OFFICIAL_LIVE -> "OFFICIAL"
        ProvenanceType.OFFICIAL_DELAYED -> "DELAYED"
        ProvenanceType.COMMUNITY_CONFIRMED -> "CONFIRMED"
        ProvenanceType.COMMUNITY_UNVERIFIED -> "COMMUNITY"
    }

    Card(
        onClick = { /* Display details or vibrate */ },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = Color(0xFF181E2A),
            endBackgroundColor = Color(0xFF181E2A)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = provenanceTag,
                    color = borderColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (distanceMiles != null) {
                        val distFormatted = if (distanceMiles < 0.1) "<0.1mi" else if (distanceMiles < 10) String.format(java.util.Locale.US, "%.1fmi", distanceMiles) else String.format(java.util.Locale.US, "%.0fmi", distanceMiles)
                        Text(
                            text = distFormatted,
                            color = Color(0xFF00E5FF),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatWearTime(incident.occurredAtEpochMs),
                        color = Color.Gray,
                        fontSize = 8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = incident.title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )

            Text(
                text = incident.displayAddress,
                color = Color.LightGray,
                fontSize = 9.sp,
                maxLines = 1
            )
        }
    }
}

private fun formatWearTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val minutes = diff / (60 * 1000)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }
}
