package io.github.beacon.app.core

import android.content.Context
import android.content.Intent

/** Package-private cross-process invalidation. Storage reports facts; manifest wiring selects consumers. */
object TopicChanges {
    const val UI_CHANGED = "io.github.beacon.STATE_CACHE_CHANGED"
    const val DATA_CHANGED = "io.github.beacon.TOPIC_DATA_CHANGED"
    private const val MAX_IDENTITIES = 500

    fun ui(context: Context) {
        context.sendBroadcast(Intent(UI_CHANGED).setPackage(context.packageName))
    }

    fun data(context: Context, source: String, identities: List<String>?) {
        context.sendBroadcast(Intent(DATA_CHANGED).setPackage(context.packageName).putExtra("source",source).apply {
            // Null means bounded whole-source invalidation, not an arbitrarily large Binder payload.
            identities?.asSequence()?.distinct()?.take(MAX_IDENTITIES + 1)?.toList()?.takeIf { it.size <= MAX_IDENTITIES }?.let {
                putStringArrayListExtra("identities",ArrayList(it))
            }
        })
    }
}
