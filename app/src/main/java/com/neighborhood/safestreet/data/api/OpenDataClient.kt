package com.neighborhood.safestreet.data.api

import android.util.Log
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class OpenDataClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun parseDate(dateStr: String?, fallback: Long = System.currentTimeMillis()): Long {
        if (dateStr.isNullOrBlank()) return fallback
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSSSSSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(dateStr)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
        return fallback
    }

    // 1. NOAA National Weather Service Active Emergency & Safety Alerts (Nationwide - all US cities/counties)
    suspend fun fetchNoaaAlerts(userLat: Double, userLon: Double): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            // First, check active alerts specifically for user's coordinate
            val pointUrl = "https://api.weather.gov/alerts/active?point=${String.format(Locale.US, "%.4f", userLat)},${String.format(Locale.US, "%.4f", userLon)}"
            val pointReq = Request.Builder()
                .url(pointUrl)
                .header("User-Agent", "SafeStreetApp (contact@safestreet.org)")
                .build()
            client.newCall(pointReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    parseNoaaFeatures(body, userLat, userLon, incidents)
                }
            }

            // Also query nationwide high-severity active alerts
            val activeUrl = "https://api.weather.gov/alerts/active?status=actual&severity=Extreme,Severe"
            val activeReq = Request.Builder()
                .url(activeUrl)
                .header("User-Agent", "SafeStreetApp (contact@safestreet.org)")
                .build()
            client.newCall(activeReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    parseNoaaFeatures(body, null, null, incidents)
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NOAA alerts: ${e.message}")
        }
        incidents.distinctBy { it.id }
    }

    private fun parseNoaaFeatures(
        jsonBody: String,
        fallbackLat: Double?,
        fallbackLon: Double?,
        outList: MutableList<Incident>
    ) {
        try {
            val root = JSONObject(jsonBody)
            val features = root.optJSONArray("features") ?: return
            val now = System.currentTimeMillis()
            for (i in 0 until minOf(features.length(), 25)) {
                val f = features.getJSONObject(i)
                val props = f.optJSONObject("properties") ?: continue
                val event = props.optString("event", "Weather Alert")
                val headline = props.optString("headline", event)
                val description = props.optString("description", "")
                val areaDesc = props.optString("areaDesc", "Affected Area")
                val severity = props.optString("severity", "Moderate")
                val sent = props.optString("sent")
                val occurredAt = parseDate(sent, now)

                var lat = fallbackLat
                var lon = fallbackLon

                val geom = f.optJSONObject("geometry")
                if (geom != null) {
                    val type = geom.optString("type")
                    if (type.equals("Polygon", ignoreCase = true)) {
                        val coords = geom.optJSONArray("coordinates")
                        if (coords != null && coords.length() > 0) {
                            val ring = coords.getJSONArray(0)
                            var sumLat = 0.0
                            var sumLon = 0.0
                            val count = ring.length()
                            if (count > 0) {
                                for (c in 0 until count) {
                                    val pt = ring.getJSONArray(c)
                                    sumLon += pt.optDouble(0)
                                    sumLat += pt.optDouble(1)
                                }
                                lat = sumLat / count
                                lon = sumLon / count
                            }
                        }
                    } else if (type.equals("Point", ignoreCase = true)) {
                        val coords = geom.optJSONArray("coordinates")
                        if (coords != null && coords.length() >= 2) {
                            lon = coords.optDouble(0)
                            lat = coords.optDouble(1)
                        }
                    }
                }

                if (lat == null || lon == null) continue

                val category = when {
                    event.contains("Fire", ignoreCase = true) || event.contains("Red Flag", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                    event.contains("Flood", ignoreCase = true) || event.contains("Tornado", ignoreCase = true) ||
                        event.contains("Storm", ignoreCase = true) || event.contains("Wind", ignoreCase = true) -> IncidentCategory.WEATHER_HAZARD
                    event.contains("Civil", ignoreCase = true) || event.contains("Evacuation", ignoreCase = true) -> IncidentCategory.POLICE_ACTIVITY
                    else -> IncidentCategory.HAZARD_CONDITION
                }

                val id = props.optString("id", "noaa_${System.currentTimeMillis()}_$i")
                outList.add(
                    Incident(
                        id = "noaa_${Math.abs(id.hashCode())}",
                        category = category,
                        subcategory = event,
                        title = headline.ifBlank { "$event ($areaDesc)" },
                        description = description.take(280).ifBlank { "Official NWS National Weather Service alert issued for $areaDesc." },
                        occurredAtEpochMs = occurredAt,
                        sourceUpdatedAtEpochMs = occurredAt,
                        receivedAtEpochMs = now,
                        latitude = lat,
                        longitude = lon,
                        displayAddress = areaDesc.take(60),
                        sourceId = "noaa_weather_gov",
                        agency = "National Weather Service (NOAA)",
                        provenance = ProvenanceType.OFFICIAL_LIVE,
                        licenseInfo = "US National Weather Service (Public Domain)",
                        isHighPriority = severity.equals("Extreme", ignoreCase = true) || severity.equals("Severe", ignoreCase = true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error parsing NOAA features: ${e.message}")
        }
    }

    // 2. New York State 511 & Public Safety Incidents (Schenectady, Albany, Capital District & NY Counties)
    suspend fun fetchNyStateIncidents(lat: Double, lon: Double, radiusMeters: Int = 80000): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.ny.gov/resource/ah74-pg4w.json?\$order=create_time%20DESC&\$where=within_circle(georeference,$lat,$lon,$radiusMeters)&\$limit=35"
            val request = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val eventType = obj.optString("event_type", "Incident").replaceFirstChar { it.uppercase() }
                        val facility = obj.optString("facility_name", "Highway")
                        val county = obj.optString("county", "NY")
                        val city = obj.optString("city", "")
                        val description = obj.optString("event_description", "$eventType on $facility in $county County")
                        val geomCoords = obj.optJSONObject("georeference")?.optJSONArray("coordinates")
                        val itemLat = obj.optString("latitude").toDoubleOrNull()
                            ?: (if (geomCoords != null && geomCoords.length() >= 2) geomCoords.optDouble(1) else null)
                            ?: lat
                        val itemLon = obj.optString("longitude").toDoubleOrNull()
                            ?: (if (geomCoords != null && geomCoords.length() >= 2) geomCoords.optDouble(0) else null)
                            ?: lon
                        val createTime = obj.optString("create_time")
                        val occurredAt = parseDate(createTime, now)
                        val org = obj.optString("responding_organization_id", "NYSDOT")

                        val category = when {
                            eventType.contains("crash", ignoreCase = true) || eventType.contains("accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            eventType.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            eventType.contains("hazard", ignoreCase = true) || eventType.contains("disabled", ignoreCase = true) -> IncidentCategory.ROAD_HAZARD
                            eventType.contains("police", ignoreCase = true) -> IncidentCategory.POLICE_ACTIVITY
                            else -> IncidentCategory.ROAD_HAZARD
                        }

                        val locDesc = if (city.isNotBlank()) "$facility ($city, $county Co)" else "$facility ($county Co)"
                        incidents.add(
                            Incident(
                                id = "nys511_${itemLat}_${itemLon}_${occurredAt}_$i",
                                category = category,
                                subcategory = eventType,
                                title = "$eventType: $facility",
                                description = description,
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = itemLat,
                                longitude = itemLon,
                                displayAddress = locDesc,
                                sourceId = "nys_511_events",
                                agency = "$org / NYS 511",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "New York State Open Data (Public)",
                                isHighPriority = category == IncidentCategory.VEHICLE_CRASH
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NY State 511: ${e.message}")
        }
        incidents
    }

    // 3. Chicago Police Department Crimes
    suspend fun fetchChicagoCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cityofchicago.org/resource/ijzp-q8t2.json?\$limit=25&\$order=date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id", "cpd_$i")
                        val primaryType = obj.optString("primary_type", "Police Incident")
                        val desc = obj.optString("description", "")
                        val block = obj.optString("block", "Chicago, IL")
                        val lat = obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        val dtStr = obj.optString("date")
                        val occurredAt = parseDate(dtStr, now)

                        val category = when {
                            primaryType.contains("ARSON", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            primaryType.contains("BATTERY", ignoreCase = true) || primaryType.contains("ASSAULT", ignoreCase = true) ||
                                primaryType.contains("ROBBERY", ignoreCase = true) || primaryType.contains("WEAPON", ignoreCase = true) -> IncidentCategory.POLICE_ACTIVITY
                            primaryType.contains("TRAFFIC", ignoreCase = true) || primaryType.contains("CRASH", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "cpd_$id",
                                category = category,
                                subcategory = primaryType,
                                title = "$primaryType ($block)",
                                description = "Chicago Police Department reported incident: $desc at $block.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = block,
                                sourceId = "chicago_police_crimes",
                                agency = "Chicago Police Department",
                                provenance = ProvenanceType.OFFICIAL_DELAYED,
                                licenseInfo = "City of Chicago Open Data",
                                isHighPriority = category == IncidentCategory.POLICE_ACTIVITY && (primaryType.contains("WEAPON") || primaryType.contains("ROBBERY"))
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Chicago Crimes: ${e.message}")
        }
        incidents
    }

    // 4. New York City NYPD Arrests & Law Enforcement Dispatches
    suspend fun fetchNycArrests(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cityofnewyork.us/resource/uip8-fykc.json?\$limit=25&\$order=arrest_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val key = obj.optString("arrest_key", "nypd_$i")
                        val desc = obj.optString("ofns_desc", "Police Enforcement")
                        val pdDesc = obj.optString("pd_desc", desc)
                        val boro = when (obj.optString("arrest_boro")) {
                            "M" -> "Manhattan, NY"
                            "B", "K" -> "Brooklyn, NY"
                            "Q" -> "Queens, NY"
                            "X" -> "Bronx, NY"
                            "S" -> "Staten Island, NY"
                            else -> "New York, NY"
                        }
                        val lat = obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        val dtStr = obj.optString("arrest_date")
                        val occurredAt = parseDate(dtStr, now)

                        incidents.add(
                            Incident(
                                id = "nypd_$key",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = pdDesc,
                                title = "$desc ($boro)",
                                description = "NYPD Law Enforcement Action: $pdDesc in $boro.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = boro,
                                sourceId = "nyc_nypd_arrests",
                                agency = "New York City Police Department",
                                provenance = ProvenanceType.OFFICIAL_DELAYED,
                                licenseInfo = "NYC Open Data (Public)",
                                isHighPriority = desc.contains("ASSAULT", ignoreCase = true) || desc.contains("ROBBERY", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NYC arrests: ${e.message}")
        }
        incidents
    }

    // 5. Los Angeles LAPD Crime & Safety Incidents
    suspend fun fetchLosAngelesCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.lacity.org/resource/2nrs-mtv8.json?\$limit=25&\$order=date_occ%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val dr = obj.optString("dr_no", "lapd_$i")
                        val desc = obj.optString("crm_cd_desc", "LAPD Crime Incident")
                        val loc = obj.optString("location", "Los Angeles, CA").trim()
                        val area = obj.optString("area_name", "Los Angeles")
                        val lat = obj.optDouble("lat", Double.NaN)
                        val lon = obj.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue
                        val dtStr = obj.optString("date_occ")
                        val occurredAt = parseDate(dtStr, now)

                        val category = when {
                            desc.contains("BURGLARY", ignoreCase = true) || desc.contains("THEFT", ignoreCase = true) -> IncidentCategory.POLICE_ACTIVITY
                            desc.contains("ASSAULT", ignoreCase = true) || desc.contains("BATTERY", ignoreCase = true) -> IncidentCategory.POLICE_ACTIVITY
                            desc.contains("TRAFFIC", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "lapd_$dr",
                                category = category,
                                subcategory = desc,
                                title = "$desc ($area)",
                                description = "LAPD incident report at $loc, $area area.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, $area",
                                sourceId = "la_lapd_crimes",
                                agency = "Los Angeles Police Department",
                                provenance = ProvenanceType.OFFICIAL_DELAYED,
                                licenseInfo = "City of Los Angeles Open Data"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching LA crimes: ${e.message}")
        }
        incidents
    }

    // 6. Montgomery County Police Department (MD / DC Metro Area)
    suspend fun fetchMontgomeryCountyIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.montgomerycountymd.gov/resource/icn6-v9z3.json?\$limit=25&\$order=date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("incident_id", "mcpd_$i")
                        val name = obj.optString("crimename3", obj.optString("crimename2", "Police Dispatch"))
                        val loc = obj.optString("location", "")
                        val city = obj.optString("city", "Montgomery County")
                        val lat = obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue
                        val dtStr = obj.optString("date")
                        val occurredAt = parseDate(dtStr, now)

                        incidents.add(
                            Incident(
                                id = "mcpd_$id",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = name,
                                title = "$name ($city)",
                                description = "Montgomery County Police dispatched report at $loc, $city.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = if (loc.isNotBlank()) "$loc, $city, MD" else "$city, MD",
                                sourceId = "mcpd_dispatch",
                                agency = "Montgomery County Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Montgomery County Open Data"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Montgomery County: ${e.message}")
        }
        incidents
    }

    // 7. Dallas Police Department Active Dispatched Calls
    suspend fun fetchDallasCalls(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://www.dallasopendata.com/resource/qv6i-rri7.json?\$limit=25&\$order=reporteddate%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("incidentnum", "dpd_$i")
                        val crime = obj.optString("offincident", obj.optString("nibrs_crime", "Police Activity"))
                        val addr = obj.optString("incident_address", "Dallas, TX")
                        val geoObj = obj.optJSONObject("geocoded_column")
                        val lat = geoObj?.optDouble("latitude", Double.NaN) ?: obj.optDouble("latitude", Double.NaN)
                        val lon = geoObj?.optDouble("longitude", Double.NaN) ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue
                        val dtStr = obj.optString("reporteddate")
                        val occurredAt = parseDate(dtStr, now)

                        incidents.add(
                            Incident(
                                id = "dpd_$id",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = crime,
                                title = "$crime ($addr)",
                                description = "Dallas Police dispatched incident at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Dallas, TX",
                                sourceId = "dallas_police_calls",
                                agency = "Dallas Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Dallas Open Data"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Dallas calls: ${e.message}")
        }
        incidents
    }

    // 8. Buffalo Police Department Crimes
    suspend fun fetchBuffaloCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.buffalony.gov/resource/d6g9-xbgu.json?\$limit=25&\$order=incident_datetime%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val caseNum = obj.optString("case_number", "bpd_$i")
                        val type = obj.optString("incident_type_primary", "Police Incident")
                        val addr = obj.optString("address_1", "Buffalo, NY")
                        val lat = obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue
                        val dtStr = obj.optString("incident_datetime")
                        val occurredAt = parseDate(dtStr, now)

                        incidents.add(
                            Incident(
                                id = "bpd_$caseNum",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = type,
                                title = "$type ($addr)",
                                description = "Buffalo Police report: $type at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Buffalo, NY",
                                sourceId = "buffalo_police_crimes",
                                agency = "Buffalo Police Department",
                                provenance = ProvenanceType.OFFICIAL_DELAYED,
                                licenseInfo = "City of Buffalo Open Data"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Buffalo crimes: ${e.message}")
        }
        incidents
    }

    // 9. Cincinnati Police Department Calls for Service
    suspend fun fetchCincinnatiCalls(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cincinnati-oh.gov/resource/gexm-h6bt.json?\$limit=25&\$order=create_time_incident%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val eventNum = obj.optString("event_number", "cpd_cin_$i")
                        val type = obj.optString("incident_type_desc", "Police Call")
                        val addr = obj.optString("address_x", "Cincinnati, OH")
                        val lat = obj.optDouble("latitude_x", Double.NaN)
                        val lon = obj.optDouble("longitude_x", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue
                        val dtStr = obj.optString("create_time_incident")
                        val occurredAt = parseDate(dtStr, now)

                        incidents.add(
                            Incident(
                                id = "cpd_cin_$eventNum",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = type,
                                title = "$type ($addr)",
                                description = "Cincinnati Police Department dispatched call at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Cincinnati, OH",
                                sourceId = "cincinnati_police_calls",
                                agency = "Cincinnati Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Cincinnati Open Data"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Cincinnati calls: ${e.message}")
        }
        incidents
    }

    // 10. USGS Real-Time Earthquakes & Geo-Hazards (Nationwide & Global)
    suspend fun fetchUsgsEarthquakes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until minOf(features.length(), 25)) {
                        val f = features.getJSONObject(i)
                        val props = f.optJSONObject("properties") ?: continue
                        val geom = f.optJSONObject("geometry") ?: continue
                        val coords = geom.optJSONArray("coordinates") ?: continue
                        val lon = coords.optDouble(0, Double.NaN)
                        val lat = coords.optDouble(1, Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val mag = props.optDouble("mag", 0.0)
                        val place = props.optString("place", "Seismic Event")
                        val time = props.optLong("time", now)
                        val id = f.optString("id", "usgs_$i")

                        incidents.add(
                            Incident(
                                id = "usgs_$id",
                                category = IncidentCategory.WEATHER_HAZARD,
                                subcategory = "Earthquake",
                                title = "M $mag Earthquake - $place",
                                description = "USGS recorded M $mag seismic event at $place.",
                                occurredAtEpochMs = time,
                                sourceUpdatedAtEpochMs = time,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = place,
                                sourceId = "usgs_earthquakes",
                                agency = "United States Geological Survey (USGS)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "USGS Public Domain Data",
                                isHighPriority = mag >= 4.0
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching USGS earthquakes: ${e.message}")
        }
        incidents
    }

    // 11. Seattle Real-Time Fire 911 Calls (Socrata API)
    suspend fun fetchSeattleFireIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.seattle.gov/resource/kzjm-xkqj.json?\$limit=25&\$order=datetime%20DESC"
            val request = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val array = JSONArray(body)
                    val now = System.currentTimeMillis()

                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val incidentNumber = item.optString("incident_number", "SFD-$i")
                        val type = item.optString("type", "Fire Response")
                        val address = item.optString("address", "Seattle, WA")
                        val lat = item.optDouble("latitude", 47.6062)
                        val lon = item.optDouble("longitude", -122.3321)
                        val dtStr = item.optString("datetime")
                        val occurredAt = parseDate(dtStr, now)

                        val category = when {
                            type.contains("Fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            type.contains("Aid", ignoreCase = true) || type.contains("Medic", ignoreCase = true) -> IncidentCategory.MEDICAL_RESPONSE
                            type.contains("Rescue", ignoreCase = true) || type.contains("Hazard", ignoreCase = true) -> IncidentCategory.HAZARD_CONDITION
                            type.contains("MVI", ignoreCase = true) || type.contains("Collision", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.FIRE_SMOKE
                        }

                        incidents.add(
                            Incident(
                                id = "sfd_$incidentNumber",
                                category = category,
                                subcategory = type,
                                title = "$type ($address)",
                                description = "Seattle Fire Department 911 dispatch response. Units dispatched to $address.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = address,
                                sourceId = "seattle_fire_realtime",
                                agency = "Seattle Fire Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Seattle Open Data (Public)",
                                isHighPriority = category == IncidentCategory.FIRE_SMOKE
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Seattle Fire: ${e.message}")
        }
        incidents
    }

    // 12. DataSF San Francisco Law Enforcement Dispatched Calls (Socrata API)
    suspend fun fetchSanFranciscoDispatch(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.sfgov.org/resource/wg3w-h783.json?\$limit=25&\$order=call_date%20DESC"
            val request = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val array = JSONArray(body)
                    val now = System.currentTimeMillis()

                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val callNumber = item.optString("cad_number", "SF-$i")
                        val callType = item.optString("original_crime_type_name", "Police Dispatch")
                        val address = item.optString("intersection_name", "San Francisco, CA")
                        val lat = item.optDouble("latitude", 37.7749)
                        val lon = item.optDouble("longitude", -122.4194)

                        val category = when {
                            callType.contains("Traffic", ignoreCase = true) || callType.contains("Accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            callType.contains("Hazard", ignoreCase = true) -> IncidentCategory.ROAD_HAZARD
                            callType.contains("Medical", ignoreCase = true) -> IncidentCategory.MEDICAL_RESPONSE
                            callType.contains("Fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "sfpd_$callNumber",
                                category = category,
                                subcategory = callType,
                                title = "$callType ($address)",
                                description = "San Francisco Police dispatched call for service at $address.",
                                occurredAtEpochMs = now - (i * 8 * 60 * 1000L),
                                sourceUpdatedAtEpochMs = now - (i * 8 * 60 * 1000L),
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = address,
                                sourceId = "sf_police_dispatch",
                                agency = "San Francisco Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City and County of San Francisco Open Data",
                                isHighPriority = category == IncidentCategory.POLICE_ACTIVITY
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching SF Dispatch: ${e.message}")
        }
        incidents
    }

    // 13. GDACS Disaster Alert & Coordination System GeoJSON (Global)
    suspend fun fetchGdacsHazards(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://www.gdacs.org/datareport/resources/GDACS_events.geojson"
            val request = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val geojson = JSONObject(body)
                    val features = geojson.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()

                    for (i in 0 until minOf(features.length(), 20)) {
                        val feature = features.getJSONObject(i)
                        val props = feature.optJSONObject("properties") ?: continue
                        val geom = feature.optJSONObject("geometry") ?: continue
                        val coords = geom.optJSONArray("coordinates") ?: continue

                        val lon = coords.optDouble(0, 0.0)
                        val lat = coords.optDouble(1, 0.0)
                        val eventName = props.optString("eventname", "Hazard Alert")
                        val eventType = props.optString("eventtype", "Hazard")
                        val alertLevel = props.optString("alertlevel", "Green")
                        val country = props.optString("country", "Global")

                        val category = when {
                            eventType.contains("WF", ignoreCase = true) || eventType.contains("Fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            eventType.contains("FL", ignoreCase = true) || eventType.contains("EQ", ignoreCase = true) || eventType.contains("TC", ignoreCase = true) -> IncidentCategory.WEATHER_HAZARD
                            else -> IncidentCategory.HAZARD_CONDITION
                        }

                        incidents.add(
                            Incident(
                                id = "gdacs_${props.optString("eventid", i.toString())}",
                                category = category,
                                subcategory = eventType,
                                title = "$eventName ($alertLevel Alert)",
                                description = "GDACS Global Hazard Alert: $eventType reported in $country.",
                                occurredAtEpochMs = now - (i * 30 * 60 * 1000L),
                                sourceUpdatedAtEpochMs = now - (i * 10 * 60 * 1000L),
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = country,
                                sourceId = "gdacs_global",
                                agency = "GDACS / UN-European Commission",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "GDACS / United Nations & European Commission Open Data",
                                isHighPriority = alertLevel.equals("Red", ignoreCase = true) || alertLevel.equals("Orange", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching GDACS: ${e.message}")
        }
        incidents
    }

    // 14. Austin Police Department Crime Reports (Austin, TX)
    suspend fun fetchAustinCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.austintexas.gov/resource/fdj4-gpfu.json?\$limit=30&\$order=occ_date_time%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("incident_report_number", "apd_$i")
                        val crimeType = obj.optString("crime_type", "Police Incident")
                        val address = obj.optString("address", "Austin, TX")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("occ_date_time", obj.optString("occ_date"))
                        val occurredAt = parseDate(dateStr, now)
                        val isViolent = crimeType.contains("assault", ignoreCase = true) || crimeType.contains("robbery", ignoreCase = true)
                        val category = when {
                            crimeType.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            crimeType.contains("crash", ignoreCase = true) || crimeType.contains("dwi", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "apd_$id",
                                category = category,
                                subcategory = crimeType,
                                title = crimeType,
                                description = "Austin Police Department reported $crimeType near $address.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = address.ifBlank { "Austin, TX" },
                                sourceId = "austin_pd",
                                agency = "Austin Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Austin Open Data (Public)",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Austin crimes: ${e.message}")
        }
        incidents
    }

    // 15. Philadelphia Police Department Crime Incidents (Philadelphia, PA)
    suspend fun fetchPhillyCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://phl.carto.com/api/v2/sql?q=SELECT%20cartodb_id,%20text_general_code,%20dispatch_date_time,%20location_block,%20lat,%20lng%20FROM%20incidents_part1_part2%20WHERE%20lat%20IS%20NOT%20NULL%20ORDER%20BY%20dispatch_date_time%20DESC%20LIMIT%2030"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val rows = root.optJSONArray("rows") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val id = row.optString("cartodb_id", "phl_$i")
                        val code = row.optString("text_general_code", "Police Incident")
                        val block = row.optString("location_block", "Philadelphia, PA")
                        val lat = row.optDouble("lat", Double.NaN)
                        val lon = row.optDouble("lng", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = row.optString("dispatch_date_time")
                        val occurredAt = parseDate(dateStr, now)
                        val isViolent = code.contains("homicide", ignoreCase = true) || code.contains("assault", ignoreCase = true) || code.contains("robbery", ignoreCase = true)
                        val category = when {
                            code.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            code.contains("crash", ignoreCase = true) || code.contains("accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "ppd_$id",
                                category = category,
                                subcategory = code,
                                title = code,
                                description = "Philadelphia Police incident: $code near $block.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = block.ifBlank { "Philadelphia, PA" },
                                sourceId = "philly_pd",
                                agency = "Philadelphia Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "OpenDataPhilly / Carto (Public)",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Philly crimes: ${e.message}")
        }
        incidents
    }

    // 16. New York City NYPD Crime Complaints (NYC 5 Boroughs)
    suspend fun fetchNypdComplaints(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cityofnewyork.us/resource/5uac-w243.json?\$limit=30&\$order=cmplnt_fr_dt%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("cmplnt_num", "nypd_c_$i")
                        val offense = obj.optString("ofns_desc", "Police Incident")
                        val boro = obj.optString("boro_nm", "New York, NY")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("cmplnt_fr_dt")
                        val occurredAt = parseDate(dateStr, now)
                        val isViolent = offense.contains("assault", ignoreCase = true) || offense.contains("robbery", ignoreCase = true) || offense.contains("murder", ignoreCase = true)
                        val category = when {
                            offense.contains("fire", ignoreCase = true) || offense.contains("arson", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "nypd_comp_$id",
                                category = category,
                                subcategory = offense,
                                title = offense,
                                description = "NYPD complaint reported in $boro: $offense.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$boro, NY",
                                sourceId = "nypd_complaints",
                                agency = "New York City Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "NYC Open Data (Public)",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NYPD complaints: ${e.message}")
        }
        incidents
    }

    // 17. Washington DC Metropolitan Police Incidents (DC Metro)
    suspend fun fetchDcPoliceIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://maps2.dcgis.dc.gov/dcgis/rest/services/FEEDS/MPD/FeatureServer/0/query?where=1%3D1&outFields=*&f=json&resultRecordCount=30"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val feat = features.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val ccn = attr.optString("CCN", "dc_$i")
                        val offense = attr.optString("OFFENSE", "Police Incident")
                        val block = attr.optString("BLOCK", "Washington, DC")
                        val reportDate = attr.optLong("REPORT_DAT", now)
                        val lat = attr.optDouble("LATITUDE", Double.NaN)
                        val lon = attr.optDouble("LONGITUDE", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val isViolent = offense.contains("assault", ignoreCase = true) || offense.contains("robbery", ignoreCase = true) || offense.contains("homicide", ignoreCase = true)
                        val category = when {
                            offense.contains("arson", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "dc_mpd_$ccn",
                                category = category,
                                subcategory = offense,
                                title = offense,
                                description = "DC Metropolitan Police dispatch: $offense near $block.",
                                occurredAtEpochMs = reportDate,
                                sourceUpdatedAtEpochMs = reportDate,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = block,
                                sourceId = "dc_mpd",
                                agency = "DC Metropolitan Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Open Data DC / ArcGIS REST (Public)",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching DC incidents: ${e.message}")
        }
        incidents
    }

    // 18. Kansas City Police Department Crime (Kansas City, MO)
    suspend fun fetchKansasCityCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.kcmo.org/resource/kbzx-7ehe.json?\$limit=30"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("report_no", "kc_$i")
                        val desc = obj.optString("description", "Police Incident")
                        val address = obj.optString("address", "Kansas City, MO")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("reported_date", obj.optString("from_date"))
                        val occurredAt = parseDate(dateStr, now)
                        val isViolent = desc.contains("assault", ignoreCase = true) || desc.contains("robbery", ignoreCase = true)
                        val category = when {
                            desc.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "kcpd_$id",
                                category = category,
                                subcategory = desc,
                                title = desc,
                                description = "KCPD Incident: $desc at $address.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = address,
                                sourceId = "kcpd_crime",
                                agency = "Kansas City Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Open Data KC (Public)",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching KCPD: ${e.message}")
        }
        incidents
    }

    // 19. NIFC US Wildland Fire & Fire Dispatches (ArcGIS REST - Nationwide All 50 States)
    suspend fun fetchNifcWildfires(userLat: Double? = null, userLon: Double? = null): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val spatialClause = if (userLat != null && userLon != null) {
                "&geometry=$userLon,$userLat&geometryType=esriGeometryPoint&inSR=4326&distance=100&units=esriSRUnit_StatuteMile&spatialRel=esriSpatialRelIntersects"
            } else ""
            val url = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_Current/FeatureServer/0/query?where=1%3D1$spatialClause&orderByFields=FireDiscoveryDateTime%20DESC&outFields=*&f=json&resultRecordCount=40"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val feat = features.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = attr.optDouble("InitialLatitude", Double.NaN).takeIf { !it.isNaN() }
                            ?: geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = attr.optDouble("InitialLongitude", Double.NaN).takeIf { !it.isNaN() }
                            ?: geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val name = attr.optString("IncidentName", "Active Wildfire")
                        val fireType = attr.optString("IncidentTypeCategory", "Wildfire")
                        val county = attr.optString("POOCounty", "")
                        val state = attr.optString("POOState", "").removePrefix("US-")
                        val discoveryTime = attr.optLong("FireDiscoveryDateTime", now)
                        val acres = attr.optDouble("IncidentSize", 0.0)
                        val contained = attr.optInt("PercentContained", -1)

                        val desc = "Active fire incident: $name. Acres: $acres. " +
                            (if (contained >= 0) "Containment: $contained%. " else "") +
                            "County: $county, State: $state."

                        incidents.add(
                            Incident(
                                id = "nifc_${attr.optLong("OBJECTID", i.toLong())}_$discoveryTime",
                                category = IncidentCategory.FIRE_SMOKE,
                                subcategory = fireType,
                                title = "Wildfire: $name",
                                description = desc,
                                occurredAtEpochMs = discoveryTime,
                                sourceUpdatedAtEpochMs = discoveryTime,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = if (county.isNotBlank()) "$county County, $state" else state,
                                sourceId = "nifc_wildfires",
                                agency = "National Interagency Fire Center (NIFC)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "US Interagency Wildland Fire (Public Domain)",
                                isHighPriority = acres >= 10.0 || fireType.contains("WF", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NIFC wildfires: ${e.message}")
        }
        incidents
    }

    // 20. New York City Motor Vehicle Collisions / Crashes (NYC 5 Boroughs)
    suspend fun fetchNycCollisions(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cityofnewyork.us/resource/h9gi-nx95.json?\$limit=30&\$order=crash_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("collision_id", "nyc_c_$i")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue

                        val onStreet = obj.optString("on_street_name", "").trim()
                        val offStreet = obj.optString("off_street_name", "").trim()
                        val factor = obj.optString("contributing_factor_vehicle_1", "Traffic Collision")
                        val injured = obj.optInt("number_of_persons_injured", 0)
                        val killed = obj.optInt("number_of_persons_killed", 0)

                        val dateStr = obj.optString("crash_date")
                        val occurredAt = parseDate(dateStr, now)

                        val streetDesc = when {
                            onStreet.isNotBlank() && offStreet.isNotBlank() -> "$onStreet & $offStreet"
                            onStreet.isNotBlank() -> onStreet
                            offStreet.isNotBlank() -> offStreet
                            else -> "New York, NY"
                        }

                        val title = if (injured > 0 || killed > 0) "Traffic Collision with Injuries" else "Motor Vehicle Collision"
                        val desc = "NYPD reported vehicle crash on $streetDesc. Factor: $factor. Injured: $injured, Fatalities: $killed."

                        incidents.add(
                            Incident(
                                id = "nyc_crash_$id",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = factor,
                                title = "$title: $streetDesc",
                                description = desc,
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$streetDesc, New York, NY",
                                sourceId = "nyc_collisions",
                                agency = "New York City Police Department (NYPD)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "NYC Open Data (Public)",
                                isHighPriority = injured > 0 || killed > 0
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NYC collisions: ${e.message}")
        }
        incidents
    }

    // 21. Chicago Traffic Crashes (Chicago, IL)
    suspend fun fetchChicagoCrashes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cityofchicago.org/resource/85ca-t3if.json?\$limit=30&\$order=crash_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("crash_record_id", "chi_cr_$i")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) continue

                        val crashType = obj.optString("first_crash_type", "Traffic Crash")
                        val streetNo = obj.optString("street_no", "")
                        val streetDir = obj.optString("street_direction", "")
                        val streetName = obj.optString("street_name", "")
                        val address = listOf(streetNo, streetDir, streetName).filter { it.isNotBlank() }.joinToString(" ")
                        val totalInjured = obj.optInt("injuries_total", 0)
                        val cause = obj.optString("prim_contributory_cause", "")

                        val dateStr = obj.optString("crash_date")
                        val occurredAt = parseDate(dateStr, now)

                        incidents.add(
                            Incident(
                                id = "chi_crash_$id",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = crashType,
                                title = "Crash: $crashType",
                                description = "Chicago Police reported crash at $address. Cause: $cause. Injured: $totalInjured.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = if (address.isNotBlank()) "$address, Chicago, IL" else "Chicago, IL",
                                sourceId = "chicago_crashes",
                                agency = "City of Chicago / CPD",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Chicago Open Data",
                                isHighPriority = totalInjured > 0
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Chicago crashes: ${e.message}")
        }
        incidents
    }

    // 22. Los Angeles County Sheriff's Department (LASD) Part 1 & 2 Crimes
    suspend fun fetchLasdCrimes(userLat: Double? = null, userLon: Double? = null): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val spatialClause = if (userLat != null && userLon != null) {
                "&geometry=$userLon,$userLat&geometryType=esriGeometryPoint&inSR=4326&distance=25&units=esriSRUnit_StatuteMile&spatialRel=esriSpatialRelIntersects"
            } else ""
            val url = "https://services.arcgis.com/RmCCgQtiZLDCtblq/arcgis/rest/services/PART_I_AND_II_CRIMES-YTD/FeatureServer/0/query?where=1%3D1$spatialClause&orderByFields=INCIDENT_DATE%20DESC&outFields=*&f=json&resultRecordCount=30"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val feat = features.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val lat = attr.optString("LATITUDE").toDoubleOrNull() ?: attr.optDouble("LATITUDE", Double.NaN)
                        val lon = attr.optString("LONGITUDE").toDoubleOrNull() ?: attr.optDouble("LONGITUDE", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val id = attr.optString("INCIDENT_ID", "lasd_$i")
                        val categoryStr = attr.optString("CATEGORY", "Police Incident")
                        val desc = attr.optString("STAT_DESC", categoryStr)
                        val address = attr.optString("ADDRESS", "Los Angeles County, CA")
                        val dateEpoch = attr.optLong("INCIDENT_DATE", now)

                        val isViolent = categoryStr.contains("ASSAULT", ignoreCase = true) ||
                            categoryStr.contains("ROBBERY", ignoreCase = true) ||
                            categoryStr.contains("HOMICIDE", ignoreCase = true)

                        val cat = when {
                            categoryStr.contains("ARSON", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            categoryStr.contains("VEHICLE", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "lasd_$id",
                                category = cat,
                                subcategory = categoryStr,
                                title = categoryStr,
                                description = "LASD Incident: $desc at $address.",
                                occurredAtEpochMs = dateEpoch,
                                sourceUpdatedAtEpochMs = dateEpoch,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = address,
                                sourceId = "lasd_crimes",
                                agency = "Los Angeles County Sheriff's Department (LASD)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "County of Los Angeles Open Data / ArcGIS",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching LASD crimes: ${e.message}")
        }
        incidents
    }

    // 23. San Francisco Fire Department Incidents (San Francisco, CA)
    suspend fun fetchSanFranciscoFire(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.sf.gov/resource/wr8u-xric.json?\$limit=30&\$order=alarm_dttm%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("incident_number", "sffd_$i")
                        val sit = obj.optString("primary_situation", "Fire Emergency")
                        val addr = obj.optString("address", "San Francisco, CA")
                        val coords = obj.optJSONObject("point")?.optJSONArray("coordinates")
                        val lon = coords?.optDouble(0, Double.NaN) ?: Double.NaN
                        val lat = coords?.optDouble(1, Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("alarm_dttm")
                        val occurredAt = parseDate(dateStr, now)

                        val cat = when {
                            sit.contains("medical", ignoreCase = true) -> IncidentCategory.MEDICAL_RESPONSE
                            sit.contains("crash", ignoreCase = true) || sit.contains("accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.FIRE_SMOKE
                        }

                        incidents.add(
                            Incident(
                                id = "sffd_$id",
                                category = cat,
                                subcategory = sit,
                                title = "SFFD: $sit",
                                description = "San Francisco Fire Department responding to $sit at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, San Francisco, CA",
                                sourceId = "sffd_incidents",
                                agency = "San Francisco Fire Department (SFFD)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City and County of San Francisco Open Data",
                                isHighPriority = cat == IncidentCategory.FIRE_SMOKE
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching SFFD: ${e.message}")
        }
        incidents
    }

    // 24. Seattle Police Department Crime Incident Reports (Seattle, WA)
    suspend fun fetchSeattlePoliceCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.seattle.gov/resource/tazs-3rd5.json?\$limit=30&\$order=report_date_time%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("report_number", "spd_$i")
                        val offense = obj.optString("offense_sub_category", obj.optString("offense_category", "Police Incident"))
                        val detail = obj.optString("nibrs_offense_code_description", offense)
                        val addr = obj.optString("block_address", "Seattle, WA")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("report_date_time")
                        val occurredAt = parseDate(dateStr, now)

                        val isViolent = offense.contains("ASSAULT", ignoreCase = true) ||
                            offense.contains("ROBBERY", ignoreCase = true) ||
                            offense.contains("HOMICIDE", ignoreCase = true)

                        val cat = when {
                            offense.contains("ARSON", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            offense.contains("COLLISION", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "spd_$id",
                                category = cat,
                                subcategory = offense,
                                title = offense,
                                description = "Seattle Police Department report: $detail at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = addr,
                                sourceId = "spd_crime",
                                agency = "Seattle Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Seattle Open Data",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching SPD crimes: ${e.message}")
        }
        incidents
    }

    // 25. Austin Real-Time Fire Incidents (Austin, TX)
    suspend fun fetchAustinFireIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.austintexas.gov/resource/wpu4-x69d.json?\$limit=30&\$order=published_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("traffic_report_id", "atx_f_$i")
                        val issue = obj.optString("issue_reported", "Fire Incident")
                        val addr = obj.optString("address", "Austin, TX")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("published_date")
                        val occurredAt = parseDate(dateStr, now)

                        val cat = when {
                            issue.contains("crash", ignoreCase = true) || issue.contains("rollover", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            issue.contains("medical", ignoreCase = true) -> IncidentCategory.MEDICAL_RESPONSE
                            else -> IncidentCategory.FIRE_SMOKE
                        }

                        incidents.add(
                            Incident(
                                id = "atx_fire_$id",
                                category = cat,
                                subcategory = issue,
                                title = "AFD: $issue",
                                description = "Austin Fire Department responding to $issue at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = addr,
                                sourceId = "austin_fire",
                                agency = "Austin Fire Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Austin Open Data",
                                isHighPriority = cat == IncidentCategory.FIRE_SMOKE
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Austin fire: ${e.message}")
        }
        incidents
    }

    // 26. Austin Real-Time Traffic Incidents (Austin, TX)
    suspend fun fetchAustinTrafficIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.austintexas.gov/resource/dx9v-zd7x.json?\$limit=30&\$order=published_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("traffic_report_id", "atx_tr_$i")
                        val issue = obj.optString("issue_reported", "Traffic Incident")
                        val addr = obj.optString("address", "Austin, TX")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("published_date")
                        val occurredAt = parseDate(dateStr, now)

                        val cat = when {
                            issue.contains("crash", ignoreCase = true) || issue.contains("accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            else -> IncidentCategory.ROAD_HAZARD
                        }

                        incidents.add(
                            Incident(
                                id = "atx_traffic_$id",
                                category = cat,
                                subcategory = issue,
                                title = "Traffic: $issue",
                                description = "Austin Transportation & Police report: $issue at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = addr,
                                sourceId = "austin_traffic",
                                agency = "City of Austin Transportation / APD",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Austin Open Data",
                                isHighPriority = issue.contains("urgent", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Austin traffic: ${e.message}")
        }
        incidents
    }

    // 27. New Orleans Police Department Calls for Service 2026 (New Orleans, LA)
    suspend fun fetchNewOrleansCalls(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.nola.gov/resource/es9j-6y5d.json?\$limit=30&\$order=timecreate%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("nopd_item", "nola_$i")
                        val typeText = obj.optString("typetext", obj.optString("type_", "Police Call"))
                        val addr = obj.optString("block_address", "New Orleans, LA")
                        val coords = obj.optJSONObject("location")?.optJSONArray("coordinates")
                        val lon = coords?.optDouble(0, Double.NaN) ?: Double.NaN
                        val lat = coords?.optDouble(1, Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("timecreate")
                        val occurredAt = parseDate(dateStr, now)

                        val cat = when {
                            typeText.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            typeText.contains("crash", ignoreCase = true) || typeText.contains("traffic", ignoreCase = true) || typeText.contains("accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            typeText.contains("medical", ignoreCase = true) || typeText.contains("ems", ignoreCase = true) -> IncidentCategory.MEDICAL_RESPONSE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        val isViolent = typeText.contains("assault", ignoreCase = true) ||
                            typeText.contains("robbery", ignoreCase = true) ||
                            typeText.contains("weapon", ignoreCase = true) ||
                            typeText.contains("shooting", ignoreCase = true)

                        incidents.add(
                            Incident(
                                id = "nopd_$id",
                                category = cat,
                                subcategory = typeText,
                                title = typeText,
                                description = "New Orleans Police Department CAD dispatch: $typeText at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, New Orleans, LA",
                                sourceId = "nopd_cfs_2026",
                                agency = "New Orleans Police Department (NOPD)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of New Orleans Open Data",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching NOLA calls: ${e.message}")
        }
        incidents
    }

    // 28. Baton Rouge Police & Sheriff Crime Incidents (Baton Rouge, LA)
    suspend fun fetchBatonRougeCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.brla.gov/resource/pbin-pcm7.json?\$limit=30&\$order=report_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("incident_number", "brla_$i")
                        val offense = obj.optString("offense_description", obj.optString("statute_description", "Police Incident"))
                        val street = obj.optString("street", "Baton Rouge, LA")
                        val city = obj.optString("city", "BATON ROUGE")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("report_date", obj.optString("charge_date"))
                        val occurredAt = parseDate(dateStr, now)

                        val isViolent = offense.contains("assault", ignoreCase = true) ||
                            offense.contains("robbery", ignoreCase = true) ||
                            offense.contains("battery", ignoreCase = true) ||
                            offense.contains("homicide", ignoreCase = true)

                        incidents.add(
                            Incident(
                                id = "brla_$id",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = offense,
                                title = offense,
                                description = "Baton Rouge Police report: $offense at $street, $city.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$street, $city, LA",
                                sourceId = "baton_rouge_police",
                                agency = "Baton Rouge Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Baton Rouge Open Data",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Baton Rouge: ${e.message}")
        }
        incidents
    }

    // 29. Montgomery County Crash Reporting Data (Maryland / DC Metro)
    suspend fun fetchMontgomeryCountyCrashes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.montgomerycountymd.gov/resource/bhju-22kf.json?\$limit=30&\$order=crash_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("report_number", "mcmd_cr_$i")
                        val road = obj.optString("road_name", "Montgomery County Road")
                        val injury = obj.optString("injury_severity", "NO INJURY")
                        val reportType = obj.optString("acrs_report_type", "Property Damage Crash")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("crash_date")
                        val occurredAt = parseDate(dateStr, now)

                        val hasInjury = !injury.contains("NO INJURY", ignoreCase = true)

                        incidents.add(
                            Incident(
                                id = "mcmd_crash_$id",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = reportType,
                                title = "Crash: $road",
                                description = "Montgomery County crash on $road. Severity: $injury.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$road, Montgomery County, MD",
                                sourceId = "montgomery_crashes",
                                agency = "Montgomery County Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Montgomery County Open Data",
                                isHighPriority = hasInjury
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Montgomery crashes: ${e.message}")
        }
        incidents
    }

    // 30. Prince George's County Police Crime Incidents (Maryland / DC Metro)
    suspend fun fetchPrinceGeorgesCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.princegeorgescountymd.gov/resource/xjru-idbe.json?\$limit=30&\$order=date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("incident_case_id", "pgpd_$i")
                        val incType = obj.optString("clearance_code_inc_type", "Police Incident")
                        val street = obj.optString("street_address", "Prince George's County, MD")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("date")
                        val occurredAt = parseDate(dateStr, now)

                        val cat = when {
                            incType.contains("ACCIDENT", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            incType.contains("FIRE", ignoreCase = true) || incType.contains("ARSON", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        val isViolent = incType.contains("ASSAULT", ignoreCase = true) ||
                            incType.contains("ROBBERY", ignoreCase = true) ||
                            incType.contains("HOMICIDE", ignoreCase = true) ||
                            incType.contains("WEAPON", ignoreCase = true)

                        incidents.add(
                            Incident(
                                id = "pgpd_$id",
                                category = cat,
                                subcategory = incType,
                                title = incType,
                                description = "Prince George's County Police report: $incType near $street.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$street, Prince George's County, MD",
                                sourceId = "pg_county_police",
                                agency = "Prince George's County Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Prince George's County Open Data",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching PG County: ${e.message}")
        }
        incidents
    }

    // 31. Cincinnati Police & Fire Department Calls for Service (Cincinnati, OH)
    suspend fun fetchCincinnatiFireCalls(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cincinnati-oh.gov/resource/qiik-bpks.json?\$limit=30&\$order=create_time_incident%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("event_number", "cpd_cfd_$i")
                        val agency = obj.optString("agency", "CFD")
                        val typeId = obj.optString("incident_type_id", "Emergency Call")
                        val disp = obj.optString("disposition_text", "")
                        val addr = obj.optString("address_x", "Cincinnati, OH")
                        val lat = obj.optString("latitude_x").toDoubleOrNull() ?: obj.optDouble("latitude_x", Double.NaN)
                        val lon = obj.optString("longitude_x").toDoubleOrNull() ?: obj.optDouble("longitude_x", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("create_time_incident")
                        val occurredAt = parseDate(dateStr, now)

                        val cat = when {
                            agency.equals("CFD", ignoreCase = true) && disp.contains("TRANSPORT", ignoreCase = true) -> IncidentCategory.MEDICAL_RESPONSE
                            agency.equals("CFD", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        incidents.add(
                            Incident(
                                id = "cincy_cfd_$id",
                                category = cat,
                                subcategory = typeId,
                                title = "$agency: $typeId",
                                description = "$agency CAD dispatch: $typeId at $addr. $disp",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Cincinnati, OH",
                                sourceId = "cincinnati_cfd_cpd",
                                agency = "Cincinnati Fire & Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Cincinnati Open Data",
                                isHighPriority = agency.equals("CFD", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Cincinnati CFD: ${e.message}")
        }
        incidents
    }

    // 32. Gainesville Police Department Crime Responses (Gainesville, FL)
    suspend fun fetchGainesvilleCrimes(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.cityofgainesville.org/resource/gvua-xt9q.json?\$limit=30&\$order=offense_date%20DESC"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id", "gpd_$i")
                        val narr = obj.optString("narrative", "Police Incident")
                        val addr = obj.optString("address", "Gainesville, FL")
                        val lat = obj.optString("latitude").toDoubleOrNull() ?: obj.optDouble("latitude", Double.NaN)
                        val lon = obj.optString("longitude").toDoubleOrNull() ?: obj.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val dateStr = obj.optString("offense_date", obj.optString("report_date"))
                        val occurredAt = parseDate(dateStr, now)

                        val isViolent = narr.contains("BATTERY", ignoreCase = true) ||
                            narr.contains("ASSAULT", ignoreCase = true) ||
                            narr.contains("ROBBERY", ignoreCase = true)

                        incidents.add(
                            Incident(
                                id = "gpd_$id",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = narr,
                                title = narr,
                                description = "Gainesville Police Department response: $narr at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Gainesville, FL",
                                sourceId = "gainesville_pd",
                                agency = "Gainesville Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Gainesville Open Data",
                                isHighPriority = isViolent
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Gainesville: ${e.message}")
        }
        incidents
    }

    // 33. Detroit Police Department RMS Crime Incidents (ArcGIS REST Spatial)
    suspend fun fetchDetroitCrimes(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services2.arcgis.com/RQcpPaCpMAXzUI5g/arcgis/rest/services/RMS_Crime_Incidents/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val offense = attr.optString("offense_de", attr.optString("offense_ca", "Crime Incident"))
                        val addr = attr.optString("address", "Detroit, MI")
                        val dateEpoch = attr.optLong("incident_date", attr.optLong("ibr_date", now))
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("FID", attr.optString("crime_id", "$i"))
                        incidents.add(
                            Incident(
                                id = "dpd_rms_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = offense,
                                title = offense.replaceFirstChar { it.uppercase() },
                                description = "Detroit Police RMS: $offense reported near $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Detroit, MI",
                                sourceId = "detroit_pd_rms",
                                agency = "Detroit Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Detroit Open Data",
                                isHighPriority = offense.contains("ASSAULT", true) || offense.contains("HOMICIDE", true) || offense.contains("ROBBERY", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Detroit: ${e.message}")
        }
        incidents
    }

    // 34. Cleveland Division of Police CAD Dispatches (ArcGIS REST Spatial)
    suspend fun fetchClevelandCad(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services3.arcgis.com/dty2kHktVXHrqO8i/arcgis/rest/services/CAD_Police/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val incidentType = attr.optString("IncidentTypeDescription", "Police Call")
                        val addr = attr.optString("address", "Cleveland, OH")
                        val dateEpoch = attr.optLong("IncidentDate", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("recordserialno", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "cleveland_cad_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = incidentType,
                                title = incidentType.replaceFirstChar { it.uppercase() },
                                description = "Cleveland Police CAD Dispatch: $incidentType dispatched at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Cleveland, OH",
                                sourceId = "cleveland_pd_cad",
                                agency = "Cleveland Division of Police",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "OpenDataCLE Public Safety",
                                isHighPriority = incidentType.contains("SHOTS", true) || incidentType.contains("ROBBERY", true) || incidentType.contains("ASSAULT", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Cleveland: ${e.message}")
        }
        incidents
    }

    // 35. Raleigh Police Department Crime Incidents (ArcGIS REST Spatial)
    suspend fun fetchRaleighCrimes(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services.arcgis.com/v400IkDOw1ad7Yad/arcgis/rest/services/Raleigh_Police_Incidents_Last_90_Days/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val desc = attr.optString("crime_description", attr.optString("crime_category", "Crime Incident"))
                        val addr = attr.optString("reported_block_address", "Raleigh, NC")
                        val dateEpoch = attr.optLong("reported_date", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("case_number", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "raleigh_pd_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = desc,
                                title = desc.replaceFirstChar { it.uppercase() },
                                description = "Raleigh Police Report: $desc at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Raleigh, NC",
                                sourceId = "raleigh_pd",
                                agency = "Raleigh Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Raleigh Open Data",
                                isHighPriority = desc.contains("WEAPON", true) || desc.contains("ASSAULT", true) || desc.contains("ROBBERY", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Raleigh: ${e.message}")
        }
        incidents
    }

    // 36. Metro Nashville Police Department Calls for Service (ArcGIS REST Spatial)
    suspend fun fetchNashvilleCalls(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services2.arcgis.com/HdTo6HJqh92wn4D8/arcgis/rest/services/Metro_Nashville_Police_Department_Calls_for_Service_view/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("Latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("Longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val tencode = attr.optString("Tencode_Description", attr.optString("Tencode", "Police Response"))
                        val street = attr.optString("Street_Name", "Nashville, TN")
                        val block = attr.optString("Block", "")
                        val addr = if (block.isNotBlank()) "$block $street" else street
                        val dateEpoch = attr.optLong("Call_Received", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("Event_Number", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "nashville_cfs_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = tencode,
                                title = "Code $tencode: MNPD Response",
                                description = "Metro Nashville Police CAD: $tencode response logged at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Nashville, TN",
                                sourceId = "nashville_cfs",
                                agency = "Metro Nashville Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Nashville Open Data",
                                isHighPriority = tencode.contains("SHOOT", true) || tencode.contains("FIGHT", true) || tencode.contains("ROBBERY", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Nashville: ${e.message}")
        }
        incidents
    }

    // 37. Charlotte-Mecklenburg Police Department (CMPD) Incidents (ArcGIS REST Spatial)
    suspend fun fetchCharlotteIncidents(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://gis.charlottenc.gov/arcgis/rest/services/CMPD/CMPDIncidents/MapServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("LATITUDE_PUBLIC", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("LONGITUDE_PUBLIC", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val desc = attr.optString("HIGHEST_NIBRS_DESCRIPTION", "Police Incident")
                        val addr = attr.optString("LOCATION", "Charlotte, NC")
                        val dateEpoch = attr.optLong("DATE_REPORTED", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("INCIDENT_REPORT_ID", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "cmpd_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = desc,
                                title = desc.replaceFirstChar { it.uppercase() },
                                description = "Charlotte-Mecklenburg Police: $desc reported at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Charlotte, NC",
                                sourceId = "cmpd_incident",
                                agency = "Charlotte-Mecklenburg Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Charlotte Open Data Portal",
                                isHighPriority = desc.contains("VIOLENT", true) || desc.contains("ASSAULT", true) || desc.contains("ROBBERY", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Charlotte: ${e.message}")
        }
        incidents
    }

    // 38. Columbus Division of Police Incident Reports (ArcGIS REST Spatial)
    suspend fun fetchColumbusIncidents(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services1.arcgis.com/9yy6msODkIBzkUXU/arcgis/rest/services/Police_Incident_Reports/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val addr = attr.optString("IncidentLocation", "Columbus, OH")
                        val dateEpoch = attr.optLong("OccurredOn", attr.optLong("ReportedOn", now))
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("OBJECTID", "$i")
                        incidents.add(
                            Incident(
                                id = "columbus_pd_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = "Police Incident",
                                title = "Police Response: Columbus",
                                description = "Columbus Division of Police: Incident logged at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Columbus, OH",
                                sourceId = "columbus_pd",
                                agency = "Columbus Division of Police",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Columbus Open Data",
                                isHighPriority = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Columbus: ${e.message}")
        }
        incidents
    }

    // 39. Denver Police Department Crime & Traffic Incidents (ArcGIS REST Spatial)
    suspend fun fetchDenverCrimes(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services.arcgis.com/aY6P1IjnU1hzETf0/arcgis/rest/services/Dec18Crime/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("GEO_LAT", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("GEO_LON", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val offense = attr.optString("OFFENSE_TYPE_ID", "incident").replace("-", " ")
                        val addr = attr.optString("INCIDENT_ADDRESS", "Denver, CO")
                        val dateEpoch = attr.optLong("FIRST_OCCURRENCE_DATE", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val isTraffic = attr.optInt("IS_TRAFFIC", 0) == 1
                        val cat = if (isTraffic) IncidentCategory.VEHICLE_CRASH else IncidentCategory.POLICE_ACTIVITY
                        val objId = attr.optString("INCIDENT_ID", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "denver_pd_$objId",
                                category = cat,
                                subcategory = offense,
                                title = offense.replaceFirstChar { it.uppercase() },
                                description = "Denver Police Department: $offense reported near $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Denver, CO",
                                sourceId = "denver_pd",
                                agency = "Denver Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Denver Open Data Catalog",
                                isHighPriority = offense.contains("assault", true) || offense.contains("homicide", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Denver: ${e.message}")
        }
        incidents
    }

    // 40. Tulsa Police Department Crime Incidents (ArcGIS REST Spatial)
    suspend fun fetchTulsaCrimes(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services5.arcgis.com/cuQhNeNcUrgLmYGD/arcgis/rest/services/Tulsa_Crime_Time_Display/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val crimeType = attr.optString("CRIME_TYPE", "Crime Incident")
                        val addr = attr.optString("BLOCK_ADDRESS", "Tulsa, OK")
                        val dateEpoch = attr.optLong("START_DATE", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("OBJECTID", "$i")
                        incidents.add(
                            Incident(
                                id = "tulsa_pd_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = crimeType,
                                title = crimeType.replaceFirstChar { it.uppercase() },
                                description = "Tulsa Police Report: $crimeType at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Tulsa, OK",
                                sourceId = "tulsa_pd",
                                agency = "Tulsa Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Tulsa Open Data",
                                isHighPriority = crimeType.contains("MURDER", true) || crimeType.contains("ROBBERY", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Tulsa: ${e.message}")
        }
        incidents
    }

    // 41. Omaha Police Department Incident Data (ArcGIS REST Spatial)
    suspend fun fetchOmahaIncidents(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services1.arcgis.com/tIBLyYZX96jUntYm/arcgis/rest/services/Omaha_Police_Incident_Data_(View)/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("LatBlock", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("LonBlock", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val category = attr.optString("NIBRSCategory", "Police Incident")
                        val addr = attr.optString("AddressBlock", "Omaha, NE")
                        val dateEpoch = attr.optLong("dteMaxSystem", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("OBJECTID", "$i")
                        incidents.add(
                            Incident(
                                id = "omaha_pd_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = category,
                                title = category.replaceFirstChar { it.uppercase() },
                                description = "Omaha Police: $category incident reported at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Omaha, NE",
                                sourceId = "omaha_pd",
                                agency = "Omaha Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Omaha Open Data",
                                isHighPriority = category.contains("Assault", true) || category.contains("Robbery", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Omaha: ${e.message}")
        }
        incidents
    }

    // 42. Tucson Police Department Calls for Service (ArcGIS REST Spatial)
    suspend fun fetchTucsonCalls(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://gis.tucsonaz.gov/arcgis/rest/services/PublicMaps/OpenData_PublicSafety/MapServer/41/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val nature = attr.optString("NatureCodeDesc", "Police Call")
                        val addr = attr.optString("ADDRESS_PUBLIC", "Tucson, AZ")
                        val dateEpoch = attr.optLong("ACTDATETIME", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("call_id", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "tucson_cad_$objId",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = nature,
                                title = nature.replaceFirstChar { it.uppercase() },
                                description = "Tucson Police 911 Call: $nature at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Tucson, AZ",
                                sourceId = "tucson_pd_cad",
                                agency = "Tucson Police Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Tucson Open Data",
                                isHighPriority = nature.contains("SHOTS", true) || nature.contains("WEAPON", true) || nature.contains("FIGHT", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Tucson: ${e.message}")
        }
        incidents
    }

    // 43. Minneapolis Fire Department 911 CAD & EMS Calls for Service (ArcGIS REST Spatial)
    suspend fun fetchMinneapolisFire(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services.arcgis.com/afSMGVsC7QlRK1kZ/arcgis/rest/services/MFD_Calls_For_Service/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val incidentType = attr.optString("incident_type", "Fire/EMS Incident")
                        val streetNum = attr.optString("street_number", "")
                        val streetName = attr.optString("street_name", "Minneapolis, MN")
                        val addr = if (streetNum.isNotBlank()) "$streetNum $streetName" else streetName
                        val dateEpoch = attr.optLong("alarm_date", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val isFire = incidentType.contains("FIRE", true) || incidentType.contains("SMOKE", true) || incidentType.contains("ALARM", true)
                        val cat = if (isFire) IncidentCategory.FIRE_SMOKE else IncidentCategory.MEDICAL_RESPONSE
                        val objId = attr.optString("incident_number", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "mfd_cfs_$objId",
                                category = cat,
                                subcategory = incidentType,
                                title = incidentType.replaceFirstChar { it.uppercase() },
                                description = "Minneapolis Fire Department 911 CAD: $incidentType dispatched at $addr.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$addr, Minneapolis, MN",
                                sourceId = "minneapolis_mfd_cad",
                                agency = "Minneapolis Fire Department",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "City of Minneapolis Open Data",
                                isHighPriority = isFire || incidentType.contains("BLS", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Minneapolis Fire: ${e.message}")
        }
        incidents
    }

    // 44. Ohio Statewide OHGO Real-Time Crashes, Hazards & Closures (ODOT ArcGIS REST Spatial)
    suspend fun fetchOhioStatewideIncidents(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services1.arcgis.com/AeX7yhXqx2UBQyL7/arcgis/rest/services/OHGOIncidents/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("Latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("Longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val desc = attr.optString("Description", "Active Traffic Crash/Hazard")
                        val loc = attr.optString("Location", "Ohio Highway")
                        val catName = attr.optString("Category", "Crash")
                        val dateEpoch = attr.optLong("LastUpdated", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("IncidentID", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "ohgo_$objId",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = catName,
                                title = "$catName: $loc",
                                description = "Ohio DOT OHGO: $desc",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, OH",
                                sourceId = "ohio_dot_ohgo",
                                agency = "Ohio Department of Transportation (OHGO)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "State of Ohio Open Data",
                                isHighPriority = catName.contains("Crash", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Ohio OHGO: ${e.message}")
        }
        incidents
    }

    // 45. Pennsylvania Statewide Travel Advisories & 911 CAD Police Activity (PennDOT ArcGIS REST Spatial)
    suspend fun fetchPennsylvaniaAdvisories(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://services9.arcgis.com/dEM0v3itWrU2mnNn/arcgis/rest/services/travel_advisories/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val desc = attr.optString("description", "Police Activity / Travel Disruption")
                        val eventType = attr.optString("event_type", "POLICE ACTIVITY")
                        val source = attr.optString("source", "PA CAD")
                        val dateEpoch = attr.optLong("start_date", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("event_id", attr.optString("OBJECTID", "$i"))
                        incidents.add(
                            Incident(
                                id = "pa_adv_$objId",
                                category = if (eventType.contains("POLICE", true)) IncidentCategory.POLICE_ACTIVITY else IncidentCategory.VEHICLE_CRASH,
                                subcategory = eventType,
                                title = "$eventType: $source",
                                description = "Pennsylvania Travel Advisories & 911 CAD: $desc",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "Pennsylvania, USA",
                                sourceId = "pa_travel_cad",
                                agency = "PennDOT & County 911 Dispatch ($source)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "Commonwealth of Pennsylvania Open Data",
                                isHighPriority = eventType.contains("POLICE", true) || desc.contains("ACCIDENT", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Pennsylvania: ${e.message}")
        }
        incidents
    }

    // 46. Maryland SHA CHART Real-Time Emergency Incidents & Traffic Operations (MDOT ArcGIS REST Spatial)
    suspend fun fetchMarylandChartIncidents(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://chartimap1.sha.maryland.gov/arcgis/rest/services/CHART/Incidents/MapServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("Latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("Longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val desc = attr.optString("Description", "Active Incident")
                        val incType = attr.optString("IncidentType", "Emergency Incident")
                        val county = attr.optString("County", "Maryland")
                        val dateEpoch = attr.optLong("Created", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("ID", attr.optString("rowid", "$i"))
                        incidents.add(
                            Incident(
                                id = "md_chart_$objId",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = incType,
                                title = "$incType ($county)",
                                description = "Maryland CHART Operations: $desc",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$county, MD",
                                sourceId = "maryland_sha_chart",
                                agency = "Maryland DOT State Highway Administration (CHART)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "State of Maryland Open Data",
                                isHighPriority = incType.contains("Collision", true) || incType.contains("Closure", true)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching Maryland CHART: ${e.message}")
        }
        incidents
    }

    // 47. Washington State WSDOT Emergency Road Alerts & Travel Incidents (WSDOT ArcGIS REST Spatial)
    suspend fun fetchWashingtonRoadAlerts(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val url = "https://data.wsdot.wa.gov/arcgis/rest/services/TravelInformation/TravelInfoRoadAlerts/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$userLon,$userLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=$radiusMiles&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=25"
            val req = Request.Builder().url(url).header("User-Agent", "SafeStreetApp/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features") ?: return@use
                    val now = System.currentTimeMillis()
                    for (i in 0 until features.length()) {
                        val f = features.getJSONObject(i)
                        val attr = f.optJSONObject("attributes") ?: continue
                        val geom = f.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                        val headline = attr.optString("HeadlineMessage", "Active Road Alert")
                        val catDesc = attr.optString("EventCategoryDescription", "Traffic Alert")
                        val road = attr.optString("Road", "Washington State")
                        val dateEpoch = attr.optLong("LastModifiedDate", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val objId = attr.optString("OBJECTID", "$i")
                        incidents.add(
                            Incident(
                                id = "wsdot_alert_$objId",
                                category = IncidentCategory.ROAD_HAZARD,
                                subcategory = catDesc,
                                title = "$catDesc: $road",
                                description = "WSDOT Emergency Alert: $headline",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$road, WA",
                                sourceId = "wsdot_travel_alerts",
                                agency = "Washington State Department of Transportation",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                licenseInfo = "State of Washington Open Data",
                                isHighPriority = attr.optInt("RoadClosedFlag", 0) == 1
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error fetching WSDOT alerts: ${e.message}")
        }
        incidents
    }

    // 48. Universal ArcGIS REST Spatial Discovery Engine (Discovers and queries county/city public safety GIS layers)
    suspend fun fetchArcGisDiscovery(userLat: Double, userLon: Double, radiusMiles: Double = 25.0): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            val searchUrl = "https://www.arcgis.com/sharing/rest/search?q=type:%22Feature%20Service%22%20AND%20(tags:%22crime%22%20OR%20tags:%22police%22%20OR%20tags:%22fire%22%20OR%20tags:%22traffic%22%20OR%20tags:%22cad%22)&f=json&num=8"
            val req = Request.Builder().url(searchUrl).header("User-Agent", "SafeStreetApp/2.0").build()
            val candidateUrls = mutableListOf<Pair<String, String>>() // url, title
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val results = root.optJSONArray("results") ?: return@use
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val serviceUrl = item.optString("url")
                        val title = item.optString("title", "Public Safety Layer")
                        if (serviceUrl.isNotBlank() && serviceUrl.startsWith("https://")) {
                            candidateUrls.add(Pair(serviceUrl, title))
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            for ((serviceUrl, title) in candidateUrls.take(4)) {
                try {
                    val queryUrl = "$serviceUrl/0/query?geometry=$userLon,$userLat&geometryType=esriGeometryPoint&inSR=4326&distance=$radiusMiles&units=esriSRUnit_StatuteMile&spatialRel=esriSpatialRelIntersects&outSR=4326&outFields=*&f=json&resultRecordCount=10"
                    val queryReq = Request.Builder().url(queryUrl).header("User-Agent", "SafeStreetApp/2.0").build()
                    client.newCall(queryReq).execute().use { qResp ->
                        if (qResp.isSuccessful) {
                            val qBody = qResp.body?.string() ?: return@use
                            val qRoot = JSONObject(qBody)
                            val features = qRoot.optJSONArray("features") ?: return@use
                            for (f in 0 until features.length()) {
                                val feat = features.getJSONObject(f)
                                val attr = feat.optJSONObject("attributes") ?: continue
                                val geom = feat.optJSONObject("geometry")

                                val lat = geom?.optDouble("y", Double.NaN)
                                    ?: attr.optString("LATITUDE").toDoubleOrNull()
                                    ?: attr.optDouble("LATITUDE", Double.NaN).takeIf { !it.isNaN() }
                                    ?: Double.NaN
                                val lon = geom?.optDouble("x", Double.NaN)
                                    ?: attr.optString("LONGITUDE").toDoubleOrNull()
                                    ?: attr.optDouble("LONGITUDE", Double.NaN).takeIf { !it.isNaN() }
                                    ?: Double.NaN

                                if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                                val dateEpoch = attr.optLong("DATE", attr.optLong("DATE_REPORTED", attr.optLong("IncidentDate", attr.optLong("alarm_date", attr.optLong("Created", now)))))
                                val occurredAt = if (dateEpoch > 0) dateEpoch else now
                                if (now - occurredAt !in -3600000L..86400000L) continue

                                val objId = attr.optLong("OBJECTID", f.toLong())
                                val catName = attr.optString("CATEGORY", attr.optString("INCIDENT_TYPE", attr.optString("OFFENSE", title)))
                                val addr = attr.optString("ADDRESS", attr.optString("LOCATION", "Local Jurisdiction"))

                                incidents.add(
                                    Incident(
                                        id = "arcgis_${Math.abs(serviceUrl.hashCode())}_$objId",
                                        category = IncidentCategory.POLICE_ACTIVITY,
                                        subcategory = catName,
                                        title = catName,
                                        description = "ArcGIS Spatial Feed ($title): $catName near $addr.",
                                        occurredAtEpochMs = occurredAt,
                                        sourceUpdatedAtEpochMs = occurredAt,
                                        receivedAtEpochMs = now,
                                        latitude = lat,
                                        longitude = lon,
                                        displayAddress = addr,
                                        sourceId = "arcgis_spatial_discovery",
                                        agency = title,
                                        provenance = ProvenanceType.OFFICIAL_LIVE,
                                        licenseInfo = "ArcGIS Open Data Public Service",
                                        isHighPriority = false
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error in ArcGIS discovery: ${e.message}")
        }
        incidents
    }

    // 34. Dynamic Regional Socrata Discovery Engine (Universal US Open Data Query across all 50 states)
    suspend fun fetchSocrataDiscovery(userLat: Double, userLon: Double, radiusMeters: Int = 40233): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            // Query Socrata ODN Discovery catalog for public safety, CAD, crime, and fire datasets nationwide
            val catalogUrl = "https://api.us.socrata.com/api/catalog/v1?q=police%20OR%20crime%20OR%20traffic%20OR%20fire%20OR%20%22calls%20for%20service%22&only=datasets&limit=25"
            val catReq = Request.Builder().url(catalogUrl).header("User-Agent", "SafeStreetApp/2.0").build()
            val candidateDatasets = mutableListOf<Triple<String, String, String>>() // domain, id, geoCol

            client.newCall(catReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val results = root.optJSONArray("results") ?: return@use
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val meta = item.optJSONObject("metadata") ?: continue
                        val res = item.optJSONObject("resource") ?: continue
                        val domain = meta.optString("domain")
                        val id = res.optString("id")
                        val cols = res.optString("columns_field_name").split(" ")
                        val types = res.optString("columns_datatype").split(" ")

                        var geoCol = ""
                        for (c in 0 until minOf(cols.size, types.size)) {
                            if (types[c].equals("Point", ignoreCase = true) || types[c].equals("Location", ignoreCase = true)) {
                                geoCol = cols[c]
                                break
                            }
                        }
                        if (domain.isNotBlank() && id.isNotBlank() && geoCol.isNotBlank()) {
                            candidateDatasets.add(Triple(domain, id, geoCol))
                        }
                    }
                }
            }

            // Query geospatial records within user radius for discovered portals
            val now = System.currentTimeMillis()
            for ((domain, datasetId, geoCol) in candidateDatasets.take(4)) {
                try {
                    val dataUrl = "https://$domain/resource/$datasetId.json?\$where=within_circle($geoCol,$userLat,$userLon,$radiusMeters)&\$limit=10"
                    val dataReq = Request.Builder().url(dataUrl).header("User-Agent", "SafeStreetApp/2.0").build()
                    client.newCall(dataReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val dataBody = resp.body?.string() ?: return@use
                            val records = JSONArray(dataBody)
                            for (r in 0 until records.length()) {
                                val obj = records.getJSONObject(r)
                                val lat = obj.optString("latitude").toDoubleOrNull()
                                    ?: obj.optJSONObject(geoCol)?.optJSONArray("coordinates")?.optDouble(1)
                                    ?: obj.optDouble("latitude", Double.NaN)
                                val lon = obj.optString("longitude").toDoubleOrNull()
                                    ?: obj.optJSONObject(geoCol)?.optJSONArray("coordinates")?.optDouble(0)
                                    ?: obj.optDouble("longitude", Double.NaN)
                                if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                                val title = obj.optString("event_type", obj.optString("crime_type", obj.optString("offense", obj.optString("description", "Public Safety Report"))))
                                incidents.add(
                                    Incident(
                                        id = "socrata_${datasetId}_${r}_$now",
                                        category = IncidentCategory.POLICE_ACTIVITY,
                                        subcategory = title,
                                        title = title.replaceFirstChar { it.uppercase() },
                                        description = "Authoritative municipal public safety record discovered via $domain.",
                                        occurredAtEpochMs = now,
                                        sourceUpdatedAtEpochMs = now,
                                        receivedAtEpochMs = now,
                                        latitude = lat,
                                        longitude = lon,
                                        displayAddress = domain,
                                        sourceId = "socrata_odn_$datasetId",
                                        agency = "$domain Open Data",
                                        provenance = ProvenanceType.OFFICIAL_LIVE,
                                        licenseInfo = "Socrata Open Data Network (Public)",
                                        isHighPriority = false
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("OpenDataClient", "Error in Socrata discovery: ${e.message}")
        }
        incidents
    }
}
