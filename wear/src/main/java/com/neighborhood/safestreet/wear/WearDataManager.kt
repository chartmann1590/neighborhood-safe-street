package com.neighborhood.safestreet.wear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.Wearable
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.common.models.WearQuickReport
import com.neighborhood.safestreet.common.models.WearSyncPacket
import com.neighborhood.safestreet.common.serialization.JsonHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

enum class WearConnectionMode {
    BLUETOOTH_PHONE,
    INTERNET_FALLBACK,
    SEARCHING
}

class WearDataManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Watch Location & Phone Synced Location State
    private val _watchLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val watchLocation: StateFlow<Pair<Double, Double>?> = _watchLocation.asStateFlow()

    private val _phoneLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val phoneLocation: StateFlow<Pair<Double, Double>?> = _phoneLocation.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    fun getEffectiveLocation(): Pair<Double, Double> {
        return _watchLocation.value ?: _phoneLocation.value ?: Pair(42.8249, -73.9270)
    }

    fun calculateDistanceMiles(targetLat: Double, targetLon: Double): Double {
        val (currentLat, currentLon) = getEffectiveLocation()
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(targetLat - currentLat)
        val dLon = Math.toRadians(targetLon - currentLon)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(currentLat)) * cos(Math.toRadians(targetLat)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMiles * c
    }

    private val _incidents = MutableStateFlow<List<Incident>>(emptyList())
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    private val _radiusMiles = MutableStateFlow(5.0)
    val radiusMiles: StateFlow<Double> = _radiusMiles.asStateFlow()

    fun setRadiusMiles(radius: Double) {
        _radiusMiles.value = radius
    }

    // Strictly incidents within the active radius
    val filteredIncidents: StateFlow<List<Incident>> = combine(
        _incidents,
        _radiusMiles,
        _watchLocation,
        _phoneLocation
    ) { list, radius, _, _ ->
        val now = System.currentTimeMillis()
        val maxAgeMs = 24 * 60 * 60 * 1000L
        list.filter {
            val ageMs = now - it.occurredAtEpochMs
            val isRecent24h = ageMs in -3600000L..maxAgeMs && !it.isExpired
            val withinRadius = calculateDistanceMiles(it.latitude, it.longitude) <= radius
            isRecent24h && withinRadius
        }
    }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _connectionMode = MutableStateFlow(WearConnectionMode.SEARCHING)
    val connectionMode: StateFlow<WearConnectionMode> = _connectionMode.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _latestAlert = MutableStateFlow<String?>(null)
    val latestAlert: StateFlow<String?> = _latestAlert.asStateFlow()

    private val _activeAlertIncident = MutableStateFlow<Incident?>(null)
    val activeAlertIncident: StateFlow<Incident?> = _activeAlertIncident.asStateFlow()

    fun checkAndFetchLocation() {
        try {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasPerm = fine || coarse
            _hasLocationPermission.value = hasPerm

            if (hasPerm) {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            _watchLocation.value = Pair(loc.latitude, loc.longitude)
                            Log.d("WearDataManager", "Watch GPS fix: ${loc.latitude}, ${loc.longitude}")
                        } else {
                            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                                if (lastLoc != null) {
                                    _watchLocation.value = Pair(lastLoc.latitude, lastLoc.longitude)
                                }
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w("WearDataManager", "Error fetching watch location: ${e.message}")
        }
    }

    fun dismissAlert() {
        _activeAlertIncident.value = null
        _latestAlert.value = null
    }

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.neighborhood.safestreet.wear.INCIDENTS_SYNC" -> {
                    val json = intent.getStringExtra("payload") ?: return
                    try {
                        val packet = JsonHelper.json.decodeFromString<WearSyncPacket>(json)
                        _incidents.value = packet.activeIncidents
                        _radiusMiles.value = packet.radiusMiles
                        _connectionMode.value = WearConnectionMode.BLUETOOTH_PHONE
                        _statusMessage.value = "Phone Linked (${packet.activeIncidents.size} alerts)"
                        val uLat = packet.userLatitude
                        val uLon = packet.userLongitude
                        if (uLat != null && uLon != null) {
                            _phoneLocation.value = Pair(uLat, uLon)
                        }
                        packet.highPriorityAlert?.let {
                            _activeAlertIncident.value = it
                            triggerHapticAlert(it.title)
                        }
                    } catch (e: Exception) {
                        Log.e("WearDataManager", "Error parsing Bluetooth sync packet: ${e.message}")
                    }
                }
                "com.neighborhood.safestreet.wear.HIGH_PRIORITY_ALERT" -> {
                    val json = intent.getStringExtra("payload") ?: return
                    try {
                        val incident = JsonHelper.json.decodeFromString<Incident>(json)
                        _activeAlertIncident.value = incident
                        _latestAlert.value = incident.title
                        triggerHapticAlert(incident.title)
                    } catch (e: Exception) {
                        _latestAlert.value = "Critical Alert"
                        triggerHapticAlert("Critical Public Safety Alert Received")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction("com.neighborhood.safestreet.wear.INCIDENTS_SYNC")
            addAction("com.neighborhood.safestreet.wear.HIGH_PRIORITY_ALERT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(syncReceiver, filter)
        }

        refreshData()
    }

    fun refreshData() {
        checkAndFetchLocation()
        coroutineScope.launch {
            // First check if phone Bluetooth node is connected
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    _connectionMode.value = WearConnectionMode.BLUETOOTH_PHONE
                    _statusMessage.value = "Bluetooth Link to ${nodes.first().displayName}"
                    return@launch
                }
            } catch (e: Exception) {
                Log.w("WearDataManager", "Node check error: ${e.message}")
            }

            // Fallback: Check Direct Internet Connection (Wi-Fi / LTE)
            if (isNetworkAvailable()) {
                _connectionMode.value = WearConnectionMode.INTERNET_FALLBACK
                _statusMessage.value = "Standalone Internet Fallback (Active)"
                fetchDirectOpenData()
            } else {
                _connectionMode.value = WearConnectionMode.SEARCHING
                _statusMessage.value = "Standby (Awaiting Sync)"
            }
        }
    }

    private suspend fun fetchDirectOpenData() = withContext(Dispatchers.IO) {
        val list = mutableListOf<Incident>()
        val (currentLat, currentLon) = getEffectiveLocation()
        val now = System.currentTimeMillis()

        try {
            // 1. Standalone Direct Fallback: Query NOAA National Weather Service Active Alerts for watch GPS position
            val noaaUrl = "https://api.weather.gov/alerts/active?point=${String.format(java.util.Locale.US, "%.4f", currentLat)},${String.format(java.util.Locale.US, "%.4f", currentLon)}"
            val reqNoaa = Request.Builder().url(noaaUrl).header("User-Agent", "SafeStreetWear/2.0 (contact@safestreet.org)").build()
            okHttpClient.newCall(reqNoaa).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features")
                    if (features != null) {
                        for (i in 0 until features.length()) {
                            val f = features.getJSONObject(i)
                            val props = f.optJSONObject("properties") ?: continue
                            val event = props.optString("event", "Public Safety Warning")
                            val area = props.optString("areaDesc", "Nearby Area")
                            list.add(
                                Incident(
                                    id = "wear_noaa_${System.currentTimeMillis()}_$i",
                                    category = IncidentCategory.WEATHER_HAZARD,
                                    subcategory = event,
                                    title = "$event ($area)",
                                    description = props.optString("headline", "Active emergency alert issued for $area."),
                                    occurredAtEpochMs = now,
                                    sourceUpdatedAtEpochMs = now,
                                    receivedAtEpochMs = now,
                                    latitude = currentLat,
                                    longitude = currentLon,
                                    displayAddress = area.take(40),
                                    sourceId = "wear_direct_noaa",
                                    agency = "NOAA / National Weather Service",
                                    provenance = ProvenanceType.OFFICIAL_LIVE,
                                    isHighPriority = true
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WearDataManager", "Direct NOAA fetch error: ${e.message}")
        }

        try {
            // 2. Query Regional Public Safety & 511 Events (spatial query around watch GPS position)
            val radiusMeters = (_radiusMiles.value * 1609.344).toInt().coerceAtLeast(8000)
            val nysUrl = "https://data.ny.gov/resource/ah74-pg4w.json?\$order=create_time%20DESC&\$where=within_circle(georeference,$currentLat,$currentLon,$radiusMeters)&\$limit=10"
            val reqNys = Request.Builder().url(nysUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqNys).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val eventType = obj.optString("event_type", "Incident").replaceFirstChar { it.uppercase() }
                        val facility = obj.optString("facility_name", "Local Road")
                        val county = obj.optString("county", "Area")
                        val geomCoords = obj.optJSONObject("georeference")?.optJSONArray("coordinates")
                        val itemLat = obj.optString("latitude").toDoubleOrNull()
                            ?: (if (geomCoords != null && geomCoords.length() >= 2) geomCoords.optDouble(1) else null)
                            ?: currentLat
                        val itemLon = obj.optString("longitude").toDoubleOrNull()
                            ?: (if (geomCoords != null && geomCoords.length() >= 2) geomCoords.optDouble(0) else null)
                            ?: currentLon
                        val org = obj.optString("responding_organization_id", "Public Safety")

                        val category = when {
                            eventType.contains("crash", ignoreCase = true) || eventType.contains("accident", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            eventType.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.ROAD_HAZARD
                        }

                        list.add(
                            Incident(
                                id = "wear_net_nys_${itemLat}_${itemLon}_$i",
                                category = category,
                                subcategory = eventType,
                                title = "$eventType: $facility",
                                description = "$eventType reported on $facility ($county County)",
                                occurredAtEpochMs = now - (i * 10 * 60 * 1000L),
                                sourceUpdatedAtEpochMs = now,
                                receivedAtEpochMs = now,
                                latitude = itemLat,
                                longitude = itemLon,
                                displayAddress = "$facility, $county Co",
                                sourceId = "wear_direct_nys",
                                agency = "$org / NYS 511",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = category == IncidentCategory.VEHICLE_CRASH
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WearDataManager", "Direct regional fetch error: ${e.message}")
        }

        try {
            // 3. USGS Real-Time Earthquakes (Nationwide)
            val usgsUrl = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson"
            val reqUsgs = Request.Builder().url(usgsUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqUsgs).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until minOf(feats.length(), 20)) {
                        val f = feats.getJSONObject(i)
                        val props = f.optJSONObject("properties") ?: continue
                        val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                        val lon = coords.optDouble(0, Double.NaN)
                        val lat = coords.optDouble(1, Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        val mag = props.optDouble("mag", 0.0)
                        val place = props.optString("place", "Seismic Event")
                        val time = props.optLong("time", now)
                        list.add(
                            Incident(
                                id = "wear_usgs_${f.optString("id", i.toString())}",
                                category = IncidentCategory.WEATHER_HAZARD,
                                subcategory = "Earthquake",
                                title = "M $mag Earthquake - $place",
                                description = "USGS recorded M $mag earthquake at $place.",
                                occurredAtEpochMs = time,
                                sourceUpdatedAtEpochMs = time,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = place,
                                sourceId = "wear_usgs",
                                agency = "USGS",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = mag >= 4.0
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 4. NIFC Wildfire Dispatches (Nationwide)
            val nifcUrl = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_Current/FeatureServer/0/query?where=1%3D1&geometry=$currentLon,$currentLat&geometryType=esriGeometryPoint&inSR=4326&distance=50&units=esriSRUnit_StatuteMile&spatialRel=esriSpatialRelIntersects&orderByFields=FireDiscoveryDateTime%20DESC&outFields=*&f=json&resultRecordCount=20"
            val reqNifc = Request.Builder().url(nifcUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqNifc).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = attr.optDouble("InitialLatitude", Double.NaN).takeIf { !it.isNaN() }
                            ?: geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = attr.optDouble("InitialLongitude", Double.NaN).takeIf { !it.isNaN() }
                            ?: geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue
                        val name = attr.optString("IncidentName", "Active Wildfire")
                        val county = attr.optString("POOCounty", "")
                        val state = attr.optString("POOState", "").removePrefix("US-")
                        val disc = attr.optLong("FireDiscoveryDateTime", now)
                        list.add(
                            Incident(
                                id = "wear_nifc_${attr.optLong("OBJECTID", i.toLong())}",
                                category = IncidentCategory.FIRE_SMOKE,
                                subcategory = "Wildfire",
                                title = "Wildfire: $name",
                                description = "Active wildfire dispatch in $county County, $state.",
                                occurredAtEpochMs = disc,
                                sourceUpdatedAtEpochMs = disc,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = if (county.isNotBlank()) "$county, $state" else state,
                                sourceId = "wear_nifc",
                                agency = "NIFC",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 5. Ohio OHGO Statewide Real-Time Crashes & Hazards
            val ohgoUrl = "https://services1.arcgis.com/AeX7yhXqx2UBQyL7/arcgis/rest/services/OHGOIncidents/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqOhgo = Request.Builder().url(ohgoUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqOhgo).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("Latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("Longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        val desc = attr.optString("Description", "Active Crash/Hazard")
                        val loc = attr.optString("Location", "Ohio")
                        val catName = attr.optString("Category", "Crash")
                        val dateEpoch = attr.optLong("LastUpdated", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue
                        list.add(
                            Incident(
                                id = "wear_ohgo_${attr.optString("IncidentID", i.toString())}",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = catName,
                                title = "$catName: $loc",
                                description = "Ohio DOT OHGO: $desc",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, OH",
                                sourceId = "wear_ohgo",
                                agency = "ODOT OHGO",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 6. Maryland SHA CHART Active Incidents
            val chartUrl = "https://chartimap1.sha.maryland.gov/arcgis/rest/services/CHART/Incidents/MapServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqChart = Request.Builder().url(chartUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqChart).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("Latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("Longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        val desc = attr.optString("Description", "Active Incident")
                        val incType = attr.optString("IncidentType", "Emergency Incident")
                        val county = attr.optString("County", "MD")
                        val dateEpoch = attr.optLong("Created", now)
                        val occurredAt = if (dateEpoch > 0) dateEpoch else now
                        if (now - occurredAt !in -3600000L..86400000L) continue
                        list.add(
                            Incident(
                                id = "wear_chart_${attr.optString("ID", i.toString())}",
                                category = IncidentCategory.POLICE_ACTIVITY,
                                subcategory = incType,
                                title = "$incType ($county)",
                                description = "MDOT CHART: $desc",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$county, MD",
                                sourceId = "wear_chart",
                                agency = "MDOT CHART",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 7. California Highway Patrol (CHP CAD Live Stream - Statewide California)
            val chpUrl = "http://media.chp.ca.gov/sa_xml/sa.xml"
            val reqChp = Request.Builder().url(chpUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqChp).execute().use { resp ->
                if (resp.isSuccessful) {
                    val stream = resp.body?.byteStream() ?: return@use
                    val factory = DocumentBuilderFactory.newInstance()
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(stream)
                    val logNodes = doc.getElementsByTagName("Log")
                    for (i in 0 until minOf(logNodes.length, 50)) {
                        val logEl = logNodes.item(i) as? Element ?: continue
                        val latLonRaw = logEl.getElementsByTagName("LATLON").item(0)?.textContent?.replace("\"", "")?.trim() ?: ""
                        if (!latLonRaw.contains(":")) continue
                        val parts = latLonRaw.split(":")
                        if (parts.size != 2) continue
                        val latVal = parts[0].toDoubleOrNull() ?: continue
                        val lonVal = parts[1].toDoubleOrNull() ?: continue
                        val lat = latVal / 1000000.0
                        val lon = -(Math.abs(lonVal) / 1000000.0)
                        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                        val dist = calculateDistanceMiles(lat, lon)
                        if (dist > _radiusMiles.value) continue

                        val logType = logEl.getElementsByTagName("LogType").item(0)?.textContent?.replace("\"", "")?.trim() ?: "Traffic Incident"
                        val loc = logEl.getElementsByTagName("Location").item(0)?.textContent?.replace("\"", "")?.trim() ?: "Highway"
                        val area = logEl.getElementsByTagName("Area").item(0)?.textContent?.replace("\"", "")?.trim() ?: "CA"
                        val timeStr = logEl.getElementsByTagName("LogTime").item(0)?.textContent?.replace("\"", "")?.trim() ?: ""

                        var occurredAt = now
                        val sdf = SimpleDateFormat("MMM dd yyyy h:mma", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("America/Los_Angeles")
                        }
                        try {
                            val parsed = sdf.parse(timeStr)
                            if (parsed != null) occurredAt = parsed.time
                        } catch (_: Exception) {}

                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val category = when {
                            logType.contains("Collision", ignoreCase = true) || logType.contains("Crash", ignoreCase = true) ||
                                logType.startsWith("1182") || logType.startsWith("1183") || logType.startsWith("1179") -> IncidentCategory.VEHICLE_CRASH
                            logType.contains("Fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            logType.contains("Hazard", ignoreCase = true) || logType.startsWith("1125") -> IncidentCategory.ROAD_HAZARD
                            else -> IncidentCategory.POLICE_ACTIVITY
                        }

                        val id = logEl.getAttribute("ID").ifBlank { "wear_chp_$i" }
                        list.add(
                            Incident(
                                id = "wear_chp_${Math.abs(id.hashCode())}",
                                category = category,
                                subcategory = logType,
                                title = "$logType: $loc",
                                description = "CHP CAD Dispatch ($area): $logType at $loc.",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, $area, CA",
                                sourceId = "wear_chp_cad",
                                agency = "CHP CAD",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = category == IncidentCategory.VEHICLE_CRASH || category == IncidentCategory.FIRE_SMOKE
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 8. North Carolina TIMS Incidents (Statewide NC)
            val ncUrl = "https://services.arcgis.com/NuWFvHYDMVmmxMeM/arcgis/rest/services/NCDOT_TIMSIncidentsByIncidentType/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqNc = Request.Builder().url(ncUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqNc).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("Latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("Longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val eventType = attr.optString("EventType", attr.optString("EventSubType", "Traffic Incident"))
                        val loc = attr.optString("Location", attr.optString("Road", "NC Highway"))
                        val county = attr.optString("CountyName", "NC")
                        val rawTime = attr.optLong("LastUpdateDateTime", now)
                        val occurredAt = if (rawTime > 0) rawTime else now
                        if (now - occurredAt !in -3600000L..86400000L) continue

                        val category = when {
                            eventType.contains("accident", ignoreCase = true) || eventType.contains("crash", ignoreCase = true) -> IncidentCategory.VEHICLE_CRASH
                            eventType.contains("fire", ignoreCase = true) -> IncidentCategory.FIRE_SMOKE
                            else -> IncidentCategory.ROAD_HAZARD
                        }

                        list.add(
                            Incident(
                                id = "wear_nc_${attr.optLong("OBJECTID", i.toLong())}",
                                category = category,
                                subcategory = eventType,
                                title = "$eventType: $loc",
                                description = "NCDOT TIMS: $eventType ($county Co)",
                                occurredAtEpochMs = occurredAt,
                                sourceUpdatedAtEpochMs = occurredAt,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, $county, NC",
                                sourceId = "wear_ncdot",
                                agency = "NCDOT DriveNC",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 9. Florida 511 Live Traffic Data (Statewide FL)
            val flUrl = "https://services8.arcgis.com/qhgIImgl4UmEEyAS/arcgis/rest/services/FL_511_Traffic_Data/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqFl = Request.Builder().url(flUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqFl).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("LATITUDE", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("LONGITUDE", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val incType = attr.optString("TYPE", "Traffic Incident")
                        val road = attr.optString("ROADWAY", "FL Highway")
                        val county = attr.optString("COUNTY", "FL")

                        list.add(
                            Incident(
                                id = "wear_fl_${attr.optLong("FID", i.toLong())}",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = incType,
                                title = "$incType: $road",
                                description = "FL511: $incType on $road ($county)",
                                occurredAtEpochMs = now,
                                sourceUpdatedAtEpochMs = now,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$road, $county, FL",
                                sourceId = "wear_fl511",
                                agency = "FDOT FL511",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 10. Utah UDOT Events (Statewide UT)
            val utahUrl = "https://services6.arcgis.com/KaHXE9OkiB9e63uE/arcgis/rest/services/UDOT_Events/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqUtah = Request.Builder().url(utahUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqUtah).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN()) continue

                        val eventType = attr.optString("EventType", "Road Incident")
                        val loc = attr.optString("Location", "Utah Highway")
                        val county = attr.optString("County", "UT")

                        list.add(
                            Incident(
                                id = "wear_utah_${attr.optLong("OBJECTID", i.toLong())}",
                                category = IncidentCategory.ROAD_HAZARD,
                                subcategory = eventType,
                                title = "$eventType: $loc",
                                description = "Utah DOT: $eventType on $loc ($county)",
                                occurredAtEpochMs = now,
                                sourceUpdatedAtEpochMs = now,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, $county, UT",
                                sourceId = "wear_udot",
                                agency = "UDOT Events",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = false
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 11. Northern Virginia / Loudoun County CAD Live Traffic & Incidents
            val novaUrl = "https://services1.arcgis.com/MxjRokvPm7bjslyR/arcgis/rest/services/CAD_CuurentTrafficIncidents_UD/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqNova = Request.Builder().url(novaUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqNova).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: attr.optDouble("latitude", Double.NaN)
                        val lon = geom?.optDouble("x", Double.NaN) ?: attr.optDouble("longitude", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue

                        val desc = attr.optString("description", "CAD Incident")
                        val loc = attr.optString("location", "Loudoun County")
                        val agency = attr.optString("agency_name", "Loudoun/Middleburg Police")

                        list.add(
                            Incident(
                                id = "wear_nova_${attr.optLong("OBJECTID", i.toLong())}",
                                category = IncidentCategory.VEHICLE_CRASH,
                                subcategory = desc,
                                title = "$desc: $loc",
                                description = "Live CAD: $desc at $loc ($agency)",
                                occurredAtEpochMs = now,
                                sourceUpdatedAtEpochMs = now,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$loc, Loudoun Co, VA",
                                sourceId = "wear_nova_cad",
                                agency = agency,
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            // 12. Colorado Road Closures & Hazard Incidents
            val coUrl = "https://services1.arcgis.com/0MSEUqKaxRlEPj5g/arcgis/rest/services/RoadClosures_public_df7039994f85494b9fa6d9bdb5383aee/FeatureServer/0/query?geometryType=esriGeometryPoint&geometry=$currentLon,$currentLat&inSR=4326&spatialRel=esriSpatialRelIntersects&distance=25&units=esriSRUnit_StatuteMile&outSR=4326&outFields=*&f=json&resultRecordCount=10"
            val reqCo = Request.Builder().url(coUrl).header("User-Agent", "SafeStreetWear/2.0").build()
            okHttpClient.newCall(reqCo).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val root = JSONObject(body)
                    val feats = root.optJSONArray("features") ?: return@use
                    for (i in 0 until feats.length()) {
                        val feat = feats.getJSONObject(i)
                        val attr = feat.optJSONObject("attributes") ?: continue
                        val geom = feat.optJSONObject("geometry")
                        val lat = geom?.optDouble("y", Double.NaN) ?: Double.NaN
                        val lon = geom?.optDouble("x", Double.NaN) ?: Double.NaN
                        if (lat.isNaN() || lon.isNaN()) continue

                        val reason = attr.optString("reason", "Hazard")
                        val street = attr.optString("street", "CO Route")

                        list.add(
                            Incident(
                                id = "wear_co_${attr.optLong("OBJECTID", i.toLong())}",
                                category = IncidentCategory.ROAD_HAZARD,
                                subcategory = reason,
                                title = "$reason: $street",
                                description = "CDOT Hazard: $reason on $street",
                                occurredAtEpochMs = now,
                                sourceUpdatedAtEpochMs = now,
                                receivedAtEpochMs = now,
                                latitude = lat,
                                longitude = lon,
                                displayAddress = "$street, CO",
                                sourceId = "wear_co_hazard",
                                agency = "CDOT",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = false
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Filter strictly by watch radius
        val withinRadius = list.filter { calculateDistanceMiles(it.latitude, it.longitude) <= _radiusMiles.value }
        _incidents.value = withinRadius
        if (withinRadius.isNotEmpty()) {
            _statusMessage.value = "Internet Fallback: ${withinRadius.size} alerts"
        } else {
            _statusMessage.value = "Area Clear within ${_radiusMiles.value.toInt()} mi"
        }
    }

    fun quickReportFromWrist(category: IncidentCategory) {
        coroutineScope.launch(Dispatchers.IO) {
            val loc = getEffectiveLocation()
            val coordStr = String.format(java.util.Locale.US, "%.4f, %.4f", loc.first, loc.second)
            val report = WearQuickReport(
                category = category,
                latitude = loc.first,
                longitude = loc.second,
                timestampEpochMs = System.currentTimeMillis(),
                note = "Quick observation logged directly from Wear OS wrist interface at [$coordStr]."
            )

            // Try sending to phone via Bluetooth Data Layer
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    val json = JsonHelper.json.encodeToString(report)
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, "/quick_report", json.toByteArray(Charsets.UTF_8)).await()
                    }
                    _statusMessage.value = "Reported ${category.displayName} to phone"
                    triggerHapticAlert("Report Sent")
                    return@launch
                }
            } catch (e: Exception) {
                Log.w("WearDataManager", "Failed to send to phone: ${e.message}")
            }

            // Fallback: Add to local watch list
            val now = System.currentTimeMillis()
            val newLocal = Incident(
                id = "wear_rep_${System.currentTimeMillis()}",
                category = category,
                title = "${category.displayName} (Wrist Report)",
                description = "Observed from smartwatch at [$coordStr]. Expires in 24 hours.",
                occurredAtEpochMs = now,
                sourceUpdatedAtEpochMs = now,
                receivedAtEpochMs = now,
                expiresAtEpochMs = now + (24 * 3600 * 1000L),
                latitude = loc.first,
                longitude = loc.second,
                displayAddress = "Location [$coordStr]",
                sourceId = "wear_quick_report",
                agency = "Wear OS Contributor",
                provenance = ProvenanceType.COMMUNITY_UNVERIFIED
            )

            val current = _incidents.value.toMutableList()
            current.add(0, newLocal)
            _incidents.value = current
            _statusMessage.value = "Logged ${category.displayName} locally"
            triggerHapticAlert("Report Logged")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun triggerHapticAlert(message: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
            _latestAlert.value = message
        } catch (e: Exception) {
            // ignore haptic error
        }
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(syncReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
