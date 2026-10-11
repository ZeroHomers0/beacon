package io.github.beacon.app

import android.content.Intent
import android.os.Bundle
import io.github.beacon.app.runtime.notification.NotificationNavigation

/** Private notification entry point; exported launcher intents never route or mutate messages. */
class NotificationOpenActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            NotificationNavigation.key(intent)?.let(::openNotificationMessage)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationNavigation.key(intent)?.let(::openNotificationMessage)
    }
}
