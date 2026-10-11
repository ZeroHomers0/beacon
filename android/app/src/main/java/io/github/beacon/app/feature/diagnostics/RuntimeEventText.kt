package io.github.beacon.app.feature.diagnostics

import io.github.beacon.app.domain.model.*

/**
 * Event-export copy, supplied by the caller so the formatter stays free of Android types and keeps
 * running under plain JVM tests. Android callers use [AndroidRuntimeEventText].
 */
interface RuntimeEventText {
    val title: String
    val disclaimer: String

    fun kind(kind: RuntimeEventKind): String

    fun outcome(outcome: SyncOutcome): String

    /** Returns null for [RealtimeFailure.NONE], which contributes no word to the line. */
    fun failure(failure: RealtimeFailure): String?

    /** Only enum labels and timestamps can enter the event export. */
    fun label(event: RuntimeEvent): String {
        val parts =
            listOfNotNull(
                kind(event.kind),
                event.outcome?.let { outcome(it) },
                failure(event.failure),
            )
        return parts.joinToString(" · ")
    }

    fun export(events: List<RuntimeEvent>): String = buildString {
        appendLine(title)
        events.takeLast(RuntimeEvent.MAX_EVENTS).forEach {
            appendLine("${java.time.Instant.ofEpochMilli(it.atMs)} · ${label(it)}")
        }
        append(disclaimer)
    }
}
