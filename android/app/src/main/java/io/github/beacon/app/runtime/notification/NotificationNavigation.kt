package io.github.beacon.app.runtime.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.beacon.app.NotificationOpenActivity
import io.github.beacon.app.domain.model.MessageKey

internal object NotificationNavigation {
    private const val SERVER = "notification_server"
    private const val MESSAGE = "notification_message"

    fun intent(context: Context, key: MessageKey): Intent {
        key.validate()
        return Intent(context, NotificationOpenActivity::class.java)
            .setData(
                Uri.Builder()
                    .scheme("beacon")
                    .authority("message")
                    .appendPath(key.server)
                    .appendPath(key.id)
                    .build()
            )
            .putExtra(SERVER, key.server)
            .putExtra(MESSAGE, key.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    fun key(intent: Intent): MessageKey? = runCatching {
        MessageKey(
                requireNotNull(intent.getStringExtra(SERVER)),
                requireNotNull(intent.getStringExtra(MESSAGE)),
            )
            .also { it.validate() }
    }
        .getOrNull()
}
