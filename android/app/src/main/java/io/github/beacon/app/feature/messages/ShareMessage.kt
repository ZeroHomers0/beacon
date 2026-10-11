package io.github.beacon.app.feature.messages

import android.content.Context
import android.content.Intent
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.policy.MessageShare

internal fun shareMessage(context: Context, message: IncomingMessage) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, MessageShare.text(message))
                putExtra(Intent.EXTRA_SUBJECT, message.title)
            },
            null,
        )
    )
}
