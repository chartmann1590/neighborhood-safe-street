package com.neighborhood.safestreet.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.neighborhood.safestreet.common.models.WearQuickReport
import com.neighborhood.safestreet.common.serialization.JsonHelper
import com.neighborhood.safestreet.data.repository.IncidentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HandheldWearableListenerService : WearableListenerService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val repository = IncidentRepository()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/quick_report") {
            try {
                val json = String(messageEvent.data, Charsets.UTF_8)
                val report = JsonHelper.json.decodeFromString<WearQuickReport>(json)
                Log.d("WearListenerService", "Quick report from watch: ${report.category}")

                scope.launch {
                    repository.submitCommunityReport(
                        category = report.category,
                        latitude = report.latitude,
                        longitude = report.longitude,
                        note = report.note
                    )
                }
            } catch (e: Exception) {
                Log.e("WearListenerService", "Failed to process message from watch: ${e.message}")
            }
        } else {
            super.onMessageReceived(messageEvent)
        }
    }
}
