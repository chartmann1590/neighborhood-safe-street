package com.neighborhood.safestreet.data.repository

import android.util.Log
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.data.api.OpenDataClient
import com.neighborhood.safestreet.data.firebase.FirestoreCommunityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    suspend fun refresh(userLat: Double = 42.8249, userLon: Double = -73.9270) = coroutineScope {
        _isLoading.value = true
        try {
            // Concurrently query official feeds across all major US municipalities, states & national agencies
            val jobs = listOf(
                async(Dispatchers.IO) { openDataClient.fetchNyStateIncidents(userLat, userLon) },
                async(Dispatchers.IO) { openDataClient.fetchNoaaAlerts(userLat, userLon) },
                async(Dispatchers.IO) { openDataClient.fetchUsgsEarthquakes() },
                async(Dispatchers.IO) { openDataClient.fetchNifcWildfires(userLat, userLon) },
                async(Dispatchers.IO) { openDataClient.fetchNycArrests() },
                async(Dispatchers.IO) { openDataClient.fetchNypdComplaints() },
                async(Dispatchers.IO) { openDataClient.fetchNycCollisions() },
                async(Dispatchers.IO) { openDataClient.fetchChicagoCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchChicagoCrashes() },
                async(Dispatchers.IO) { openDataClient.fetchSeattleFireIncidents() },
                async(Dispatchers.IO) { openDataClient.fetchSeattlePoliceCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchSanFranciscoDispatch() },
                async(Dispatchers.IO) { openDataClient.fetchSanFranciscoFire() },
                async(Dispatchers.IO) { openDataClient.fetchLosAngelesCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchLasdCrimes(userLat, userLon) },
                async(Dispatchers.IO) { openDataClient.fetchMontgomeryCountyIncidents() },
                async(Dispatchers.IO) { openDataClient.fetchMontgomeryCountyCrashes() },
                async(Dispatchers.IO) { openDataClient.fetchPrinceGeorgesCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchDallasCalls() },
                async(Dispatchers.IO) { openDataClient.fetchAustinCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchAustinFireIncidents() },
                async(Dispatchers.IO) { openDataClient.fetchAustinTrafficIncidents() },
                async(Dispatchers.IO) { openDataClient.fetchNewOrleansCalls() },
                async(Dispatchers.IO) { openDataClient.fetchBatonRougeCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchCincinnatiCalls() },
                async(Dispatchers.IO) { openDataClient.fetchCincinnatiFireCalls() },
                async(Dispatchers.IO) { openDataClient.fetchGainesvilleCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchBuffaloCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchPhillyCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchDcPoliceIncidents() },
                async(Dispatchers.IO) { openDataClient.fetchKansasCityCrimes() },
                async(Dispatchers.IO) { openDataClient.fetchArcGisDiscovery(userLat, userLon) },
                async(Dispatchers.IO) { openDataClient.fetchSocrataDiscovery(userLat, userLon) },
                async(Dispatchers.IO) { openDataClient.fetchGdacsHazards() },
                async(Dispatchers.IO) { communityRepository.fetchActiveCommunityReports() }
            )

            val combined = mutableListOf<Incident>()
            for (job in jobs) {
                try {
                    combined.addAll(job.await())
                } catch (e: Exception) {
                    Log.w("IncidentRepository", "Individual feed fetch error: ${e.message}")
                }
            }

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
