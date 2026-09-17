package com.neighborhood.safestreet.common.util

import java.util.Locale
import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS_MILES = 3958.8

    /**
     * Calculates the great-circle distance between two points in miles using the Haversine formula.
     */
    fun calculateDistanceMiles(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_MILES * c
    }

    /**
     * Calculates the initial bearing (forward azimuth) from point 1 to point 2 in degrees.
     * Returns a normalized angle in [0.0, 360.0), where 0° is True North, 90° is East, 180° is South, 270° is West.
     */
    fun calculateBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360.0) % 360.0
    }

    /**
     * Formats distance in miles into a readable, concise string.
     */
    fun formatDistanceMiles(miles: Double): String {
        return when {
            miles < 0.1 -> "<0.1 mi"
            miles < 10.0 -> String.format(Locale.US, "%.1f mi", miles)
            else -> String.format(Locale.US, "%.0f mi", miles)
        }
    }
}
