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
            // Strictly real data from official municipal 911/CAD feeds and live community Firestore
            val officialSeattle = openDataClient.fetchSeattleFireIncidents()
            val officialSF = openDataClient.fetchSanFranciscoDispatch()
            val officialGdacs = openDataClient.fetchGdacsHazards()
            val community = communityRepository.fetchActiveCommunityReports()

            val combined = mutableListOf<Incident>()
            combined.addAll(officialSeattle)
            combined.addAll(officialSF)
            combined.addAll(officialGdacs)
            combined.addAll(community)

            // Deduplicate by real incident ID & sort by occurrence timestamp descending
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
}
