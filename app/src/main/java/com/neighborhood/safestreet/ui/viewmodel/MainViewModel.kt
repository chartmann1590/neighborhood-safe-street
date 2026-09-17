package com.neighborhood.safestreet.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.common.util.GeoUtils
import com.neighborhood.safestreet.data.alerts.AlertPreferences
import com.neighborhood.safestreet.data.alerts.AlertPreferencesRepository
import com.neighborhood.safestreet.data.alerts.SafetyAlertManager
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

enum class IncidentSortOrder(val label: String) {
    NEAREST("Nearest"),
    NEWEST("Newest")
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = IncidentRepository()
    val alertPreferencesRepo = AlertPreferencesRepository(application.applicationContext)

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

    val safetyAlertManager = SafetyAlertManager(
        context = application.applicationContext,
        preferencesRepository = alertPreferencesRepo,
        wearableSyncManager = wearSyncManager,
        coroutineScope = viewModelScope
    )

    val isLoading = repository.isLoading
    val isWearConnected = wearSyncManager.isWearConnected
    val connectedNodeName = wearSyncManager.connectedNodeName
    val lastSyncStatus = wearSyncManager.lastSyncStatus
    val alertPreferences = alertPreferencesRepo.preferences
    val activeInAppAlert = safetyAlertManager.activeInAppAlert

    // User Location (defaults to Seattle center until GPS coordinates resolve)
    private val _isLocationPermissionGranted = MutableStateFlow(false)
    val isLocationPermissionGranted: StateFlow<Boolean> = _isLocationPermissionGranted.asStateFlow()

    private val _userLatitude = MutableStateFlow(47.6062)
    val userLatitude: StateFlow<Double> = _userLatitude.asStateFlow()

    private val _userLongitude = MutableStateFlow(-122.3321)
    val userLongitude: StateFlow<Double> = _userLongitude.asStateFlow()

    private val _selectedCategory = MutableStateFlow<IncidentCategory?>(null)
    val selectedCategory: StateFlow<IncidentCategory?> = _selectedCategory.asStateFlow()

    private val _selectedAuthority = MutableStateFlow(AuthorityFilter.ALL)
    val selectedAuthority: StateFlow<AuthorityFilter> = _selectedAuthority.asStateFlow()

    private val _sortOrder = MutableStateFlow(IncidentSortOrder.NEAREST)
    val sortOrder: StateFlow<IncidentSortOrder> = _sortOrder.asStateFlow()

    private val _isRadarView = MutableStateFlow(true)
    val isRadarView: StateFlow<Boolean> = _isRadarView.asStateFlow()

    private val _showFullscreenMap = MutableStateFlow(false)
    val showFullscreenMap: StateFlow<Boolean> = _showFullscreenMap.asStateFlow()

    private val _focusedIncidentForMap = MutableStateFlow<Incident?>(null)
    val focusedIncidentForMap: StateFlow<Incident?> = _focusedIncidentForMap.asStateFlow()

    private val _showAlertSettings = MutableStateFlow(false)
    val showAlertSettings: StateFlow<Boolean> = _showAlertSettings.asStateFlow()

    private val _showReportDialog = MutableStateFlow(false)
    val showReportDialog: StateFlow<Boolean> = _showReportDialog.asStateFlow()

    private val _showWearDialog = MutableStateFlow(false)
    val showWearDialog: StateFlow<Boolean> = _showWearDialog.asStateFlow()

    private val _showSourcesDialog = MutableStateFlow(false)
    val showSourcesDialog: StateFlow<Boolean> = _showSourcesDialog.asStateFlow()

    val filteredIncidents: StateFlow<List<Incident>> = combine(
        repository.incidents,
        _selectedCategory,
        _selectedAuthority,
        _sortOrder,
        _userLatitude
    ) { all, category, authority, sort, userLat ->
        val userLon = _userLongitude.value
        val filtered = all.filter { incident ->
            val matchesCategory = category == null || incident.category == category
            val matchesAuthority = when (authority) {
                AuthorityFilter.ALL -> true
                AuthorityFilter.OFFICIAL_ONLY -> incident.provenance == ProvenanceType.OFFICIAL_LIVE || incident.provenance == ProvenanceType.OFFICIAL_DELAYED
                AuthorityFilter.COMMUNITY_ONLY -> incident.provenance == ProvenanceType.COMMUNITY_UNVERIFIED || incident.provenance == ProvenanceType.COMMUNITY_CONFIRMED
            }
            matchesCategory && matchesAuthority
        }

        when (sort) {
            IncidentSortOrder.NEAREST -> {
                filtered.sortedBy { inc ->
                    GeoUtils.calculateDistanceMiles(userLat, userLon, inc.latitude, inc.longitude)
                }
            }
            IncidentSortOrder.NEWEST -> {
                filtered.sortedByDescending { it.occurredAtEpochMs }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSortOrder(order: IncidentSortOrder) {
        _sortOrder.value = order
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
            val list = repository.incidents.value
            // Check incidents against alert preferences
            safetyAlertManager.evaluateIncidents(list, _userLatitude.value, _userLongitude.value)
            // Auto-sync top incidents to watch
            syncToWear()
        }
    }

    fun updateUserLocation(lat: Double, lon: Double) {
        _userLatitude.value = lat
        _userLongitude.value = lon
        safetyAlertManager.evaluateIncidents(repository.incidents.value, lat, lon)
        syncToWear()
    }

    fun openFullscreenMap(focusedIncident: Incident? = null) {
        _focusedIncidentForMap.value = focusedIncident
        _showFullscreenMap.value = true
    }

    fun closeFullscreenMap() {
        _showFullscreenMap.value = false
        _focusedIncidentForMap.value = null
    }

    fun setShowAlertSettings(show: Boolean) {
        _showAlertSettings.value = show
    }

    fun dismissInAppAlert() {
        safetyAlertManager.dismissInAppAlert()
    }

    fun sendTestSafetyAlert() {
        val realIncident = repository.incidents.value.firstOrNull()
        if (realIncident != null) {
            safetyAlertManager.triggerAlertOnRealIncident(listOf(realIncident), _userLatitude.value, _userLongitude.value)
            syncToWear(highPriority = realIncident)
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
        setShowReportDialog(false)
        viewModelScope.launch {
            val incident = repository.submitCommunityReport(category, lat, lon, note)
            syncToWear(highPriority = incident)
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

    fun setLocationPermissionGranted(granted: Boolean) {
        _isLocationPermissionGranted.value = granted
    }

    fun syncToWear(highPriority: Incident? = null) {
        val current = repository.incidents.value
        wearSyncManager.syncIncidentsToWatch(
            incidents = current,
            highPriority = highPriority,
            userLat = _userLatitude.value,
            userLon = _userLongitude.value
        )
    }

    fun sendTestAlertToWear() {
        wearSyncManager.sendTestAlertToWatch()
    }

    override fun onCleared() {
        super.onCleared()
        wearSyncManager.cleanup()
    }
}
