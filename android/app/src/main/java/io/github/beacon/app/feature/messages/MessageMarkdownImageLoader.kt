package io.github.beacon.app.feature.messages

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import io.github.beacon.app.data.remote.RemoteMessageImage
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inline Markdown images, fetched only while their `TextView` holds them.
 *
 * Markwon's own loader lives in `markwon-image`, which this app does not ship; the parts that
 * matter are here instead, on top of [RemoteMessageImage], so an inline picture passes the same gate
 * as every other remote image: HTTPS only, no user info, 2 MiB, 16 MP, no private network target,
 * five second cap.
 *
 * Five rules keep it bounded and quiet:
 * - A request starts only when the drawable is attached, and the scheduler detaches it when the
 *   body leaves the window, so scrolling away drops queued work.
 * - At most [MAX_IN_FLIGHT] downloads run at once; the rest wait their turn instead of being
 *   dropped, since a body is free to carry more pictures than that.
 * - A result is discarded if the drawable detached meanwhile, so a view that moved on never
 *   receives a previous body's picture.
 * - Only the first [MAX_PICTURES] distinct destinations in a body are fetched. The body limit is
 *   64 KiB, which is room for hundreds of image links, and every one of them would otherwise be a
 *   request the reader never asked for. Extra pictures render as nothing, exactly like a picture
 *   that failed.
 * - A destination that failed is not tried again for the lifetime of this loader. [RemoteMessageImage]
 *   does not cache failures so that a retry can succeed, but re-attaching a body would otherwise
 *   put the same unreachable picture back on the network on every scroll.
 *
 * There is deliberately no placeholder drawable. Markwon measures a placeholder from its own
 * intrinsic size, and at construction time nothing knows the line width, so a placeholder would be
 * laid out at some guessed width and — for an image that never loads, which is the common case for
 * a plain-HTTP or oversized picture — stay at that width forever. With `null` the span keeps
 * measuring the text it replaced (the alt text, or `U+FFFC` when the author wrote none), so an
 * unfetchable picture degrades to its own description instead of a grey box of the wrong size.
 */
internal class MessageMarkdownImageLoader(private val resources: Resources) :
    AsyncDrawableLoader() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = HashMap<AsyncDrawable, Job>(MAX_IN_FLIGHT)
    private val waiting = ArrayDeque<AsyncDrawable>()
    private val admitted = LinkedHashSet<String>(MAX_PICTURES)
    private val failed = LinkedHashSet<String>(MAX_PICTURES)

    override fun load(drawable: AsyncDrawable) {
        val destination = drawable.destination
        if (destination in failed) return
        if (destination !in admitted) {
            if (admitted.size >= MAX_PICTURES) return
            admitted.add(destination)
        }
        if (active.containsKey(drawable) || waiting.contains(drawable)) return
        if (active.size >= MAX_IN_FLIGHT) {
            waiting.addLast(drawable)
            return
        }
        start(drawable)
    }

    override fun cancel(drawable: AsyncDrawable) {
        waiting.remove(drawable)
        active.remove(drawable)?.cancel()
        drain()
    }

    /** Drops every transfer; the owning screen calls this when it leaves or loses its theme. */
    fun release() {
        waiting.clear()
        active.values.forEach { it.cancel() }
        active.clear()
    }

    /** No placeholder; see the class comment for why an empty picture beats a guessed one. */
    override fun placeholder(drawable: AsyncDrawable): Drawable? = null

    private fun start(drawable: AsyncDrawable) {
        active[drawable] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { RemoteMessageImage.load(drawable.destination) }
            if (bitmap != null && drawable.isAttached) drawable.setResult(bitmapDrawable(bitmap))
            else if (bitmap == null) rememberFailure(drawable.destination)
            active.remove(drawable)
            drain()
        }
    }

    private fun rememberFailure(destination: String) {
        failed.add(destination)
    }

    private fun drain() {
        while (active.size < MAX_IN_FLIGHT) {
            start(waiting.removeFirstOrNull() ?: return)
        }
    }

    private fun bitmapDrawable(bitmap: Bitmap): Drawable =
        // Bounds first: Markwon resolves the displayed size from the result's bounds, not from its
        // density-scaled intrinsic size, so this is what keeps a hi-dpi decode from being
        // double-scaled. The resolver then fits it to the line.
        bitmap.toDrawable(resources).apply { setBounds(0, 0, bitmap.width, bitmap.height) }

    private companion object {
        /** Four-ish phone-width columns of images; enough to keep a page busy, few enough to be polite. */
        const val MAX_IN_FLIGHT = 4

        /**
         * The most distinct pictures one body may fetch. The same cap [MessageMarkdown] applies to
         * the pictures it reports, so a body cannot ask for more than the reader was promised.
         */
        const val MAX_PICTURES = 5
    }
}
