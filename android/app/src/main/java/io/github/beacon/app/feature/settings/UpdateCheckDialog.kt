package io.github.beacon.app.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.AppRelease
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import io.github.beacon.app.ui.components.BeaconUiMetrics
import io.github.beacon.app.ui.components.formatStorageSize

/**
 * Announces a newer build and hands its download over to the user's browser.
 *
 * The app never fetches or installs the archive itself: the published link opens in the browser,
 * where the release page and its signed assets live, so this app no longer asks the system for
 * permission to install packages.
 */
@Composable
internal fun UpdateCheckDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val context = LocalContext.current
    // Resolved outside the lambdas below: stringResource cannot be called from a plain lambda.
    val missingNotes = stringResource(R.string.settings_update_notes_empty)
    val noBrowser = stringResource(R.string.settings_update_browser_unavailable)
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.settings_update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
                if (release.sizeBytes > 0) {
                    BeaconText(
                        stringResource(
                            R.string.settings_update_size,
                            formatStorageSize(context, release.sizeBytes, short = true),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BeaconText(
                    release.notes.ifBlank { missingNotes },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            BeaconButton(
                onClick = {
                    if (openInBrowser(context, release.downloadUrl)) onDismiss()
                    else onFeedback(noBrowser)
                }
            ) {
                BeaconText(stringResource(R.string.settings_update_open_browser))
            }
        },
        dismissButton = {
            BeaconTextButton(onClick = onDismiss) {
                BeaconText(stringResource(R.string.settings_update_later))
            }
        },
    )
}

/**
 * Opens the published asset link in the user's browser; the download and the installer stay outside
 * this app. Only an HTTPS link is handed over, so a hostile feed cannot launch another handler
 * through this button.
 */
private fun openInBrowser(context: Context, url: String): Boolean {
    val uri = url.toUri()
    if (uri.scheme != "https") return false
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isSuccess
}
