package com.neighborhood.safestreet.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.serialization.JsonHelper

class WearCompanionListenerService : WearableListenerService() {

    companion object {
        const val CHANNEL_ID = "wear_safety_alerts"
        const val CHANNEL_NAME = "Wear Safety Alerts"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority safety notifications pushed to watch"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 350)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

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

                // Try to parse incident and show wrist notification
                try {
                    val incident = JsonHelper.json.decodeFromString<Incident>(json)
                    showWristNotification(incident.title, incident.displayAddress)
                } catch (e: Exception) {
                    showWristNotification("CRITICAL SAFETY ALERT", "Nearby incident reported.")
                }
            }
        }
    }

    private fun showWristNotification(title: String, subtitle: String) {
        try {
            val appIntent = Intent(this, WearMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                999,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🚨 $title")
                .setContentText(subtitle)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(0, 350, 150, 350))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(this).notify(999, notification)
        } catch (e: Exception) {
            Log.e("WearListener", "Error showing wrist notification: ${e.message}")
        }
    }
}
