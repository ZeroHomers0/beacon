package io.github.beacon.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.ipc.RuntimeActions

/**
 * Lifecycle owner for UI actions and page state.
 *
 * Credentials and database access stay behind the reception service; this holds the page state that
 * used to live in the composable, so it survives configuration changes and process death through
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
        repository.saveReadingAnchor(uiState.readingKey)
        uiState.restoreReadingPosition = uiState.readingKey != null
        state.value.selectedMessage?.let {
            val key = io.github.beacon.app.domain.model.MessageKey(it.server, it.id)
            uiState.detailKey = key
            uiState.pendingNotification = key
        }
        repository.releaseBackgroundDetail()
    }

    override fun onCleared() {
        repository.close()
    }
}
