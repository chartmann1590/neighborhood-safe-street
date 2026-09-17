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

    // 19. Dynamic Regional Socrata Discovery Engine (Universal US Open Data Query)
    suspend fun fetchSocrataDiscovery(userLat: Double, userLon: Double, radiusMeters: Int = 40233): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            // Query Socrata ODN Discovery catalog for public safety datasets
            val catalogUrl = "https://api.us.socrata.com/api/catalog/v1?categories=public%20safety&limit=4"
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
            for ((domain, datasetId, geoCol) in candidateDatasets.take(2)) {
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
                                if (lat.isNaN() || lon.isNaN()) continue

                                val title = obj.optString("event_type", obj.optString("crime_type", obj.optString("offense", obj.optString("description", "Public Safety Report"))))
                                incidents.add(
                                    Incident(
                                        id = "socrata_${datasetId}_${r}_$now",
                                        category = IncidentCategory.POLICE_ACTIVITY,
                                        subcategory = title,
                                        title = title.replaceFirstChar { it.uppercase() },
                                        description = "Discovered authoritative public safety record via $domain open data network.",
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
