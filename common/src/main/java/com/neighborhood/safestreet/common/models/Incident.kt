package com.neighborhood.safestreet.common.models

import kotlinx.serialization.Serializable

@Serializable
enum class IncidentCategory(val displayName: String, val code: String) {
    FIRE_SMOKE("Fire & Smoke", "fire_smoke"),
    MEDICAL_RESPONSE("Medical Emergency", "medical_response"),
    POLICE_ACTIVITY("Police Activity", "police_activity"),
    VEHICLE_CRASH("Vehicle Crash", "vehicle_crash"),
    ROAD_HAZARD("Road Hazard", "road_hazard"),
    HAZARD_CONDITION("Hazardous Condition", "hazard_condition"),
    WEATHER_HAZARD("Severe Weather / Hazard", "weather_hazard"),
    OTHER_SAFETY("Public Safety Activity", "other_safety");

    companion object {
        fun fromCode(code: String): IncidentCategory {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: OTHER_SAFETY
        }
    }
}

@Serializable
enum class ProvenanceType(val label: String, val description: String) {
    OFFICIAL_LIVE("OFFICIAL LIVE", "Direct live dispatch or CAD feed"),
    OFFICIAL_DELAYED("OFFICIAL DELAYED", "Official open data portal with reporting delay"),
    COMMUNITY_CONFIRMED("COMMUNITY CONFIRMED", "Observable event confirmed by multiple community members"),
    COMMUNITY_UNVERIFIED("COMMUNITY REPORT", "Single community observation (Expires in 24h)");
}

@Serializable
data class Incident(
    val id: String,
    val category: IncidentCategory,
    val subcategory: String? = null,
    val title: String,
    val description: String? = null,
    val occurredAtEpochMs: Long,
    val sourceUpdatedAtEpochMs: Long,
    val receivedAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val latitude: Double,
    val longitude: Double,
    val displayAddress: String,
    val sourceId: String,
    val agency: String,
    val provenance: ProvenanceType,
    val communityConfirmations: Int = 0,
    val communityFlags: Int = 0,
    val licenseInfo: String = "Open Data",
    val isHighPriority: Boolean = false
) {
    val isExpired: Boolean
        get() = expiresAtEpochMs != null && System.currentTimeMillis() > expiresAtEpochMs
}

@Serializable
data class WearQuickReport(
    val category: IncidentCategory,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMs: Long,
    val note: String = "Reported from Wear OS companion"
)

@Serializable
data class WearSyncPacket(
    val packetTimestamp: Long,
    val activeIncidents: List<Incident>,
    val highPriorityAlert: Incident? = null,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null
)
