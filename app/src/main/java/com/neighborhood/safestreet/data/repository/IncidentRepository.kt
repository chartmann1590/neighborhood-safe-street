package com.neighborhood.safestreet.data.repository

import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.data.api.OpenDataClient
import com.neighborhood.safestreet.data.firebase.FirestoreCommunityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IncidentRepository(
    private val openDataClient: OpenDataClient = OpenDataClient(),
    private val communityRepository: FirestoreCommunityRepository = FirestoreCommunityRepository()
) {
    private val _incidents = MutableStateFlow<List<Incident>>(emptyList())
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(System.currentTimeMillis())
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    suspend fun refresh() {
        _isLoading.value = true
        try {
            val officialSeattle = openDataClient.fetchSeattleFireIncidents()
            val officialSF = openDataClient.fetchSanFranciscoDispatch()
            val officialGdacs = openDataClient.fetchGdacsHazards()
            val community = communityRepository.fetchActiveCommunityReports()

            val combined = mutableListOf<Incident>()
            combined.addAll(officialSeattle)
            combined.addAll(officialSF)
            combined.addAll(officialGdacs)
            combined.addAll(community)

            // If remote feeds were empty (e.g. offline emulator), provide initial seed data
            if (combined.isEmpty()) {
                combined.addAll(getSeedIncidents())
            }

            // Deduplicate & sort by timestamp descending
            val sorted = combined
                .distinctBy { it.id }
                .sortedByDescending { it.occurredAtEpochMs }

            _incidents.value = sorted
            _lastSyncTimestamp.value = System.currentTimeMillis()
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun submitCommunityReport(
        category: IncidentCategory,
        latitude: Double,
        longitude: Double,
        note: String
    ): Incident {
        val newIncident = communityRepository.submitReport(category, latitude, longitude, note)
        val current = _incidents.value.toMutableList()
        current.add(0, newIncident)
        _incidents.value = current.distinctBy { it.id }
        return newIncident
    }

    suspend fun confirmReport(reportId: String) {
        communityRepository.confirmReport(reportId)
        val updated = _incidents.value.map {
            if (it.id == reportId) {
                val newCount = it.communityConfirmations + 1
                it.copy(
                    communityConfirmations = newCount,
                    provenance = if (newCount >= 2) ProvenanceType.COMMUNITY_CONFIRMED else it.provenance
                )
            } else it
        }
        _incidents.value = updated
    }

    suspend fun flagReport(reportId: String, reason: String) {
        communityRepository.flagReport(reportId, reason)
        val updated = _incidents.value.mapNotNull {
            if (it.id == reportId) {
                val newFlags = it.communityFlags + 1
                if (newFlags >= 3) null else it.copy(communityFlags = newFlags)
            } else it
        }
        _incidents.value = updated
    }

    private fun getSeedIncidents(): List<Incident> {
        val now = System.currentTimeMillis()
        return listOf(
            Incident(
                id = "demo_sfd_1",
                category = IncidentCategory.FIRE_SMOKE,
                subcategory = "Structure Fire Response",
                title = "Structure Fire (2nd Alarm)",
                description = "Full 2nd alarm response dispatched. Ladder and engine companies responding to commercial building smoke.",
                occurredAtEpochMs = now - (6 * 60 * 1000L),
                sourceUpdatedAtEpochMs = now - (4 * 60 * 1000L),
                receivedAtEpochMs = now - (2 * 60 * 1000L),
                latitude = 47.6062,
                longitude = -122.3321,
                displayAddress = "4th Ave & Pine St, Seattle, WA",
                sourceId = "seattle_fire_realtime",
                agency = "Seattle Fire Department",
                provenance = ProvenanceType.OFFICIAL_LIVE,
                licenseInfo = "Seattle Open Data (Public)",
                isHighPriority = true
            ),
            Incident(
                id = "demo_sfpd_2",
                category = IncidentCategory.POLICE_ACTIVITY,
                subcategory = "Traffic Collision with Injury",
                title = "Major Vehicle Collision",
                description = "Multi-vehicle collision blocking lanes. Emergency medical and law enforcement on scene.",
                occurredAtEpochMs = now - (14 * 60 * 1000L),
                sourceUpdatedAtEpochMs = now - (10 * 60 * 1000L),
                receivedAtEpochMs = now - (8 * 60 * 1000L),
                latitude = 37.7749,
                longitude = -122.4194,
                displayAddress = "Market St & 5th St, San Francisco, CA",
                sourceId = "sf_police_dispatch",
                agency = "San Francisco Police Department",
                provenance = ProvenanceType.OFFICIAL_LIVE,
                licenseInfo = "DataSF Open Data",
                isHighPriority = true
            ),
            Incident(
                id = "demo_comm_3",
                category = IncidentCategory.ROAD_HAZARD,
                title = "Downed Power Line & Tree Limb",
                description = "Live wire down across sidewalk near corner bus stop. Utilities not yet on scene.",
                occurredAtEpochMs = now - (25 * 60 * 1000L),
                sourceUpdatedAtEpochMs = now - (25 * 60 * 1000L),
                receivedAtEpochMs = now - (25 * 60 * 1000L),
                expiresAtEpochMs = now + (23 * 60 * 60 * 1000L + 35 * 60 * 1000L),
                latitude = 47.6101,
                longitude = -122.3421,
                displayAddress = "Near 1st Ave & Bell St",
                sourceId = "community_reports",
                agency = "Community Observation",
                provenance = ProvenanceType.COMMUNITY_CONFIRMED,
                communityConfirmations = 4,
                communityFlags = 0,
                licenseInfo = "Community Submitted",
                isHighPriority = false
            ),
            Incident(
                id = "demo_gdacs_4",
                category = IncidentCategory.WEATHER_HAZARD,
                subcategory = "Wildfire Alert",
                title = "Wildfire Thermal Anomaly Detection",
                description = "NASA FIRMS / GDACS satellite infrared detection of active forest fire perimeter.",
                occurredAtEpochMs = now - (45 * 60 * 1000L),
                sourceUpdatedAtEpochMs = now - (30 * 60 * 1000L),
                receivedAtEpochMs = now - (15 * 60 * 1000L),
                latitude = 47.5000,
                longitude = -121.8000,
                displayAddress = "Cascades Foothills Area",
                sourceId = "gdacs_global",
                agency = "GDACS / NASA FIRMS",
                provenance = ProvenanceType.OFFICIAL_LIVE,
                licenseInfo = "UN-EC / NASA Earth Science Data",
                isHighPriority = true
            )
        )
    }
}
