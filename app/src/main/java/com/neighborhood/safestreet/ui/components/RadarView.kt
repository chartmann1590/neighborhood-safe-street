package com.neighborhood.safestreet.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.util.GeoUtils
import com.neighborhood.safestreet.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.*

enum class RadarRangeOption(val miles: Double, val label: String) {
    RANGE_1MI(1.0, "1 mi"),
    RANGE_3MI(3.0, "3 mi"),
    RANGE_5MI(5.0, "5 mi"),
    RANGE_10MI(10.0, "10 mi"),
    RANGE_25MI(25.0, "25 mi"),
    RANGE_AUTO(0.0, "Auto")
}

data class ProjectedBlip(
    val incident: Incident,
    val distanceMiles: Double,
    val bearingDegrees: Double,
    val screenX: Float,
    val screenY: Float,
    val isInsideScope: Boolean
)

@Composable
fun RadarView(
    incidents: List<Incident>,
    userLatitude: Double = 42.8249,
    userLongitude: Double = -73.9270,
    selectedRange: RadarRangeOption = RadarRangeOption.RANGE_5MI,
    onRangeSelected: (RadarRangeOption) -> Unit = {},
    onExpandMap: () -> Unit = {},
    onIncidentSelected: (Incident) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedBlip by remember { mutableStateOf<ProjectedBlip?>(null) }
    var projectedBlips by remember { mutableStateOf<List<ProjectedBlip>>(emptyList()) }

    // Effective radar range
    val effectiveRangeMiles = remember(selectedRange, incidents, userLatitude, userLongitude) {
        if (selectedRange == RadarRangeOption.RANGE_AUTO) {
            val minDistance = incidents.minOfOrNull {
                GeoUtils.calculateDistanceMiles(userLatitude, userLongitude, it.latitude, it.longitude)
            } ?: 5.0
            when {
                minDistance <= 1.0 -> 1.0
                minDistance <= 3.0 -> 3.0
                minDistance <= 5.0 -> 5.0
                minDistance <= 10.0 -> 10.0
                minDistance <= 25.0 -> 25.0
                minDistance <= 50.0 -> 50.0
                else -> 100.0
            }
        } else {
            selectedRange.miles
        }
    }

    // Continuous clockwise radar sweep animation (0° to 360°)
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarSweepAngle"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(310.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
    ) {
        // 1. Interactive OpenStreetMap background synchronized to user location
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(false)
                    isTilesScaledToDpi = true
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(14.5)
                    controller.setCenter(GeoPoint(userLatitude, userLongitude))
                    addOnFirstLayoutListener { _, _, _, _, _ ->
                        controller.setCenter(GeoPoint(userLatitude, userLongitude))
                        invalidate()
                    }
                }
            },
            update = { mapView ->
                // Center map and scale zoom whenever user GPS coordinates or range option update
                val targetZoom = when {
                    effectiveRangeMiles <= 1.5 -> 15.5
                    effectiveRangeMiles <= 6.0 -> 14.0
                    effectiveRangeMiles <= 15.0 -> 12.5
                    effectiveRangeMiles <= 30.0 -> 11.0
                    else -> 9.5
                }
                mapView.controller.setZoom(targetZoom)
                mapView.controller.setCenter(GeoPoint(userLatitude, userLongitude))
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. High-Tech Tactical Dark Filter
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x94090E16))
        )

        // 3. Accurate Geodetic Radar Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .pointerInput(projectedBlips) {
                    detectTapGestures { tapOffset ->
                        // Detect tap within 24dp of any blip
                        val hitBlip = projectedBlips.find { blip ->
                            val dx = blip.screenX - tapOffset.x
                            val dy = blip.screenY - tapOffset.y
                            sqrt(dx * dx + dy * dy) <= 60f
                        }
                        if (hitBlip != null) {
                            selectedBlip = hitBlip
                            onIncidentSelected(hitBlip.incident)
                        } else {
                            selectedBlip = null
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2, size.height / 2 + 8.dp.toPx())
            val maxRadius = minOf(size.width, size.height - 30.dp.toPx()) / 2 * 0.90f

            // Calculate true real-world projection for all incidents
            val currentBlips = incidents.map { inc ->
                val dist = GeoUtils.calculateDistanceMiles(userLatitude, userLongitude, inc.latitude, inc.longitude)
                val bearing = GeoUtils.calculateBearing(userLatitude, userLongitude, inc.latitude, inc.longitude)
                val isInside = dist <= effectiveRangeMiles
                val distFraction = if (isInside) (dist / effectiveRangeMiles).toFloat() else 1.0f
                val r = maxRadius * distFraction
                val bearingRad = Math.toRadians(bearing)

                // 0° is North (Up), 90° East (Right), 180° South (Down), 270° West (Left)
                val px = center.x + (r * sin(bearingRad)).toFloat()
                val py = center.y - (r * cos(bearingRad)).toFloat()

                ProjectedBlip(
                    incident = inc,
                    distanceMiles = dist,
                    bearingDegrees = bearing,
                    screenX = px,
                    screenY = py,
                    isInsideScope = isInside
                )
            }
            projectedBlips = currentBlips

            // Concentric range rings (1/3, 2/3, 3/3 of range)
            val ringFractions = listOf(0.33f, 0.66f, 1.0f)
            for (fraction in ringFractions) {
                drawCircle(
                    color = AccentCyan.copy(alpha = 0.28f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Crosshairs (North-South & East-West)
            drawLine(
                color = AccentCyan.copy(alpha = 0.22f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = AccentCyan.copy(alpha = 0.22f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Dynamic Phosphor Radar Sweep Beam (Wedge sector + leading laser)
            val sweepRad = Math.toRadians(sweepAngle.toDouble())
            val beamEndX = center.x + (maxRadius * sin(sweepRad)).toFloat()
            val beamEndY = center.y - (maxRadius * cos(sweepRad)).toFloat()

            // Draw fading phosphor trail (trailing wedge sector)
            drawRadarSweepWedge(
                center = center,
                radius = maxRadius,
                currentSweepDegrees = sweepAngle,
                trailAngleDegrees = 45f
            )

            // Draw bright leading sweep line
            drawLine(
                brush = Brush.radialGradient(
                    colors = listOf(AccentCyan, AccentCyan.copy(alpha = 0.1f)),
                    center = center,
                    radius = maxRadius
                ),
                start = center,
                end = Offset(beamEndX, beamEndY),
                strokeWidth = 2.5.dp.toPx()
            )

            // Center User Location Glowing Ring & Dot
            drawCircle(
                color = AccentCyan,
                radius = 5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx() * pulseScale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Render Incident Blips with Real Sweep Phosphor Excitation
            currentBlips.filter { it.isInsideScope }.forEach { blip ->
                val categoryColor = getCategoryColor(blip.incident.category)

                // Angular difference between sweep beam and blip's true azimuth
                val sweepDiff = ((sweepAngle - blip.bearingDegrees.toFloat() + 360f) % 360f)
                val isExcited = sweepDiff in 0f..45f
                val phosphorAlpha = if (isExcited) 1f - (sweepDiff / 45f) else 0f

                val blipOffset = Offset(blip.screenX, blip.screenY)

                // Phosphor excitation ping ring when beam sweeps past
                if (phosphorAlpha > 0f) {
                    drawCircle(
                        color = categoryColor.copy(alpha = phosphorAlpha * 0.85f),
                        radius = (6.dp.toPx() + (14.dp.toPx() * (1f - phosphorAlpha))),
                        center = blipOffset,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // High priority pulsing ring
                if (blip.incident.isHighPriority) {
                    drawCircle(
                        color = categoryColor.copy(alpha = 0.45f),
                        radius = 9.dp.toPx() * pulseScale,
                        center = blipOffset,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }

                // Selected blip target reticle
                if (selectedBlip?.incident?.id == blip.incident.id) {
                    drawCircle(
                        color = Color.White,
                        radius = 12.dp.toPx(),
                        center = blipOffset,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Blip core dot
                drawCircle(
                    color = categoryColor,
                    radius = (if (phosphorAlpha > 0f) 6.5f else 5.0f).dp.toPx(),
                    center = blipOffset
                )

                if (phosphorAlpha > 0f) {
                    drawCircle(
                        color = Color.White.copy(alpha = phosphorAlpha),
                        radius = 3.dp.toPx(),
                        center = blipOffset
                    )
                }
            }

            // Directional indicators for distant incidents outside scope
            val distantCount = currentBlips.count { !it.isInsideScope }
            if (distantCount > 0) {
                currentBlips.filter { !it.isInsideScope }.take(8).forEach { blip ->
                    val catColor = getCategoryColor(blip.incident.category)
                    drawCircle(
                        color = catColor.copy(alpha = 0.7f),
                        radius = 3.5.dp.toPx(),
                        center = Offset(blip.screenX, blip.screenY)
                    )
                }
            }
        }

        // 4. Header Bar: Scope Status, GPS Location, and Active Range
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground.copy(alpha = 0.90f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SafeGreen)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "RADAR: ${String.format(java.util.Locale.US, "%.0f", effectiveRangeMiles)} MI RANGE",
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                val inScopeCount = projectedBlips.count { it.isInsideScope }
                Text(
                    text = if (inScopeCount > 0) "$inScopeCount nearby in scope" else "Area clear within range",
                    color = if (inScopeCount > 0) AccentCyan else TextMuted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 5. Radar Range Selector Chips (Top Right)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground.copy(alpha = 0.90f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(RadarRangeOption.RANGE_1MI, RadarRangeOption.RANGE_5MI, RadarRangeOption.RANGE_25MI, RadarRangeOption.RANGE_AUTO).forEach { opt ->
                val isSelected = selectedRange == opt
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) AccentCyan else Color.Transparent)
                        .clickable { onRangeSelected(opt) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
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

        // 6. Selected Incident Floating Card (if user taps a blip on radar)
        if (selectedBlip != null) {
            val blip = selectedBlip!!
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .widthIn(max = 240.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = blip.incident.title,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "📍 ${GeoUtils.formatDistanceMiles(blip.distanceMiles)} away • Bearing ${blip.bearingDegrees.toInt()}°",
                            color = AccentCyan,
                            fontSize = 9.sp
                        )
                    }
                    IconButton(
                        onClick = { selectedBlip = null },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        // 7. Expand Fullscreen Map Badge (Bottom Right)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AccentCyan)
                .clickable { onExpandMap() }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.OpenInFull,
                contentDescription = "Fullscreen",
                tint = DarkBackground,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "FULLSCREEN MAP",
                color = DarkBackground,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

/**
 * Draws a fading trailing wedge behind the sweeping radar beam.
 */
private fun DrawScope.drawRadarSweepWedge(
    center: Offset,
    radius: Float,
    currentSweepDegrees: Float,
    trailAngleDegrees: Float
) {
    val steps = 15
    val stepAngle = trailAngleDegrees / steps
    for (i in 0 until steps) {
        val deg = currentSweepDegrees - (i * stepAngle)
        val rad = Math.toRadians(deg.toDouble())
        val alpha = (1f - (i.toFloat() / steps)).pow(1.5f) * 0.16f
        val x = center.x + (radius * sin(rad)).toFloat()
        val y = center.y - (radius * cos(rad)).toFloat()

        drawLine(
            color = AccentCyan.copy(alpha = alpha),
            start = center,
            end = Offset(x, y),
            strokeWidth = 3.5.dp.toPx()
        )
    }
}

private fun getCategoryColor(category: IncidentCategory): Color {
    return when (category) {
        IncidentCategory.FIRE_SMOKE -> AlertRed
        IncidentCategory.POLICE_ACTIVITY -> PoliceBlue
        IncidentCategory.MEDICAL_RESPONSE -> AlertRed
        IncidentCategory.VEHICLE_CRASH -> AlertAmber
        IncidentCategory.ROAD_HAZARD -> WarningYellow
        IncidentCategory.HAZARD_CONDITION -> AlertAmber
        IncidentCategory.WEATHER_HAZARD -> AccentCyan
        else -> PrimaryBlue
    }
}
