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
import com.neighborhood.safestreet.ui.components.RadarRangeOption
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

    private val prefs = application.getSharedPreferences("safestreet_gps", android.content.Context.MODE_PRIVATE)

    // User Location (restored from persistent GPS cache or local coordinates)
    private val _isLocationPermissionGranted = MutableStateFlow(false)
    val isLocationPermissionGranted: StateFlow<Boolean> = _isLocationPermissionGranted.asStateFlow()

    private val _userLatitude = MutableStateFlow(
        prefs.getString("last_lat", null)?.toDoubleOrNull() ?: 42.8249
    )
    val userLatitude: StateFlow<Double> = _userLatitude.asStateFlow()

    private val _userLongitude = MutableStateFlow(
        prefs.getString("last_lon", null)?.toDoubleOrNull() ?: -73.9270
    )
    val userLongitude: StateFlow<Double> = _userLongitude.asStateFlow()

    // Radar & Feed Range Filter (1mi, 5mi, 25mi, Auto)
    private val _selectedRange = MutableStateFlow(RadarRangeOption.RANGE_5MI)
    val selectedRange: StateFlow<RadarRangeOption> = _selectedRange.asStateFlow()

    fun setRadarRange(option: RadarRangeOption) {
        _selectedRange.value = option
        syncToWear()
    }

    val effectiveRangeMiles: StateFlow<Double> = combine(
        _selectedRange,
        repository.incidents,
        _userLatitude,
        _userLongitude
    ) { range, all, userLat, userLon ->
        val now = System.currentTimeMillis()
        val maxAgeMs = 24 * 60 * 60 * 1000L
        val recentIncidents = all.filter { (now - it.occurredAtEpochMs) in -3600000L..maxAgeMs && !it.isExpired }
        if (range == RadarRangeOption.RANGE_AUTO) {
            val minDistance = recentIncidents.minOfOrNull {
                GeoUtils.calculateDistanceMiles(userLat, userLon, it.latitude, it.longitude)
            } ?: 5.0
            when {
                minDistance <= 1.0 -> 1.0
                minDistance <= 3.0 -> 3.0
                minDistance <= 5.0 -> 5.0
                minDistance <= 10.0 -> 10.0
                minDistance <= 25.0 -> 25.0
                else -> 50.0
            }
        } else {
            range.miles
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 5.0)

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

    // Strictly reports within the active radius, within the last 24 hours, matching category & authority filters
    val filteredIncidents: StateFlow<List<Incident>> = combine(
        repository.incidents,
        _selectedCategory,
        _selectedAuthority,
        _sortOrder,
        effectiveRangeMiles
    ) { all, category, authority, sort, maxRadius ->
        val userLat = _userLatitude.value
        val userLon = _userLongitude.value
        val now = System.currentTimeMillis()
        val maxAgeMs = 24 * 60 * 60 * 1000L // Strictly last 24 hours
        val filtered = all.filter { incident ->
            val ageMs = now - incident.occurredAtEpochMs
            val isWithin24Hours = ageMs in -3600000L..maxAgeMs && !incident.isExpired
            val dist = GeoUtils.calculateDistanceMiles(userLat, userLon, incident.latitude, incident.longitude)
            val withinRadius = dist <= maxRadius
            val matchesCategory = category == null || incident.category == category
            val matchesAuthority = when (authority) {
                AuthorityFilter.ALL -> true
                AuthorityFilter.OFFICIAL_ONLY -> incident.provenance == ProvenanceType.OFFICIAL_LIVE || incident.provenance == ProvenanceType.OFFICIAL_DELAYED
                AuthorityFilter.COMMUNITY_ONLY -> incident.provenance == ProvenanceType.COMMUNITY_UNVERIFIED || incident.provenance == ProvenanceType.COMMUNITY_CONFIRMED
            }
            isWithin24Hours && withinRadius && matchesCategory && matchesAuthority
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
            val lat = _userLatitude.value
            val lon = _userLongitude.value
            repository.refresh(lat, lon)
            val list = repository.incidents.value
            // Check incidents against alert preferences
            safetyAlertManager.evaluateIncidents(list, lat, lon)
            // Auto-sync filtered incidents within radius to watch
            syncToWear()
        }
    }

    fun updateUserLocation(lat: Double, lon: Double) {
        prefs.edit().putString("last_lat", lat.toString()).putString("last_lon", lon.toString()).apply()
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
        val realIncident = filteredIncidents.value.firstOrNull() ?: repository.incidents.value.firstOrNull()
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
        val radius = effectiveRangeMiles.value
        // Only incidents within the active radius are synchronized to the wrist companion
        val currentFiltered = filteredIncidents.value
        wearSyncManager.syncIncidentsToWatch(
            incidents = currentFiltered,
            highPriority = highPriority,
            userLat = _userLatitude.value,
            userLon = _userLongitude.value,
            radiusMiles = radius
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
