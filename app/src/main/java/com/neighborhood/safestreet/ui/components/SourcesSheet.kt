package com.neighborhood.safestreet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neighborhood.safestreet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CardBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Sources, Transparency & Disclaimers",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Critical Legal Emergency Disclaimer Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AlertRed.copy(alpha = 0.15f))
                    .border(1.dp, AlertRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Emergency,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "NOT AN EMERGENCY SERVICE",
                            color = AlertRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "This application is an informational awareness aggregator. It is NOT connected to emergency dispatch. In any life-threatening situation or crime in progress, dial 911 (US/Canada), 112 (EU), 999 (UK) or your local emergency number immediately.",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "AUTHORITATIVE DATA SOURCES",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            SourceRow(
                name = "Seattle Fire Department 911 Calls",
                latency = "Refreshed every 5 minutes",
                license = "City of Seattle Open Data (Public Domain)"
            )
            SourceRow(
                name = "San Francisco Police Dispatch (DataSF)",
                latency = "Rolling 48h window, updated every 10 min",
                license = "Open Data Commons Open Database License"
            )
            SourceRow(
                name = "GDACS Worldwide Disaster Alerts",
                latency = "Updated every 6 minutes",
                license = "United Nations & European Commission"
            )
            SourceRow(
                name = "Community Observations (Firestore)",
                latency = "Instant local submission",
                license = "Strict 24h expiration • Moderated • Quantized coordinates"
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SourceRow(name: String, latency: String, license: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Latency: $latency", color = TextSecondary, fontSize = 11.sp)
        Text(text = "License: $license", color = TextMuted, fontSize = 11.sp)
    }
}
