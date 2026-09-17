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
                latency = "Live emergency point & polygon feed (all 50 states & 3,143 counties)",
                license = "National Oceanic and Atmospheric Administration (Public Domain)"
            )
            SourceRow(
                name = "USGS Real-Time Earthquakes & Seismic Shaking",
                latency = "Live real-time seismic sensors across all US territories",
                license = "US Geological Survey (USGS Public Domain Data)"
            )
            SourceRow(
                name = "NIFC US Wildfire & Fire Dispatches (All 50 States)",
                latency = "Live active wildland fire dispatches & incident perimeters",
                license = "National Interagency Fire Center / USFS / BLM (ArcGIS REST)"
            )
            SourceRow(
                name = "New York State 511 & Public Safety Events",
                latency = "Real-time spatial queries (Schenectady, Albany, NY & statewide)",
                license = "New York State Open Data & NYSDOT"
            )
            SourceRow(
                name = "New York City Police Department (NYPD) Arrests",
                latency = "Updated daily by NYC OpenData (5 boroughs)",
                license = "City of New York Open Data"
            )
            SourceRow(
                name = "New York City Police Department (NYPD) Crime Complaints",
                latency = "Citywide 5-borough complaint records",
                license = "City of New York Open Data"
            )
            SourceRow(
                name = "New York City Motor Vehicle Collisions / Crashes",
                latency = "Citywide traffic collisions with injuries & fatalities",
                license = "NYC OpenData / NYPD Highway Patrol"
            )
            SourceRow(
                name = "Chicago Police Department (CPD) Incident Reports",
                latency = "Updated daily by Chicago Data Portal",
                license = "City of Chicago Open Data"
            )
            SourceRow(
                name = "Chicago Traffic Crashes & Incidents",
                latency = "Real-time CPD crash reports & severe injury dispatches",
                license = "City of Chicago Open Data"
            )
            SourceRow(
                name = "Los Angeles Police Department (LAPD) Incidents",
                latency = "Rolling daily updates across all LAPD divisions",
                license = "City of Los Angeles Open Data"
            )
            SourceRow(
                name = "Los Angeles County Sheriff's Department (LASD) Crimes",
                latency = "ArcGIS REST live query for Part 1 & 2 crime incidents",
                license = "County of Los Angeles Open Data"
            )
            SourceRow(
                name = "San Francisco Police Department (SFPD) Calls for Service",
                latency = "Rolling 48h CAD window, refreshed every 10 min",
                license = "City and County of San Francisco Open Data"
            )
            SourceRow(
                name = "San Francisco Fire Department (SFFD) Incidents",
                latency = "Fire, medical, and rescue 911 responses",
                license = "City and County of San Francisco Open Data"
            )
            SourceRow(
                name = "Seattle Fire Department 911 Calls (SFD CAD)",
                latency = "Refreshed every 5 minutes from live dispatch system",
                license = "City of Seattle Open Data (Public Domain)"
            )
            SourceRow(
                name = "Seattle Police Department (SPD) Crime Incident Reports",
                latency = "Regular daily municipal updates",
                license = "City of Seattle Open Data"
            )
            SourceRow(
                name = "Dallas Police Department (DPD) Active Dispatched Calls",
                latency = "Real-time CAD RMS incident records",
                license = "City of Dallas Open Data"
            )
            SourceRow(
                name = "Austin Police Department (APD) Crime Reports",
                latency = "Daily municipal updates across Austin metro",
                license = "City of Austin Open Data"
            )
            SourceRow(
                name = "Austin Fire Department (AFD) Real-Time Incidents",
                latency = "Active emergency fire and rollover CAD dispatches",
                license = "City of Austin Open Data"
            )
            SourceRow(
                name = "Austin Real-Time Traffic Incident Reports",
                latency = "Live crash and road blockage alerts from Austin Transportation",
                license = "City of Austin Open Data"
            )
            SourceRow(
                name = "New Orleans Police Department (NOPD) Calls for Service",
                latency = "Live CAD dispatches updated continuously",
                license = "City of New Orleans Open Data"
            )
            SourceRow(
                name = "Baton Rouge Police & Sheriff Crime Incidents",
                latency = "Daily municipal & parish law enforcement records",
                license = "City of Baton Rouge & East Baton Rouge Parish Open Data"
            )
            SourceRow(
                name = "Philadelphia Police Department (PPD) Crime Incidents",
                latency = "Real-time municipal dispatch records via Carto SQL",
                license = "OpenDataPhilly / Carto"
            )
            SourceRow(
                name = "Washington DC Metropolitan Police Department (MPD)",
                latency = "Real-time ArcGIS REST feature service dispatches",
                license = "Open Data DC"
            )
            SourceRow(
                name = "Kansas City Police Department (KCPD) Incidents",
                latency = "Regular municipal crime reports",
                license = "Open Data KC"
            )
            SourceRow(
                name = "Montgomery County Police Department (MCPD CAD)",
                latency = "Real-time / daily dispatched calls across MD/DC suburbs",
                license = "Montgomery County, MD Open Data"
            )
            SourceRow(
                name = "Montgomery County Crash Reporting",
                latency = "Crash reports with severity ratings across Montgomery County",
                license = "Montgomery County, MD Open Data"
            )
            SourceRow(
                name = "Prince George's County Police Department Incidents",
                latency = "Countywide crime & accident dispatch records",
                license = "Prince George's County, MD Open Data"
            )
            SourceRow(
                name = "Cincinnati Police & Fire Department (CPD & CFD CAD)",
                latency = "Real-time police, fire, and emergency medical calls",
                license = "City of Cincinnati Open Data"
            )
            SourceRow(
                name = "Gainesville Police Department Crime Responses",
                latency = "Regular municipal law enforcement updates",
                license = "City of Gainesville, FL Open Data"
            )
            SourceRow(
                name = "Buffalo Police Department (BPD) Crime Reports",
                latency = "Regular municipal updates for Western NY",
                license = "City of Buffalo Open Data"
            )
            SourceRow(
                name = "Detroit Police Department (DPD RMS Crime Incidents)",
                latency = "Active municipal crime RMS via ArcGIS REST spatial query",
                license = "City of Detroit Open Data"
            )
            SourceRow(
                name = "Cleveland Division of Police CAD 911 Dispatches",
                latency = "Real-time CAD 911 calls dispatched via ArcGIS REST",
                license = "OpenDataCLE Public Safety"
            )
            SourceRow(
                name = "Raleigh Police Department Crime Incidents",
                latency = "Rolling incident reports across Raleigh via ArcGIS REST",
                license = "City of Raleigh Open Data"
            )
            SourceRow(
                name = "Metro Nashville Police Department (MNPD CAD Calls)",
                latency = "Real-time CAD 911 calls for service in Nashville/Davidson County",
                license = "Nashville Open Data"
            )
            SourceRow(
                name = "Charlotte-Mecklenburg Police Department (CMPD Incidents)",
                latency = "Active police incident reports via Charlotte ArcGIS REST",
                license = "Charlotte Open Data Portal"
            )
            SourceRow(
                name = "Columbus Division of Police Incident Reports",
                latency = "Rolling police incident reports in Columbus, OH",
                license = "Columbus Open Data"
            )
            SourceRow(
                name = "Denver Police Department Crime & Traffic Incidents",
                latency = "Real-time spatial queries for crime & crashes in Denver",
                license = "Denver Open Data Catalog"
            )
            SourceRow(
                name = "Tulsa Police Department Crime Incidents",
                latency = "Active municipal law enforcement reports in Tulsa, OK",
                license = "City of Tulsa Open Data"
            )
            SourceRow(
                name = "Omaha Police Department Incident Data",
                latency = "Real-time law enforcement incident reporting in Omaha, NE",
                license = "City of Omaha Open Data"
            )
            SourceRow(
                name = "Tucson Police Department (TPD CAD 911 Calls)",
                latency = "Real-time 911 calls for service in Tucson, AZ",
                license = "City of Tucson Open Data"
            )
            SourceRow(
                name = "Minneapolis Fire Department 911 CAD & EMS Calls",
                latency = "Real-time fire and emergency medical dispatches in Minneapolis",
                license = "City of Minneapolis Open Data"
            )
            SourceRow(
                name = "Ohio Statewide (OHGO Real-Time Crashes & Hazards)",
                latency = "Real-time crashes, hazards, and closures statewide across Ohio",
                license = "Ohio Department of Transportation (ODOT)"
            )
            SourceRow(
                name = "Pennsylvania Statewide Travel Advisories & 911 CAD",
                latency = "Real-time police activity and emergency CAD dispatches in PA",
                license = "Commonwealth of Pennsylvania / County Dispatch"
            )
            SourceRow(
                name = "Maryland State Highway Administration (CHART Operations)",
                latency = "Real-time highway and emergency incident dispatches across MD",
                license = "Maryland DOT State Highway Administration"
            )
            SourceRow(
                name = "Washington State DOT (WSDOT Travel & Road Alerts)",
                latency = "Real-time emergency travel alerts, closures & hazards in WA",
                license = "Washington State Department of Transportation"
            )
            SourceRow(
                name = "California Highway Patrol (CHP) Statewide CAD Stream",
                latency = "Real-time live XML CAD dispatches across all California counties",
                license = "State of California / California Highway Patrol"
            )
            SourceRow(
                name = "Utah Department of Transportation (UDOT Events Statewide)",
                latency = "Real-time traffic closures, crashes & hazards across Utah",
                license = "Utah Department of Transportation (UDOT)"
            )
            SourceRow(
                name = "Kentucky Emergency Management (KYEM Incident Feed)",
                latency = "Real-time emergency response & road damage reports statewide in KY",
                license = "Commonwealth of Kentucky Public Data"
            )
            SourceRow(
                name = "North Carolina DOT (NCDOT TIMS / DriveNC Statewide)",
                latency = "Live traffic incident management system across North Carolina",
                license = "North Carolina Department of Transportation"
            )
            SourceRow(
                name = "Florida 511 (FL511 Statewide Live Traffic & Incidents)",
                latency = "Real-time crashes, obstructions & emergency events in FL",
                license = "Florida Department of Transportation (FDOT)"
            )
            SourceRow(
                name = "Tallahassee & Leon County 911 CAD Live Incidents",
                latency = "Real-time 911 CAD dispatches for Tallahassee and Leon County, FL",
                license = "City of Tallahassee & Leon County Public Safety"
            )
            SourceRow(
                name = "Iowa Department of Transportation (Iowa 511 Events)",
                latency = "Real-time travel advisories, closures & hazards across Iowa",
                license = "Iowa Department of Transportation (Iowa 511)"
            )
            SourceRow(
                name = "Las Vegas Metropolitan Police Department (LVMPD Crimes)",
                latency = "Weekly public crime dispatches in Las Vegas & Clark County, NV",
                license = "City of Las Vegas & Clark County Open Data"
            )
            SourceRow(
                name = "GDACS Worldwide Disaster Alerts",
                latency = "Updated every 6 minutes for global civil protection",
                license = "United Nations & European Commission"
            )
            SourceRow(
                name = "Universal Regional Socrata Discovery Network",
                latency = "Dynamic catalog search & server-side spatial query (1,200+ portals)",
                license = "Socrata Open Data Network (ODN)"
            )
            SourceRow(
                name = "Universal ArcGIS REST Public Safety Spatial Engine",
                latency = "Dynamic spatial queries on public safety feature servers nationwide",
                license = "ArcGIS Online / Esri Open Data"
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
