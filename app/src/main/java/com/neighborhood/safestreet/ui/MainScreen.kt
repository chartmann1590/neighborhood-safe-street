package com.neighborhood.safestreet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.util.GeoUtils
import com.neighborhood.safestreet.ui.components.*
import com.neighborhood.safestreet.ui.theme.*
import com.neighborhood.safestreet.ui.viewmodel.AuthorityFilter
import com.neighborhood.safestreet.ui.viewmodel.IncidentSortOrder
import com.neighborhood.safestreet.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestLocationPermission: () -> Unit = {}
) {
    val incidents by viewModel.filteredIncidents.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLocationPermissionGranted by viewModel.isLocationPermissionGranted.collectAsStateWithLifecycle()
    val isWearConnected by viewModel.isWearConnected.collectAsStateWithLifecycle()
    val connectedNodeName by viewModel.connectedNodeName.collectAsStateWithLifecycle()
    val lastSyncStatus by viewModel.lastSyncStatus.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedAuthority by viewModel.selectedAuthority.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isRadarView by viewModel.isRadarView.collectAsStateWithLifecycle()
    val showFullscreenMap by viewModel.showFullscreenMap.collectAsStateWithLifecycle()
    val focusedIncidentForMap by viewModel.focusedIncidentForMap.collectAsStateWithLifecycle()
    val showAlertSettings by viewModel.showAlertSettings.collectAsStateWithLifecycle()
    val alertPreferences by viewModel.alertPreferences.collectAsStateWithLifecycle()
    val activeInAppAlert by viewModel.activeInAppAlert.collectAsStateWithLifecycle()
    val userLat by viewModel.userLatitude.collectAsStateWithLifecycle()
    val userLon by viewModel.userLongitude.collectAsStateWithLifecycle()
    val showReportDialog by viewModel.showReportDialog.collectAsStateWithLifecycle()
    val showWearDialog by viewModel.showWearDialog.collectAsStateWithLifecycle()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsStateWithLifecycle()

    // If user opened the fullscreen map, render the interactive OSM map screen
    if (showFullscreenMap) {
        FullscreenMapScreen(
            incidents = incidents,
            userLatitude = userLat,
            userLongitude = userLon,
            initialFocusedIncident = focusedIncidentForMap,
            onBack = { viewModel.closeFullscreenMap() },
            onConfirmIncident = { viewModel.confirmIncident(it) },
            onFlagIncident = { id, r -> viewModel.flagIncident(id, r) }
        )
        return
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = DarkBackground,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SafeStreet",
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Worldwide Public Safety",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                },
                actions = {
                    // Alert Settings Button with Active Ring / Bell
                    IconButton(onClick = { viewModel.setShowAlertSettings(true) }) {
                        BadgedBox(
                            badge = {
                                if (alertPreferences.enabled) {
                                    Badge(
                                        containerColor = AccentCyan,
                                        modifier = Modifier.size(6.dp)
                                    )
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = "Alert Preferences",
                                tint = if (alertPreferences.enabled) AccentCyan else TextMuted
                            )
                        }
                    }

                    // Wear OS Connection Indicator Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceVariant)
                            .clickable { viewModel.setShowWearDialog(true) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Watch,
                            contentDescription = "Wear OS Status",
                            tint = if (isWearConnected) SafeGreen else AlertAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isWearConnected) "Watch" else "Standby",
                            color = if (isWearConnected) SafeGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    IconButton(onClick = { viewModel.toggleViewMode() }) {
                        Icon(
                            if (isRadarView) Icons.Default.List else Icons.Default.Radar,
                            contentDescription = "Toggle View",
                            tint = AccentCyan
                        )
                    }

                    IconButton(onClick = { viewModel.setShowSourcesDialog(true) }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Sources & Info",
                            tint = TextSecondary
                        )
                    }

                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.setShowReportDialog(true) },
                containerColor = PrimaryBlue,
                contentColor = DarkBackground,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Report Observation", fontWeight = FontWeight.Bold)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Real-time In-App Alert Banner (pops down on nearby urgent alerts)
            InAppAlertBanner(
                incident = activeInAppAlert,
                onDismiss = { viewModel.dismissInAppAlert() },
                onViewOnMap = { incident -> viewModel.openFullscreenMap(incident) }
            )

            // Location Access Status Banner (if GPS is disabled or permissions not yet granted)
            if (!isLocationPermissionGranted) {
                Surface(
                    color = Color(0xFF2A1C0A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clickable { onRequestLocationPermission() },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LocationOff,
                            contentDescription = null,
                            tint = AlertAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Location Access Needed",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Enable GPS for proximity radar and nearby safety alerts",
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                        TextButton(
                            onClick = onRequestLocationPermission,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("ENABLE", color = AlertAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Authority Filter Chips (Official vs Community) & Proximity Sort Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuthorityFilter.entries.forEach { filter ->
                    val isSelected = filter == selectedAuthority
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setAuthorityFilter(filter) },
                        label = { Text(filter.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryBlue,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        )
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Sort Order Toggle (Nearest vs Newest)
                FilterChip(
                    selected = sortOrder == IncidentSortOrder.NEAREST,
                    onClick = {
                        viewModel.setSortOrder(
                            if (sortOrder == IncidentSortOrder.NEAREST) IncidentSortOrder.NEWEST else IncidentSortOrder.NEAREST
                        )
                    },
                    label = {
                        Text(
                            text = if (sortOrder == IncidentSortOrder.NEAREST) "📍 Nearest" else "⏱️ Newest",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentCyan,
                        selectedLabelColor = DarkBackground,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    )
                )
            }

            // Category Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { viewModel.setCategoryFilter(null) },
                        label = { Text("All Types", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        )
                    )
                }

                items(IncidentCategory.entries) { cat ->
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setCategoryFilter(if (isSelected) null else cat) },
                        label = { Text(cat.displayName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkSurface,
                            labelColor = TextSecondary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Content Area
            if (isLoading && incidents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isRadarView) {
                        item {
                            // Radar with real OSM Map tiles behind it and tap-to-fullscreen
                            RadarView(
                                incidents = incidents,
                                userLatitude = userLat,
                                userLongitude = userLon,
                                onExpandMap = { viewModel.openFullscreenMap() },
                                onIncidentSelected = { inc -> viewModel.openFullscreenMap(inc) }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }

                    if (incidents.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No incidents match the active filters.",
                                    color = TextMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(incidents, key = { it.id }) { incident ->
                            val dist = GeoUtils.calculateDistanceMiles(
                                userLat,
                                userLon,
                                incident.latitude,
                                incident.longitude
                            )
                            IncidentCard(
                                incident = incident,
                                distanceMiles = dist,
                                onConfirm = { viewModel.confirmIncident(incident.id) },
                                onFlag = { reason -> viewModel.flagIncident(incident.id, reason) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs & Sheets
    if (showAlertSettings) {
        AlertSettingsSheet(
            preferences = alertPreferences,
            onToggleCategory = { viewModel.alertPreferencesRepo.toggleCategory(it) },
            onSetRadius = { viewModel.alertPreferencesRepo.setRadius(it) },
            onSetEnabled = { viewModel.alertPreferencesRepo.setEnabled(it) },
            onSetPushToWatch = { viewModel.alertPreferencesRepo.setPushToWatch(it) },
            onSetSoundVibration = { viewModel.alertPreferencesRepo.setSoundVibration(it) },
            onSendTestAlert = { viewModel.sendTestSafetyAlert() },
            onDismiss = { viewModel.setShowAlertSettings(false) }
        )
    }

    if (showReportDialog) {
        ReportBottomSheet(
            userLatitude = userLat,
            userLongitude = userLon,
            onDismiss = { viewModel.setShowReportDialog(false) },
            onSubmit = { category, note, lat, lon ->
                viewModel.submitCommunityReport(category, note, lat, lon)
            }
        )
    }

    if (showWearDialog) {
        WearSyncDialog(
            isWearConnected = isWearConnected,
            connectedNodeName = connectedNodeName,
            lastSyncStatus = lastSyncStatus,
            onSyncNow = { viewModel.syncToWear() },
            onSendTestAlert = { viewModel.sendTestAlertToWear() },
            onDismiss = { viewModel.setShowWearDialog(false) }
        )
    }

    if (showSourcesDialog) {
        SourcesSheet(onDismiss = { viewModel.setShowSourcesDialog(false) })
    }
}
