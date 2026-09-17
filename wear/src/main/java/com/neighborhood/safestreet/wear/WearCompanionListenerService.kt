package com.neighborhood.safestreet.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearCompanionListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d("WearListener", "Message received on watch: path=${messageEvent.path}")
        when (messageEvent.path) {
            "/incidents" -> {
                val json = String(messageEvent.data, Charsets.UTF_8)
                val intent = Intent("com.neighborhood.safestreet.wear.INCIDENTS_SYNC").apply {
                    putExtra("payload", json)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            "/alert" -> {
                val json = String(messageEvent.data, Charsets.UTF_8)
                val intent = Intent("com.neighborhood.safestreet.wear.HIGH_PRIORITY_ALERT").apply {
                    putExtra("payload", json)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }
    }
}
