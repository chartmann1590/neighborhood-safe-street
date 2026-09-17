package com.neighborhood.safestreet.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    userLatitude: Double = 42.8249,
    userLongitude: Double = -73.9270,
    radiusMiles: Double = 5.0,
    selectedRange: RadarRangeOption = RadarRangeOption.RANGE_5MI,
    onRangeSelected: (RadarRangeOption) -> Unit = {},
    initialFocusedIncident: Incident? = null,
    onBack: () -> Unit,
    onConfirmIncident: (String) -> Unit,
    onFlagIncident: (String, String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf<IncidentCategory?>(null) }
    var selectedIncident by remember { mutableStateOf<Incident?>(initialFocusedIncident) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val activeIncident = remember(selectedIncident, incidents) {
        if (selectedIncident == null) null
        else incidents.find { it.id == selectedIncident?.id } ?: selectedIncident
    }

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
                    val target = if (initialFocusedIncident != null)
                        GeoPoint(initialFocusedIncident.latitude, initialFocusedIncident.longitude)
                    else
                        GeoPoint(userLatitude, userLongitude)
                    controller.setZoom(if (initialFocusedIncident != null) 16.0 else 15.0)
                    controller.setCenter(target)
                    addOnFirstLayoutListener { _, _, _, _, _ ->
                        controller.setCenter(target)
                        invalidate()
                    }
                }
            },
            update = { mapView ->
                mapViewRef = mapView
                mapView.overlays.clear()

                val userGeo = GeoPoint(userLatitude, userLongitude)

                // User Location Radius Circle matching active radius filter
                val radiusMeters = radiusMiles * 1609.344
                val circle = Polygon.pointsAsCircle(userGeo, radiusMeters)
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
                        icon = MapMarkerHelper.createCategoryMarkerDrawable(
                            mapView.context,
                            incident.category,
                            selectedIncident?.id == incident.id
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = incident.title
                        snippet = incident.displayAddress
                        setOnMarkerClickListener { m, _ ->
                            selectedIncident = incident
                            mapView.controller.animateTo(m.position)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                // If user didn't select an incident, focus on user location
                if (selectedIncident == null && initialFocusedIncident == null) {
                    mapView.controller.setCenter(userGeo)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Header Overlay with Back Button, Radius Chips & Category Chips
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkBackground.copy(alpha = 0.92f))
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
                        text = "${filteredIncidents.size} incidents within ${radiusMiles.toInt()} mi • Free OSM",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                // Range Selector Chips on Map
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(RadarRangeOption.RANGE_1MI, RadarRangeOption.RANGE_5MI, RadarRangeOption.RANGE_25MI).forEach { opt ->
                        val isSelected = selectedRange == opt
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) AccentCyan else DarkSurfaceVariant)
                                .clickable { onRangeSelected(opt) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = opt.label,
                                color = if (isSelected) DarkBackground else TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        mapViewRef?.controller?.animateTo(GeoPoint(userLatitude, userLongitude))
                    }
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Center on me", tint = AccentCyan)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Category Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
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
                            containerColor = DarkBackground.copy(alpha = 0.85f),
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
                            containerColor = DarkBackground.copy(alpha = 0.85f),
                            labelColor = TextSecondary
                        )
                    )
                }
            }
        }

        // 3. Floating Incident Detail Card (When an incident marker is tapped)
        AnimatedVisibility(
            visible = activeIncident != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            if (activeIncident != null) {
                val inc = activeIncident
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(PrimaryBlue)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Badge(
                                containerColor = if (inc.provenance == ProvenanceType.OFFICIAL_LIVE) SafeGreen.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                contentColor = if (inc.provenance == ProvenanceType.OFFICIAL_LIVE) SafeGreen else TextSecondary
                            ) {
                                Text(
                                    inc.provenance.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            IconButton(
                                onClick = { selectedIncident = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = inc.title,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = inc.displayAddress,
                            color = AccentCyan,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = inc.description ?: "",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Action Buttons: Community Confirmation & Flagging
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onConfirmIncident(inc.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Confirm (${inc.communityConfirmations})", fontSize = 11.sp)
                            }

                            OutlinedButton(
                                onClick = { onFlagIncident(inc.id, "User reported inaccurate") },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertAmber),
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
