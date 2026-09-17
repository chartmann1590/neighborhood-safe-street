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
import com.neighborhood.safestreet.ui.components.*
import com.neighborhood.safestreet.ui.theme.*
import com.neighborhood.safestreet.ui.viewmodel.AuthorityFilter
import com.neighborhood.safestreet.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val incidents by viewModel.filteredIncidents.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isWearConnected by viewModel.isWearConnected.collectAsStateWithLifecycle()
    val connectedNodeName by viewModel.connectedNodeName.collectAsStateWithLifecycle()
    val lastSyncStatus by viewModel.lastSyncStatus.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val selectedAuthority by viewModel.selectedAuthority.collectAsStateWithLifecycle()
    val isRadarView by viewModel.isRadarView.collectAsStateWithLifecycle()
    val showReportDialog by viewModel.showReportDialog.collectAsStateWithLifecycle()
    val showWearDialog by viewModel.showWearDialog.collectAsStateWithLifecycle()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsStateWithLifecycle()

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
            // Authority Filter Chips (Official vs Community)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            Spacer(modifier = Modifier.height(6.dp))

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
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isRadarView) {
                        item {
                            RadarView(incidents = incidents)
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
                            IncidentCard(
                                incident = incident,
                                onConfirm = { viewModel.confirmIncident(incident.id) },
                                onFlag = { reason -> viewModel.flagIncident(incident.id, reason) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showReportDialog) {
        ReportBottomSheet(
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
