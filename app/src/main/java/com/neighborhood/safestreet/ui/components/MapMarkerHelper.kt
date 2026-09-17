package com.neighborhood.safestreet.ui.components

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.neighborhood.safestreet.common.models.IncidentCategory

object MapMarkerHelper {
    fun createCategoryMarkerDrawable(context: Context, category: IncidentCategory, isSelected: Boolean = false): Drawable {
        val size = if (isSelected) 72 else 56
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val color = when (category) {
            IncidentCategory.FIRE_SMOKE -> Color.parseColor("#FF1744")
            IncidentCategory.POLICE_ACTIVITY -> Color.parseColor("#2979FF")
            IncidentCategory.VEHICLE_CRASH -> Color.parseColor("#FF9100")
            IncidentCategory.MEDICAL_RESPONSE -> Color.parseColor("#D50000")
            IncidentCategory.ROAD_HAZARD -> Color.parseColor("#FFEA00")
            IncidentCategory.HAZARD_CONDITION -> Color.parseColor("#FFC400")
            IncidentCategory.WEATHER_HAZARD -> Color.parseColor("#00E5FF")
            IncidentCategory.OTHER_SAFETY -> Color.parseColor("#00B0FF")
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (isSelected) Color.WHITE else Color.parseColor("#0B1017")
            style = Paint.Style.STROKE
            strokeWidth = if (isSelected) 6f else 4f
        }

        val center = size / 2f
        val radius = (size / 2f) - 6f

        // Draw shadow / glow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(100, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius + 2f, shadowPaint)

        // Draw main circle pin
        canvas.drawCircle(center, center, radius, paint)
        canvas.drawCircle(center, center, radius, strokePaint)

        // Draw center inner dot
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius * 0.38f, innerPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    fun createUserLocationDrawable(context: Context): Drawable {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3300E5FF")
            style = Paint.Style.FILL
        }

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.FILL
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        val center = size / 2f
        canvas.drawCircle(center, center, center - 2f, outerPaint)
        canvas.drawCircle(center, center, center * 0.5f, centerPaint)
        canvas.drawCircle(center, center, center * 0.5f, strokePaint)

        return BitmapDrawable(context.resources, bitmap)
    }
}
