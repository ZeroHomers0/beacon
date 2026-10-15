package io.github.beacon.app.feature.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.NotificationChannelKind
import io.github.beacon.app.ui.components.*

/** Android owns channel sounds, including user-selected local audio and silent overrides. */
@Composable
fun NotificationSoundPanel() {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = LocalContext.current
    var hint by remember { mutableStateOf("") }
    SettingsSection(stringResource(R.string.settings_sound_title)) {

        val channels = listOf(
            SoundChannel(
                NotificationChannelKind.FRESH,
                resources.getString(R.string.settings_sound_normal),
                NotificationManager.IMPORTANCE_DEFAULT,
                resources.getString(R.string.channel_fresh),
                resources.getString(R.string.channel_fresh_desc),
            ),
            SoundChannel(
                NotificationChannelKind.IMPORTANT,
                resources.getString(R.string.settings_sound_important),
                NotificationManager.IMPORTANCE_HIGH,
                resources.getString(R.string.channel_important),
                resources.getString(R.string.channel_important_desc),
            ),
        )
        BeaconCompactButtonGrid(channels.map { it.title }) { index ->
            val (kind, _, importance, name, description) = channels[index]
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java)
                // Seed only missing channels; preserve the user's existing channel settings.
                if (manager.getNotificationChannel(kind.id) == null)
                    manager.createNotificationChannel(
                        NotificationChannel(kind.id, name, importance).apply {
                            this.description = description
                        }
                    )
                context.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, kind.id)
                )
            }.onFailure { hint = resources.getString(R.string.settings_sound_unavailable) }
        }
        if (hint.isNotEmpty()) BeaconText(hint)
    }
}

private data class SoundChannel(
    val kind: NotificationChannelKind,
    val title: String,
    val importance: Int,
    val name: String,
    val description: String,
)
