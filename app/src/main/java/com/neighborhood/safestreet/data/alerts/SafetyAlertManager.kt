package com.neighborhood.safestreet.data.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.neighborhood.safestreet.MainActivity
import com.neighborhood.safestreet.R
import com.neighborhood.safestreet.common.models.Incident
import com.neighborhood.safestreet.common.models.IncidentCategory
import com.neighborhood.safestreet.common.models.ProvenanceType
import com.neighborhood.safestreet.wear.WearableSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

class SafetyAlertManager(
    private val context: Context,
    private val preferencesRepository: AlertPreferencesRepository,
    private val wearableSyncManager: WearableSyncManager,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        const val CHANNEL_ID = "safestreet_alerts"
        const val CHANNEL_NAME = "Public Safety Alerts"
        private const val EARTH_RADIUS_MILES = 3958.8
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val notifiedIncidentIds = mutableSetOf<String>()

    private val _activeInAppAlert = MutableStateFlow<Incident?>(null)
    val activeInAppAlert: StateFlow<Incident?> = _activeInAppAlert.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "High-priority alerts for verified public safety incidents near your location"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun dismissInAppAlert() {
        _activeInAppAlert.value = null
    }

    fun evaluateIncidents(incidents: List<Incident>, userLat: Double, userLon: Double) {
        val prefs = preferencesRepository.preferences.value
        if (!prefs.enabled) return

        coroutineScope.launch(Dispatchers.Default) {
            for (incident in incidents) {
                if (notifiedIncidentIds.contains(incident.id)) continue

                val distanceMiles = calculateDistance(userLat, userLon, incident.latitude, incident.longitude)
                val matchesCategory = prefs.categories.contains(incident.category)
                val withinRadius = distanceMiles <= prefs.radiusMiles

                if (matchesCategory && withinRadius) {
                    notifiedIncidentIds.add(incident.id)
                    triggerAlert(incident, distanceMiles, prefs)
                    break // Alert on most immediate incident in this pass
                }
            }
        }
    }

    fun triggerAlertOnRealIncident(incidents: List<Incident>, userLat: Double, userLon: Double): Incident? {
        val prefs = preferencesRepository.preferences.value
        val target = incidents.firstOrNull() ?: return null
        val distanceMiles = calculateDistance(userLat, userLon, target.latitude, target.longitude)
        notifiedIncidentIds.add(target.id)
        triggerAlert(target, distanceMiles, prefs)
        return target
    }

    private fun triggerAlert(incident: Incident, distanceMiles: Double, prefs: AlertPreferences) {
        // 1. Post In-App Banner
        _activeInAppAlert.value = incident

        // 2. Post Android Notification (Heads-up alert)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("focused_incident_id", incident.id)
                    putExtra("open_map", true)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    incident.id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val formattedDist = String.format("%.1f mi", distanceMiles)
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("⚠️ ${incident.category.displayName} Nearby ($formattedDist)")
                    .setContentText("${incident.title} • ${incident.displayAddress}")
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("${incident.title}\nLocation: ${incident.displayAddress} (~$formattedDist away)\nAgency: ${incident.agency}\n${incident.description ?: ""}")
                    )
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setVibrate(if (prefs.soundVibration) longArrayOf(0, 400, 200, 400) else null)
                    .build()

                notificationManager.notify(incident.id.hashCode(), notification)
                Log.d("SafetyAlertManager", "Fired notification for incident: ${incident.id}")
            }
        } catch (e: Exception) {
            Log.e("SafetyAlertManager", "Error posting notification: ${e.message}")
        }

        // 3. Push to Wear OS Watch
        if (prefs.pushToWatch) {
            wearableSyncManager.syncAlertToWatch(incident)
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_MILES * c
    }
}
