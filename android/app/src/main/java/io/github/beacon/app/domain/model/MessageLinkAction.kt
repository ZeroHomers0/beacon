package io.github.beacon.app.domain.model

data class MessageLinkAction(val title: String, val url: String) {
    val displayTitle: String
        get() =
            title.trim().let { text ->
                text.substring(
                    0,
                    text.offsetByCodePoints(0, minOf(4, text.codePointCount(0, text.length))),
                )
            }
}
