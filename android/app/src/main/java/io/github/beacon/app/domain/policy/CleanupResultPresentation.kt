package io.github.beacon.app.domain.policy

/** Old localized timestamps cannot always be recovered; retain their count without guessing a date. */
object CleanupResultPresentation {
    private val cleaned = Regex("^(?:(.*?) · )?已清理 (\\d+) 条(?:本地消息)?$")
    private val timestamp = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")

    fun compact(value: String): String {
        val match = cleaned.matchEntire(value) ?: return value
        val time = match.groupValues[1].takeIf { timestamp.matches(it) }
        val result = "已清理 ${match.groupValues[2]} 条"
        return if (time == null) result else "$time · $result"
    }
}
