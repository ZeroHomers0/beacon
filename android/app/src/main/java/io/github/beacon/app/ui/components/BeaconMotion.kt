package io.github.beacon.app.ui.components

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The one place animation lengths live.
 *
 * Nine different literals used to describe the same few intentions, which is why two adjacent
 * surfaces could settle at visibly different speeds. Everything now reads a named intent instead:
 * a tap acknowledges in [QuickMs], a colour or size settles in [StandardMs], swapped content fades
 * in [FadeMs], a page transition runs [LargeMs], and the loading pulse repeats every [PulseMs].
 */
object BeaconMotion {
    /** Acknowledgement of a tap: press feedback, ripple-like state flips. */
    const val QuickMs = 150

    /** A value settling: colour, scale, expand/collapse. */
    const val StandardMs = 200

    /** Content replaced by other content. Deliberately longer than a value change. */
    const val FadeMs = 240

    /** Page-level transitions. Shared with the settings navigation curve. */
    const val LargeMs = SETTINGS_NAVIGATION_DURATION_MS

    /** One turn of a busy indicator, and the breathing loop of a live badge. */
    const val PulseMs = 1200

    /** How long a live badge takes to dim, and how often it breathes. */
    const val BreathFadeMs = 700
    const val BreathPeriodMs = 2600
}

/**
 * Card content grows and shrinks with this spec. The default spring drifted for ~600ms and read as
 * "slow" next to the 200ms value changes inside the same card.
 */
internal val MotionResize:
    androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
    androidx.compose.animation.core.tween(BeaconMotion.StandardMs)

/**
 * Whether the system currently allows animations. Read through [ValueAnimator.areAnimatorsEnabled]
 * so `Animator duration scale = 0` turns everything off, and re-read on resume because the setting
 * can change while the app sits in the background.
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = ValueAnimator.areAnimatorsEnabled()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LifecycleResumeEffect(Unit) {
        enabled = ValueAnimator.areAnimatorsEnabled()
        onPauseOrDispose {}
    }
    return enabled
}
