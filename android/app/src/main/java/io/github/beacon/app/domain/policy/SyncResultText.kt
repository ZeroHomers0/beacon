package io.github.beacon.app.domain.policy

/** Displays results persisted by earlier builds using the current user-facing terminology. */
object SyncResultText {
    private val pendingCount = Regex("；\\s*待\\s*ACK\\s*(\\d+)\\s*条")
    private val legacyRetryHint = Regex("[。；]?有[积挤]压[时式]请再次同步[。]?")

    // The literals below are inputs written by older builds, not copy this build emits;
    // they are rewritten on read so persisted 状态 never shows retired wording.
    private const val LEGACY_EMPTY_QUEUE = "没有待提醒消息"

    fun normalize(text: String): String =
        pendingCount
            .replace(text) { match ->
                val count = match.groupValues[1].toLongOrNull()
                if (count == 0L) "" else "；待回传接收结果 ${match.groupValues[1]} 条（自动重试）"
            }
            .replace(legacyRetryHint, "")
            .replace("ACK 确认", "向服务器回传接收结果")
            .replace("待 ACK", "待回传接收结果")
            .replace(Regex("\\bACK\\b"), "接收结果回传")
            .lineSequence()
            .filter { it.trim() != LEGACY_EMPTY_QUEUE }
            .joinToString("\n")
            .trim()
}
