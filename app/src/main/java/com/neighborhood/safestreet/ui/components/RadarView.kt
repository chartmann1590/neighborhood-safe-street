package com.neighborhood.safestreet.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarView(
    incidents: List<Incident>,
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
            .height(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = minOf(size.width, size.height) / 2 * 0.9f

            // Concentric range circles
            val rings = listOf(0.33f, 0.66f, 1.0f)
            for (fraction in rings) {
                drawCircle(
                    color = AccentCyan.copy(alpha = 0.15f),
                    radius = maxRadius * fraction,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Crosshairs
            drawLine(
                color = AccentCyan.copy(alpha = 0.15f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = AccentCyan.copy(alpha = 0.15f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Radar sweep line
            val rad = Math.toRadians(angle.toDouble())
            val endX = center.x + (maxRadius * cos(rad)).toFloat()
            val endY = center.y + (maxRadius * sin(rad)).toFloat()
            drawLine(
                brush = Brush.radialGradient(
                    colors = listOf(AccentCyan.copy(alpha = 0.8f), AccentCyan.copy(alpha = 0.0f)),
                    center = center,
                    radius = maxRadius
                ),
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 2.5.dp.toPx()
            )

            // Center user position dot
            drawCircle(color = AccentCyan, radius = 4.dp.toPx(), center = center)

            // Plot incidents pseudo-geographically relative to center
            incidents.take(12).forEachIndexed { index, incident ->
                val pointAngle = (index * 47.0 + 15.0) % 360.0
                val pointRad = Math.toRadians(pointAngle)
                val distFraction = 0.25f + ((index % 4) * 0.22f)
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
                        color = blipColor.copy(alpha = 0.3f),
                        radius = 8.dp.toPx() * pulseScale,
                        center = Offset(px, py),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Blip dot
                drawCircle(
                    color = blipColor,
                    radius = 5.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }

        // Overlay text
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
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
                text = "LIVE LOCAL RADAR (5 MI RADIUS)",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "${incidents.size} ACTIVE ALERTS",
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        )
    }
}
