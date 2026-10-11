package io.github.beacon.app.domain.policy

import io.github.beacon.app.domain.model.IncomingMessage

object MessageShare {
    fun text(message: IncomingMessage): String =
        listOf(message.title, message.subtitle, message.body)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
}
