package com.neighborhood.safestreet.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.viewinterop.AndroidView
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenMapScreen(
    incidents: List<Incident>,
    userLatitude: Double = 47.6062,
    userLongitude: Double = -122.3321,
    initialFocusedIncident: Incident? = null,
    onBack: () -> Unit,
    onConfirmIncident: (String) -> Unit,
    onFlagIncident: (String, String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf<IncidentCategory?>(null) }
    var selectedIncident by remember { mutableStateOf<Incident?>(initialFocusedIncident) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val filteredIncidents = remember(incidents, selectedCategory) {
        if (selectedCategory == null) incidents
        else incidents.filter { it.category == selectedCategory }
    }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        // 1. Interactive OpenStreetMap (OSMDroid)
        AndroidView(
            factory = { context ->
                Configuration.getInstance().userAgentValue = context.packageName
                MapView(context).apply {
                    mapViewRef = this
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                    controller.setZoom(14.5)
                    controller.setCenter(
                        if (initialFocusedIncident != null)
                            GeoPoint(initialFocusedIncident.latitude, initialFocusedIncident.longitude)
                        else
                            GeoPoint(userLatitude, userLongitude)
                    )
                }
            },
            update = { mapView ->
                mapViewRef = mapView
                mapView.overlays.clear()

                val userGeo = GeoPoint(userLatitude, userLongitude)

                // User Location Radius Circle (approx 3 miles / 4800m)
                val circle = Polygon.pointsAsCircle(userGeo, 4800.0)
                val polygon = Polygon(mapView).apply {
                    points = circle
                    fillPaint.color = android.graphics.Color.parseColor("#1500E5FF")
                    outlinePaint.color = android.graphics.Color.parseColor("#6600E5FF")
                    outlinePaint.strokeWidth = 3f
                }
                mapView.overlays.add(polygon)

                // User Location Marker
                val userMarker = Marker(mapView).apply {
                    position = userGeo
                    icon = MapMarkerHelper.createUserLocationDrawable(mapView.context)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Your Location"
                }
                mapView.overlays.add(userMarker)

                // Incident Markers
                for (incident in filteredIncidents) {
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(incident.latitude, incident.longitude)
                        val isSelected = selectedIncident?.id == incident.id
                        icon = MapMarkerHelper.createCategoryMarkerDrawable(mapView.context, incident.category, isSelected)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = incident.title
                        snippet = incident.displayAddress

                        setOnMarkerClickListener { _, _ ->
                            selectedIncident = incident
                            mapView.controller.animateTo(position)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Header Bar & Category Filter Chips
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface.copy(alpha = 0.94f))
                .statusBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Safety Incident Map",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${filteredIncidents.size} incidents plotted • Free OpenStreetMap",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                IconButton(
                    onClick = {
                        mapViewRef?.controller?.animateTo(GeoPoint(userLatitude, userLongitude))
                    }
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Center on me", tint = AccentCyan)
                }
            }

            // Category Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All Types", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkBackground,
                            labelColor = TextSecondary
                        )
                    )
                }

                items(IncidentCategory.entries) { cat ->
                    val isSelected = selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = if (isSelected) null else cat },
                        label = { Text(cat.displayName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentCyan,
                            selectedLabelColor = DarkBackground,
                            containerColor = DarkBackground,
                            labelColor = TextSecondary
                        )
                    )
                }
            }
        }

        // 3. Floating Incident Detail Card (When an incident marker is tapped)
        AnimatedVisibility(
            visible = selectedIncident != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp)
                .navigationBarsPadding()
        ) {
            val incident = selectedIncident
            if (incident != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val badgeColor = when (incident.provenance) {
                                ProvenanceType.OFFICIAL_LIVE -> AlertRed
                                ProvenanceType.OFFICIAL_DELAYED -> WarningYellow
                                ProvenanceType.COMMUNITY_CONFIRMED -> SafeGreen
                                ProvenanceType.COMMUNITY_UNVERIFIED -> AccentCyan
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(badgeColor.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = incident.provenance.label,
                                    color = badgeColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            IconButton(
                                onClick = { selectedIncident = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = incident.title,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = incident.displayAddress,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        val desc = incident.description
                        if (!desc.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = desc,
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 3
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Actions for community items
                        if (incident.provenance == ProvenanceType.COMMUNITY_UNVERIFIED || incident.provenance == ProvenanceType.COMMUNITY_CONFIRMED) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onConfirmIncident(incident.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Confirm (${incident.communityConfirmations})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { onFlagIncident(incident.id, "Flagged from Map") },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertAmber),
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Flag", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
