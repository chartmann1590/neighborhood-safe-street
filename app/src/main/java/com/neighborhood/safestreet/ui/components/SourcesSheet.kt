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
                name = "NOAA National Weather Service Alerts (Nationwide)",
                latency = "Live emergency point & polygon feed",
                license = "National Oceanic and Atmospheric Administration (Public Domain)"
            )
            SourceRow(
                name = "New York State 511 & Public Safety Events",
                latency = "Real-time / spatial query (Schenectady, Albany, NY)",
                license = "New York State Open Data & NYSDOT"
            )
            SourceRow(
                name = "Chicago Police Department (CPD) Incident Reports",
                latency = "Updated daily by Chicago Data Portal",
                license = "City of Chicago Open Data"
            )
            SourceRow(
                name = "New York City Police Department (NYPD) Arrests",
                latency = "Updated regularly by NYC OpenData",
                license = "City of New York Open Data"
            )
            SourceRow(
                name = "Los Angeles Police Department (LAPD) Incidents",
                latency = "Rolling daily updates by data.lacity.org",
                license = "City of Los Angeles Open Data"
            )
            SourceRow(
                name = "Dallas Police Department (DPD) Active Dispatched Calls",
                latency = "Real-time CAD dispatch records",
                license = "City of Dallas Open Data"
            )
            SourceRow(
                name = "Montgomery County Police Department (MCPD)",
                latency = "Real-time / daily dispatched calls",
                license = "Montgomery County, MD Open Data"
            )
            SourceRow(
                name = "Buffalo Police Department (BPD) Crime Reports",
                latency = "Regular municipal updates",
                license = "City of Buffalo Open Data"
            )
            SourceRow(
                name = "Cincinnati Police Department (CPD) Calls for Service",
                latency = "Real-time service calls",
                license = "City of Cincinnati Open Data"
            )
            SourceRow(
                name = "Seattle Fire Department 911 Calls (SFD CAD)",
                latency = "Refreshed every 5 minutes",
                license = "City of Seattle Open Data (Public Domain)"
            )
            SourceRow(
                name = "San Francisco Police Dispatch (SFPD)",
                latency = "Rolling 48h window, updated every 10 min",
                license = "City and County of San Francisco Open Data"
            )
            SourceRow(
                name = "USGS Real-Time Earthquakes & Geo-Hazards",
                latency = "Real-time seismic feeds",
                license = "US Geological Survey (USGS Public Domain)"
            )
            SourceRow(
                name = "GDACS Worldwide Disaster Alerts",
                latency = "Updated every 6 minutes",
                license = "United Nations & European Commission"
            )
            SourceRow(
                name = "Austin Police Department (APD) Crime Reports",
                latency = "Daily municipal updates",
                license = "City of Austin Open Data"
            )
            SourceRow(
                name = "Philadelphia Police Department (PPD) Crime Incidents",
                latency = "Real-time municipal dispatch records",
                license = "OpenDataPhilly / Carto"
            )
            SourceRow(
                name = "New York City Police Department (NYPD) Crime Complaints",
                latency = "Citywide 5-borough complaint records",
                license = "NYC OpenData"
            )
            SourceRow(
                name = "Washington DC Metropolitan Police Department (MPD)",
                latency = "Real-time ArcGIS REST feature service",
                license = "Open Data DC"
            )
            SourceRow(
                name = "Kansas City Police Department (KCPD) Incidents",
                latency = "Regular municipal updates",
                license = "Open Data KC"
            )
            SourceRow(
                name = "Dynamic Socrata Open Data Discovery Network",
                latency = "Automatic regional discovery & spatial querying across US municipal portals",
                license = "Socrata Open Data Network (ODN)"
            )
            SourceRow(
                name = "Community Safety Observations (Firestore)",
                latency = "Instant local submission with peer confirmation",
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
