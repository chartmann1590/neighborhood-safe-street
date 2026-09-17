package com.neighborhood.safestreet.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.neighborhood.safestreet.common.models.Incident
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
import kotlinx.serialization.encodeToString

class WearableSyncManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onQuickReportReceived: (WearQuickReport) -> Unit = {}
) : MessageClient.OnMessageReceivedListener {

    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    private val _isWearConnected = MutableStateFlow(false)
    val isWearConnected: StateFlow<Boolean> = _isWearConnected.asStateFlow()

    private val _connectedNodeName = MutableStateFlow<String?>(null)
    val connectedNodeName: StateFlow<String?> = _connectedNodeName.asStateFlow()

    private val _lastSyncStatus = MutableStateFlow("Initialized")
    val lastSyncStatus: StateFlow<String> = _lastSyncStatus.asStateFlow()

    init {
        try {
            messageClient.addListener(this)
            checkConnection()
        } catch (e: Exception) {
            Log.w("WearableSync", "Failed to register Wearable listener: ${e.message}")
        }
    }

    fun checkConnection() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    _isWearConnected.value = true
                    _connectedNodeName.value = nodes.first().displayName
                    _lastSyncStatus.value = "Connected to ${nodes.first().displayName}"
                } else {
                    _isWearConnected.value = false
                    _connectedNodeName.value = null
                    _lastSyncStatus.value = "No Wear OS companion paired"
                }
            } catch (e: Exception) {
                Log.w("WearableSync", "Error querying connected nodes: ${e.message}")
                _isWearConnected.value = false
                _lastSyncStatus.value = "Wear layer active (waiting for node)"
            }
        }
    }

    fun syncIncidentsToWatch(
        incidents: List<Incident>,
        highPriority: Incident? = null,
        userLat: Double? = null,
        userLon: Double? = null
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val packet = WearSyncPacket(
                    packetTimestamp = System.currentTimeMillis(),
                    activeIncidents = incidents.take(15),
                    highPriorityAlert = highPriority,
                    userLatitude = userLat,
                    userLongitude = userLon
                )
                val jsonString = JsonHelper.json.encodeToString(packet)
                val data = jsonString.toByteArray(Charsets.UTF_8)

                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, "/incidents", data).await()
                        Log.d("WearableSync", "Sent incidents sync packet to ${node.displayName}")
                    }
                    _isWearConnected.value = true
                    _connectedNodeName.value = nodes.first().displayName
                    _lastSyncStatus.value = "Synced ${packet.activeIncidents.size} incidents to watch"
                } else {
                    _lastSyncStatus.value = "Standby (No watch currently connected)"
                }
            } catch (e: Exception) {
                Log.e("WearableSync", "Error sending sync packet to watch: ${e.message}")
                _lastSyncStatus.value = "Sync error: ${e.message?.take(30)}"
            }
        }
    }

    fun syncAlertToWatch(incident: Incident) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val alertJson = JsonHelper.json.encodeToString(incident).toByteArray(Charsets.UTF_8)
                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, "/alert", alertJson).await()
                        Log.d("WearableSync", "Pushed alert to watch node ${node.displayName}")
                    }
                    _lastSyncStatus.value = "Pushed alert: ${incident.title.take(20)}"
                }
            } catch (e: Exception) {
                Log.e("WearableSync", "Failed to push alert to watch: ${e.message}")
            }
        }
    }

    fun sendTestAlertToWatch() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val alertJson = """
                    {"title":"TEST SAFETY ALERT","message":"High-priority test alert broadcast to Wear OS companion via Bluetooth."}
                """.trimIndent().toByteArray(Charsets.UTF_8)

                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, "/alert", alertJson).await()
                    }
                    _lastSyncStatus.value = "Test alert sent to watch"
                } else {
                    _lastSyncStatus.value = "Cannot send test: Watch not connected"
                }
            } catch (e: Exception) {
                Log.e("WearableSync", "Failed to send test alert: ${e.message}")
                _lastSyncStatus.value = "Alert send failed"
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/quick_report") {
            try {
                val json = String(messageEvent.data, Charsets.UTF_8)
                val report = JsonHelper.json.decodeFromString<WearQuickReport>(json)
                onQuickReportReceived(report)
                _lastSyncStatus.value = "Received quick report from watch: ${report.category.displayName}"
            } catch (e: Exception) {
                Log.e("WearableSync", "Failed to parse quick report: ${e.message}")
            }
        }
    }

    fun cleanup() {
        try {
            messageClient.removeListener(this)
        } catch (e: Exception) {
            // ignore
        }
    }
}
