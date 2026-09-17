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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSeattleFireIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            // Seattle Real-Time Fire 911 Calls (Socrata API)
            val url = "https://data.seattle.gov/resource/kzjm-xkqj.json?\$limit=25&\$order=datetime%20DESC"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val array = JSONArray(body)
                    val now = System.currentTimeMillis()
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }

                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val incidentNumber = item.optString("incident_number", "SFD-$i")
                        val type = item.optString("type", "Fire Response")
                        val address = item.optString("address", "Seattle, WA")
                        val lat = item.optDouble("latitude", 47.6062)
                        val lon = item.optDouble("longitude", -122.3321)
                        val dtStr = item.optString("datetime")
                        val occurredAt = try {
                            sdf.parse(dtStr)?.time ?: now
                        } catch (e: Exception) {
                            now
                        }

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

    suspend fun fetchSanFranciscoDispatch(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            // DataSF Law Enforcement Dispatched Calls (Socrata API)
            val url = "https://data.sfgov.org/resource/wg3w-h783.json?\$limit=25&\$order=call_date%20DESC"
            val request = Request.Builder().url(url).build()
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

    suspend fun fetchGdacsHazards(): List<Incident> = withContext(Dispatchers.IO) {
        val incidents = mutableListOf<Incident>()
        try {
            // GDACS Disaster Alert & Coordination System GeoJSON
            val url = "https://www.gdacs.org/datareport/resources/GDACS_events.geojson"
            val request = Request.Builder().url(url).build()
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
}
