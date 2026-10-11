package io.github.beacon.app.ui.components

import androidx.compose.animation.core.Easing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SETTINGS_NAVIGATION_DURATION_MS = 500

// Spring step response with the KSU/MIUIX navigation tuning (response 0.8, damping 0.95).
internal val settingsNavigationEasing = Easing { fraction ->
    if (fraction <= 0f) 0f
    else if (fraction >= 1f) 1f
    else {
        val damping = .95
        val omega = 2 * PI / .8
        val damped = omega * sqrt(1 - damping * damping)
        val time = fraction.toDouble()
        (1 -
                exp(-damping * omega * time) *
                    (cos(damped * time) + damping * omega / damped * sin(damped * time)))
            .toFloat()
    }
}

/** SeekableTransitionState takes time fraction; invert the curve to follow the finger linearly. */
internal fun settingsGestureFraction(progress: Float): Float {
    if (!progress.isFinite() || progress <= 0f) return 0f
    if (progress >= 1f) return 1f
    var low = 0f
    var high = 1f
    repeat(20) {
        val middle = (low + high) / 2
        if (settingsNavigationEasing.transform(middle) < progress) low = middle else high = middle
    }
    return (low + high) / 2
}
