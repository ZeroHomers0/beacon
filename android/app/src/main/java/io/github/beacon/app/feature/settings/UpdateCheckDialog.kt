package io.github.beacon.app.feature.settings

import io.github.beacon.app.ui.components.BeaconUiMetrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.beacon.app.R
import io.github.beacon.app.data.remote.ReleaseClient
import io.github.beacon.app.domain.model.AppRelease
import io.github.beacon.app.ui.components.BeaconAlertDialog
import io.github.beacon.app.ui.components.BeaconButton
import io.github.beacon.app.ui.components.BeaconText
import io.github.beacon.app.ui.components.BeaconTextButton
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Staging directory inside the cache; the provider exposes nothing else. */
private const val UPDATE_DIR = "update"
private const val APK_MIME = "application/vnd.android.package-archive"

private enum class InstallLaunch {
    LAUNCHED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
}

/**
 * Announces a newer build and, on request, downloads and installs it.
 *
 * Dismissing does not cancel an in-flight download: the user asked for that update, and the
 * installer is opened as soon as the archive is staged.
 */
@Composable
internal fun UpdateCheckDialog(
    state: UpdateCheckState,
    release: AppRelease,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val context = LocalContext.current
    // Resolved outside the lambda below: stringResource cannot be called from a plain lambda.
    val missingNotes = stringResource(R.string.settings_update_notes_empty)
    BeaconAlertDialog(
        onDismissRequest = onDismiss,
        title = { BeaconText(stringResource(R.string.settings_update_available, release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8)) {
                if (release.sizeBytes > 0) {
                    BeaconText(
                        stringResource(
                            R.string.settings_update_size,
                            io.github.beacon.app.ui.components.formatStorageSize(
                                context,
                                release.sizeBytes,
                                short = true,
                            ),
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
                    scope.launch { stageAndInstall(context, state, release, onDismiss, onFeedback) }
                },
                enabled = !state.downloading,
            ) {
                BeaconText(
                    stringResource(
                        if (state.downloading) R.string.settings_update_downloading
                        else R.string.settings_update_download
                    )
                )
            }
        },
        dismissButton = {
            BeaconTextButton(onClick = onDismiss) {
                BeaconText(stringResource(R.string.settings_update_later))
            }
        },
    )
}

private suspend fun stageAndInstall(
    context: Context,
    state: UpdateCheckState,
    release: AppRelease,
    onLaunched: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    if (!state.beginDownload()) return
    try {
        // The system requires the user to allow installs from this app; ask before spending a
        // download.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val opened = openInstallPermission(context)
            onFeedback(
                context.getString(
                    if (opened) R.string.settings_update_install_permission
                    else R.string.settings_update_install_unavailable
                )
            )
            return
        }
        val apk = stagedApk(context, release)
        val ready =
            apk.isStagedFor(release) ||
                withContext(Dispatchers.IO) {
                    clearStaged(context)
                    ReleaseClient.download(release, apk)
                }
        if (!ready) {
            onFeedback(context.getString(R.string.settings_update_download_failed))
            return
        }
        when (launchInstall(context, apk)) {
            InstallLaunch.LAUNCHED -> onLaunched()
            InstallLaunch.PERMISSION_REQUIRED -> {
                openInstallPermission(context)
                onFeedback(context.getString(R.string.settings_update_install_permission))
            }
            InstallLaunch.UNAVAILABLE ->
                onFeedback(context.getString(R.string.settings_update_install_unavailable))
        }
    } finally {
        state.endDownload()
    }
}

/** A staged archive is reusable only when it is the published one, in full. */
private fun File.isStagedFor(release: AppRelease): Boolean =
    isFile && length() > 0 && (release.sizeBytes <= 0 || length() == release.sizeBytes)

private fun stagedApk(context: Context, release: AppRelease): File =
    File(File(context.cacheDir, UPDATE_DIR), "beacon-${release.version}.apk")

private fun clearStaged(context: Context) {
    File(context.cacheDir, UPDATE_DIR).listFiles()?.forEach { it.delete() }
}

private fun openInstallPermission(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    )
}
    .isSuccess

private fun launchInstall(context: Context, apk: File): InstallLaunch {
    if (!context.packageManager.canRequestPackageInstalls())
        return InstallLaunch.PERMISSION_REQUIRED
    // The archive lives in the private cache, so it is handed over through the app's own provider.
    val uri =
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.update", apk) }
            .getOrNull() ?: return InstallLaunch.UNAVAILABLE
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (runCatching { context.startActivity(intent) }.isSuccess) InstallLaunch.LAUNCHED
    else InstallLaunch.UNAVAILABLE
}
