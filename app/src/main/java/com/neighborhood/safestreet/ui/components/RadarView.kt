package com.neighborhood.safestreet.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.ui.theme.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarView(
    incidents: List<Incident>,
    userLatitude: Double = 47.6062,
    userLongitude: Double = -122.3321,
    onExpandMap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radarSweep"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(290.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.5.dp, CardBorder, RoundedCornerShape(16.dp))
            .clickable { onExpandMap() }
    ) {
        // 1. FREE NO-API-KEY MAP (OpenStreetMap / OSMDroid) behind the radar
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(false)
                    isTilesScaledToDpi = true
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(13.8)
                    controller.setCenter(GeoPoint(userLatitude, userLongitude))
                }
            },
            update = { mapView ->
                mapView.controller.setCenter(GeoPoint(userLatitude, userLongitude))
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. High-Tech Dark Overlay so streets are visible under the radar sweep
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x8A0B1017))
        )

        // 3. Animated Radar Scope (Concentric rings, sweep beam, incident blips)
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = minOf(size.width, size.height) / 2 * 0.92f

            // Concentric range circles
            val rings = listOf(0.33f, 0.66f, 1.0f)
            for (fraction in rings) {
                drawCircle(
                    color = AccentCyan.copy(alpha = 0.30f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Crosshairs
            drawLine(
                color = AccentCyan.copy(alpha = 0.25f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = AccentCyan.copy(alpha = 0.25f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Radar sweep beam
            val rad = Math.toRadians(angle.toDouble())
            val endX = center.x + (maxRadius * cos(rad)).toFloat()
            val endY = center.y + (maxRadius * sin(rad)).toFloat()
            drawLine(
                brush = Brush.radialGradient(
                    colors = listOf(AccentCyan.copy(alpha = 0.85f), AccentCyan.copy(alpha = 0.0f)),
                    center = center,
                    radius = maxRadius
                ),
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx()
            )

            // Center user position dot
            drawCircle(color = AccentCyan, radius = 5.dp.toPx(), center = center)
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx() * pulseScale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Plot incidents relative to user center
            incidents.take(15).forEachIndexed { index, incident ->
                val pointAngle = (index * 51.0 + 20.0) % 360.0
                val pointRad = Math.toRadians(pointAngle)
                val distFraction = 0.22f + ((index % 4) * 0.24f)
                val r = maxRadius * distFraction
                val px = center.x + (r * cos(pointRad)).toFloat()
                val py = center.y + (r * sin(pointRad)).toFloat()

                val blipColor = when (incident.category) {
                    IncidentCategory.FIRE_SMOKE -> AlertRed
                    IncidentCategory.POLICE_ACTIVITY -> PoliceBlue
                    IncidentCategory.MEDICAL_RESPONSE -> AlertRed
                    IncidentCategory.VEHICLE_CRASH -> AlertAmber
                    IncidentCategory.ROAD_HAZARD -> WarningYellow
                    IncidentCategory.WEATHER_HAZARD -> AccentCyan
                    else -> PrimaryBlue
                }

                // Pulsing ring for high-priority or fresh incidents
                if (incident.isHighPriority) {
                    drawCircle(
                        color = blipColor.copy(alpha = 0.5f),
                        radius = 9.dp.toPx() * pulseScale,
                        center = Offset(px, py),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }

                // Blip dot
                drawCircle(
                    color = blipColor,
                    radius = 5.5.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }

        // 4. Header Badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkBackground.copy(alpha = 0.85f))
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
            Text(
                text = "LIVE LOCAL RADAR + OSM MAP",
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 5. Expand Fullscreen Map Badge (Clickable prompt)
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
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
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "FULLSCREEN MAP",
                color = DarkBackground,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
