package io.github.beacon.app.ui.components

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cancel by rewinding the preview, retaining its geometry, layers and header size throughout. */
internal class PredictiveBackReset {
    private var job: Job? = null

    fun stop() {
        job?.cancel()
        job = null
    }

    fun <T> launch(scope: CoroutineScope, motion: SeekableTransitionState<T>, original: T) {
        stop()
        job = scope.launch {
            val previewTarget = motion.targetState
            val startProgress = settingsNavigationEasing.transform(motion.fraction)
            if (startProgress > 0f) {
                val start = withFrameNanos { it }
                var elapsed = 0f
                while (elapsed < 1f) {
                    elapsed = withFrameNanos { ((it - start) / 220_000_000f).coerceIn(0f, 1f) }
                    val progress = startProgress * (1f - settingsNavigationEasing.transform(elapsed))
                    motion.seekTo(settingsGestureFraction(progress), previewTarget)
                }
            }
            // Retargeting before the preview reaches zero creates a second entrance animation.
            motion.snapTo(original)
        }
    }
}
