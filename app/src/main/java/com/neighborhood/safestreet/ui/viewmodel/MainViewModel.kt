package com.neighborhood.safestreet.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.data.repository.IncidentRepository
import com.neighborhood.safestreet.wear.WearableSyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AuthorityFilter(val label: String) {
    ALL("All Sources"),
    OFFICIAL_ONLY("Official Only"),
    COMMUNITY_ONLY("Community Only")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = IncidentRepository()
    val wearSyncManager = WearableSyncManager(
        context = application.applicationContext,
        coroutineScope = viewModelScope,
        onQuickReportReceived = { quickReport ->
            viewModelScope.launch {
                repository.submitCommunityReport(
                    category = quickReport.category,
                    latitude = quickReport.latitude,
                    longitude = quickReport.longitude,
                    note = quickReport.note
                )
            }
        }
    )

    val isLoading = repository.isLoading
    val isWearConnected = wearSyncManager.isWearConnected
    val connectedNodeName = wearSyncManager.connectedNodeName
    val lastSyncStatus = wearSyncManager.lastSyncStatus

    private val _selectedCategory = MutableStateFlow<IncidentCategory?>(null)
    val selectedCategory: StateFlow<IncidentCategory?> = _selectedCategory.asStateFlow()

    private val _selectedAuthority = MutableStateFlow(AuthorityFilter.ALL)
    val selectedAuthority: StateFlow<AuthorityFilter> = _selectedAuthority.asStateFlow()

    private val _isRadarView = MutableStateFlow(false)
    val isRadarView: StateFlow<Boolean> = _isRadarView.asStateFlow()

    private val _showReportDialog = MutableStateFlow(false)
    val showReportDialog: StateFlow<Boolean> = _showReportDialog.asStateFlow()

    private val _showWearDialog = MutableStateFlow(false)
    val showWearDialog: StateFlow<Boolean> = _showWearDialog.asStateFlow()

    private val _showSourcesDialog = MutableStateFlow(false)
    val showSourcesDialog: StateFlow<Boolean> = _showSourcesDialog.asStateFlow()

    val filteredIncidents: StateFlow<List<Incident>> = combine(
        repository.incidents,
        _selectedCategory,
        _selectedAuthority
    ) { all, category, authority ->
        all.filter { incident ->
            val matchesCategory = category == null || incident.category == category
            val matchesAuthority = when (authority) {
                AuthorityFilter.ALL -> true
                AuthorityFilter.OFFICIAL_ONLY -> incident.provenance == ProvenanceType.OFFICIAL_LIVE || incident.provenance == ProvenanceType.OFFICIAL_DELAYED
                AuthorityFilter.COMMUNITY_ONLY -> incident.provenance == ProvenanceType.COMMUNITY_UNVERIFIED || incident.provenance == ProvenanceType.COMMUNITY_CONFIRMED
            }
            matchesCategory && matchesAuthority
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            // Auto-sync top incidents to watch whenever data refreshes
            syncToWear()
        }
    }

    fun setCategoryFilter(category: IncidentCategory?) {
        _selectedCategory.value = category
    }

    fun setAuthorityFilter(authority: AuthorityFilter) {
        _selectedAuthority.value = authority
    }

    fun toggleViewMode() {
        _isRadarView.value = !_isRadarView.value
    }

    fun setShowReportDialog(show: Boolean) {
        _showReportDialog.value = show
    }

    fun setShowWearDialog(show: Boolean) {
        _showWearDialog.value = show
        if (show) {
            wearSyncManager.checkConnection()
        }
    }

    fun setShowSourcesDialog(show: Boolean) {
        _showSourcesDialog.value = show
    }

    fun submitCommunityReport(category: IncidentCategory, note: String, lat: Double, lon: Double) {
        viewModelScope.launch {
            val incident = repository.submitCommunityReport(category, lat, lon, note)
            syncToWear(highPriority = incident)
            setShowReportDialog(false)
        }
    }

    fun confirmIncident(incidentId: String) {
        viewModelScope.launch {
            repository.confirmReport(incidentId)
        }
    }

    fun flagIncident(incidentId: String, reason: String) {
        viewModelScope.launch {
            repository.flagReport(incidentId, reason)
        }
    }

    fun syncToWear(highPriority: Incident? = null) {
        val current = repository.incidents.value
        wearSyncManager.syncIncidentsToWatch(current, highPriority)
    }

    fun sendTestAlertToWear() {
        wearSyncManager.sendTestAlertToWatch()
    }

    override fun onCleared() {
        super.onCleared()
        wearSyncManager.cleanup()
    }
}
