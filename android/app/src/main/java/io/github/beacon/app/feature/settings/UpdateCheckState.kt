package io.github.beacon.app.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.beacon.app.domain.model.AppRelease

/** What a finished update check owes the user; [SILENT] is the background check, which says nothing. */
enum class UpdateCheckReport {
    SILENT,
    AVAILABLE,
    LATEST,
    FAILED,
}

/**
 * The outcome of the release check, kept for the settings entry that starts it.
 *
 * The app checks once per entry to the foreground, but a background check never opens anything: it
 * only turns on the badge. The dialog appears when the user acts — by tapping the entry when an
 * update is already known, or when their own check finds one.
 */
class UpdateCheckState {
    var checking by mutableStateOf(false)
        private set
    var release by mutableStateOf<AppRelease?>(null)
        private set
    var dialogOpen by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set

    fun openDialog() {
        if (release != null) dialogOpen = true
    }

    fun closeDialog() {
        dialogOpen = false
    }

    fun beginDownload() {
        downloading = true
    }

    fun endDownload() {
        downloading = false
    }

    /**
     * Runs [fetch] and records the verdict, reporting only when the user asked for this check.
     *
     * A fetch failure leaves the previous verdict untouched: being offline says nothing about the
     * installed build, so it must not clear a badge that a reachable check had already set.
     */
    suspend fun check(manual: Boolean, fetch: suspend () -> Result<AppRelease?>): UpdateCheckReport {
        // One check at a time, so a repeated tap cannot race an in-flight result.
        if (checking) return UpdateCheckReport.SILENT
        checking = true
        val outcome = fetch()
        checking = false
        return outcome.fold(
            onSuccess = { found ->
                release = found
                if (found == null) dialogOpen = false
                when {
                    !manual -> UpdateCheckReport.SILENT
                    found == null -> UpdateCheckReport.LATEST
                    else -> {
                        dialogOpen = true
                        UpdateCheckReport.AVAILABLE
                    }
                }
            },
            onFailure = { if (manual) UpdateCheckReport.FAILED else UpdateCheckReport.SILENT },
        )
    }
}
