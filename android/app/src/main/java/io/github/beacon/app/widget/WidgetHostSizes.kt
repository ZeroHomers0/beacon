package io.github.beacon.app.widget

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF

/** Host dimensions are dp, not the pixel dimensions of Xiaomi's design assets. */
object WidgetHostSizes {
    fun resolve(options: Bundle, orientation: Int, fallbackWidth: Int = 110, fallbackHeight: Int = 110): List<SizeF> {
        if (Build.VERSION.SDK_INT >= 31) {
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
                .distinct().take(16)
            if (sizes.isNotEmpty()) return sizes
        }
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallbackWidth)
        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, fallbackHeight)
        return listOf(SizeF(width.coerceAtLeast(1).toFloat(), height.coerceAtLeast(1).toFloat()))
    }
}
