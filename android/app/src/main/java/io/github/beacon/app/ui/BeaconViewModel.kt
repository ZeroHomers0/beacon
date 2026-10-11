package io.github.beacon.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import io.github.beacon.app.BuildConfig
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.feature.diagnostics.AndroidSupportReportText
import io.github.beacon.app.feature.diagnostics.SupportReport
import io.github.beacon.app.feature.diagnostics.SupportSnapshot
import io.github.beacon.app.ipc.RuntimeActions

/**
 * Lifecycle owner for UI actions and page state.
 *
 * Credentials and database access stay in :runtime; this holds the page state that used to live in
 * the composable, so it survives configuration changes and process death through
 * [SavedStateHandle].
 */
class BeaconViewModel(
    application: Application,
    handle: SavedStateHandle,
    private val repository: RuntimeRepository,
) : AndroidViewModel(application), RuntimeActions by repository {

    constructor(
        application: Application,
        handle: SavedStateHandle,
    ) : this(
        application,
        handle,
        RuntimeRepository(),
    )

    /** Page state, backed by [SavedStateHandle] and the appearance preferences. */
    val uiState = BeaconUiState(handle, UiStyleStore(application))

    fun releaseBackgroundDetail() {
        state.value.selectedMessage?.let {
            val key = io.github.beacon.app.domain.model.MessageKey(it.server, it.id)
            uiState.detailKey = key
            uiState.pendingNotification = key
        }
        repository.releaseBackgroundDetail()
    }

    fun supportReport(notificationsAllowed: Boolean): String {
        val runtime = state.value
        return SupportReport.render(
            SupportSnapshot(
                BuildConfig.VERSION_CODE,
                android.os.Build.VERSION.SDK_INT,
                BuildConfig.DEBUG,
                runtime.runtimeConnected,
                notificationsAllowed,
                runtime.pollingState,
                runtime.lastManualOutcome,
                runtime.receivePlan.mode,
                runtime.realtimeRunning,
                runtime.realtimeDiagnostics,
                runtime.recovery,
                runtime.cleanupPreview,
            ),
            System.currentTimeMillis(),
            AndroidSupportReportText(getApplication()),
        )
    }

    override fun onCleared() {
        repository.close()
    }
}
