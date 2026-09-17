package com.neighborhood.safestreet.data.alerts

import android.content.Context
import android.content.SharedPreferences
import com.neighborhood.safestreet.common.models.IncidentCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlertPreferences(
    val enabled: Boolean = true,
    val radiusMiles: Double = 5.0,
    val categories: Set<IncidentCategory> = IncidentCategory.entries.toSet(),
    val soundVibration: Boolean = true,
    val pushToWatch: Boolean = true
)

class AlertPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("safestreet_alert_prefs", Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<AlertPreferences> = _preferences.asStateFlow()

    private fun loadPreferences(): AlertPreferences {
        val enabled = prefs.getBoolean("alerts_enabled", true)
        val radius = prefs.getFloat("alerts_radius_miles", 5.0f).toDouble()
        val soundVibe = prefs.getBoolean("alerts_sound_vibe", true)
        val pushWatch = prefs.getBoolean("alerts_push_watch", true)

        val savedCategories = prefs.getStringSet("alerts_categories", null)
        val categories = if (savedCategories != null) {
            savedCategories.mapNotNull { code ->
                IncidentCategory.entries.find { it.code == code }
            }.toSet()
        } else {
            IncidentCategory.entries.toSet()
        }

        return AlertPreferences(
            enabled = enabled,
            radiusMiles = radius,
            categories = categories,
            soundVibration = soundVibe,
            pushToWatch = pushWatch
        )
    }

    fun updatePreferences(newPrefs: AlertPreferences) {
        prefs.edit()
            .putBoolean("alerts_enabled", newPrefs.enabled)
            .putFloat("alerts_radius_miles", newPrefs.radiusMiles.toFloat())
            .putBoolean("alerts_sound_vibe", newPrefs.soundVibration)
            .putBoolean("alerts_push_watch", newPrefs.pushToWatch)
            .putStringSet("alerts_categories", newPrefs.categories.map { it.code }.toSet())
            .apply()
        _preferences.value = newPrefs
    }

    fun toggleCategory(category: IncidentCategory) {
        val current = _preferences.value
        val updatedCategories = if (current.categories.contains(category)) {
            if (current.categories.size > 1) current.categories - category else current.categories
        } else {
            current.categories + category
        }
        updatePreferences(current.copy(categories = updatedCategories))
    }

    fun setRadius(radiusMiles: Double) {
        updatePreferences(_preferences.value.copy(radiusMiles = radiusMiles))
    }

    fun setEnabled(enabled: Boolean) {
        updatePreferences(_preferences.value.copy(enabled = enabled))
    }

    fun setPushToWatch(push: Boolean) {
        updatePreferences(_preferences.value.copy(pushToWatch = push))
    }

    fun setSoundVibration(sound: Boolean) {
        updatePreferences(_preferences.value.copy(soundVibration = sound))
    }
}
