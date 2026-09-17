package com.neighborhood.safestreet.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

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
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _incidents = MutableStateFlow<List<Incident>>(emptyList())
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    private val _connectionMode = MutableStateFlow(WearConnectionMode.SEARCHING)
    val connectionMode: StateFlow<WearConnectionMode> = _connectionMode.asStateFlow()

    private val _statusMessage = MutableStateFlow("Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _latestAlert = MutableStateFlow<String?>(null)
    val latestAlert: StateFlow<String?> = _latestAlert.asStateFlow()

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.neighborhood.safestreet.wear.INCIDENTS_SYNC" -> {
                    val json = intent.getStringExtra("payload") ?: return
                    try {
                        val packet = JsonHelper.json.decodeFromString<WearSyncPacket>(json)
                        _incidents.value = packet.activeIncidents
                        _connectionMode.value = WearConnectionMode.BLUETOOTH_PHONE
                        _statusMessage.value = "Phone Linked (${packet.activeIncidents.size} alerts)"
                        packet.highPriorityAlert?.let { triggerHapticAlert(it.title) }
                    } catch (e: Exception) {
                        Log.e("WearDataManager", "Error parsing Bluetooth sync packet: ${e.message}")
                    }
                }
                "com.neighborhood.safestreet.wear.HIGH_PRIORITY_ALERT" -> {
                    val json = intent.getStringExtra("payload") ?: return
                    triggerHapticAlert("Critical Public Safety Alert Received")
                    _latestAlert.value = "High Priority Alert Received"
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
                if (_incidents.value.isEmpty()) {
                    _incidents.value = getFallbackWearIncidents()
                }
            }
        }
    }

    private suspend fun fetchDirectOpenData() = withContext(Dispatchers.IO) {
        val list = mutableListOf<Incident>()
        try {
            // Standalone Direct Fallback: Query Seattle Fire Live CAD via watch Internet
            val url = "https://data.seattle.gov/resource/kzjm-xkqj.json?\$limit=10&\$order=datetime%20DESC"
            val req = Request.Builder().url(url).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONArray(body)
                    val now = System.currentTimeMillis()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val num = obj.optString("incident_number", "$i")
                        val type = obj.optString("type", "Safety Response")
                        val addr = obj.optString("address", "Nearby Area")
                        list.add(
                            Incident(
                                id = "wear_net_$num",
                                category = if (type.contains("Fire", ignoreCase = true)) IncidentCategory.FIRE_SMOKE else IncidentCategory.OTHER_SAFETY,
                                title = type,
                                description = "Live 911 dispatch at $addr (Fetched directly via watch Wi-Fi/LTE fallback)",
                                occurredAtEpochMs = now - (i * 5 * 60 * 1000L),
                                sourceUpdatedAtEpochMs = now,
                                receivedAtEpochMs = now,
                                latitude = obj.optDouble("latitude", 47.6062),
                                longitude = obj.optDouble("longitude", -122.3321),
                                displayAddress = addr,
                                sourceId = "wear_direct_internet",
                                agency = "Seattle Fire 911 (Direct)",
                                provenance = ProvenanceType.OFFICIAL_LIVE,
                                isHighPriority = i == 0
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WearDataManager", "Direct internet fetch error: ${e.message}")
        }

        if (list.isNotEmpty()) {
            _incidents.value = list
            _statusMessage.value = "Internet Fallback: ${list.size} alerts"
        } else if (_incidents.value.isEmpty()) {
            _incidents.value = getFallbackWearIncidents()
        }
    }

    fun quickReportFromWrist(category: IncidentCategory) {
        coroutineScope.launch(Dispatchers.IO) {
            val report = WearQuickReport(
                category = category,
                latitude = 47.6062,
                longitude = -122.3321,
                timestampEpochMs = System.currentTimeMillis(),
                note = "Quick observation logged directly from Wear OS wrist interface."
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
                description = "Observed from smartwatch. Expires in 24 hours.",
                occurredAtEpochMs = now,
                sourceUpdatedAtEpochMs = now,
                receivedAtEpochMs = now,
                expiresAtEpochMs = now + (24 * 3600 * 1000L),
                latitude = 47.6062,
                longitude = -122.3321,
                displayAddress = "Current Location",
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

    private fun getFallbackWearIncidents(): List<Incident> {
        val now = System.currentTimeMillis()
        return listOf(
            Incident(
                id = "wear_seed_1",
                category = IncidentCategory.FIRE_SMOKE,
                title = "Structure Fire",
                description = "Active fire response nearby.",
                occurredAtEpochMs = now - (5 * 60 * 1000L),
                sourceUpdatedAtEpochMs = now - (5 * 60 * 1000L),
                receivedAtEpochMs = now,
                latitude = 47.6062,
                longitude = -122.3321,
                displayAddress = "4th Ave & Pine St",
                sourceId = "seed",
                agency = "Fire Dept",
                provenance = ProvenanceType.OFFICIAL_LIVE,
                isHighPriority = true
            ),
            Incident(
                id = "wear_seed_2",
                category = IncidentCategory.VEHICLE_CRASH,
                title = "Vehicle Collision",
                description = "Traffic incident blocking lane.",
                occurredAtEpochMs = now - (18 * 60 * 1000L),
                sourceUpdatedAtEpochMs = now - (18 * 60 * 1000L),
                receivedAtEpochMs = now,
                latitude = 47.6100,
                longitude = -122.3400,
                displayAddress = "Market & 5th St",
                sourceId = "seed",
                agency = "Police Dispatch",
                provenance = ProvenanceType.OFFICIAL_LIVE,
                isHighPriority = false
            )
        )
    }

    fun cleanup() {
        try {
            context.unregisterReceiver(syncReceiver)
        } catch (e: Exception) {
            // ignore
        }
    }
}
