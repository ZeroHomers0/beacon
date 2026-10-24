@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.beacon.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import io.github.beacon.app.ui.AppShortcuts
import io.github.beacon.app.ui.BeaconApp
import io.github.beacon.app.ui.BeaconViewModel
import io.github.beacon.app.ui.components.NotificationPermissionState
import io.github.beacon.app.ui.components.SettingsScreen

open class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<BeaconViewModel>()
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private var contentView: ComposeView? = null
    private var stoppedWithOperation = false
    internal var backgroundUiReleased by mutableStateOf(false)
        private set

    private val releaseBackgroundUi = Runnable { releaseIdleBackgroundUi() }

    /** Keep editing and external document-picker compositions alive until the user returns. */
    internal fun releaseIdleBackgroundUi(): Boolean {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            return false
        val ui = viewModel.uiState
        if (
            ui.settingsScreen !in
                setOf(
                    SettingsScreen.HOME,
                    SettingsScreen.ABOUT,
                    SettingsScreen.GUIDE,
                    SettingsScreen.BACKGROUND,
                ) ||
                stoppedWithOperation ||
                ui.filtersOpen ||
                ui.pendingDelete != null ||
                ui.operationDetails != null ||
                viewModel.state.value.messageBusy
        )
            return false
        backgroundUiReleased = true
        // The stopped window's recomposer cannot apply a conditional-content change promptly.
        // Dispose the child composition explicitly; its saveable holder lives in the outer view.
        contentView?.disposeComposition()
        viewModel.releaseBackgroundDetail()
        return true
    }

    protected fun openNotificationMessage(key: io.github.beacon.app.domain.model.MessageKey) {
        key.validate()
        viewModel.uiState.page = 0
        viewModel.uiState.selectionMode = false
        viewModel.uiState.selectedKeys = emptyList()
        viewModel.uiState.pendingNotification = key
    }

    /**
     * Land on the tab a launcher shortcut asked for.
     *
     * Only the page index is touched: a shortcut must not be able to open a message, so this never
     * goes through [openNotificationMessage].
     */
    private fun showShortcutTab(intent: Intent?) {
        val tab = AppShortcuts.requestedTab(this, intent) ?: return
        viewModel.uiState.selectionMode = false
        viewModel.uiState.selectedKeys = emptyList()
        viewModel.uiState.finishNavigation(tab)
    }

    /**
     * Notification permission is held as a state value rather than display text, so the settings
     * page decides what to show from the state instead of re-parsing a string.
     */
    private var notificationPermission by mutableStateOf(NotificationPermissionState.UNKNOWN)
    private var notificationHint by mutableStateOf<Int?>(null)

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationStatus()
        }

    private fun refreshNotificationStatus() {
        notificationPermission =
            if (getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                NotificationPermissionState.ALLOWED
            else NotificationPermissionState.DENIED
        notificationHint = null
    }

    override fun onStop() {
        val busy = viewModel.state.value.messageBusy
        stoppedWithOperation = busy
        viewModel.stop()
        super.onStop()
        if (!isChangingConfigurations && !busy) {
            // Cached processes can be frozen after 10 seconds on Android 14+. Release eligible
            // UI before that, while allowing quick app switches to reuse the composition.
            backgroundHandler.postDelayed(releaseBackgroundUi, 5_000L)
        }
    }

    override fun onStart() {
        backgroundHandler.removeCallbacks(releaseBackgroundUi)
        backgroundUiReleased = false
        stoppedWithOperation = false
        super.onStart()
        contentView?.createComposition()
    }

    override fun onDestroy() {
        backgroundHandler.removeCallbacks(releaseBackgroundUi)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            io.github.beacon.app.domain.policy.messageMarkdownDocuments.clear()
        }
        if (
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            // Lifecycle may still be STARTED when UI_HIDDEN arrives: keep the delayed fallback.
            releaseIdleBackgroundUi()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.start(this)
        refreshNotificationStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A launcher may reuse an existing task instead of starting a new activity; the shortcut
        // hint then arrives here rather than in onCreate.
        showShortcutTab(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Republished on every cold start so a launcher that dropped them (or an app that was
        // updated) gets the current labels; failures are swallowed inside.
        AppShortcuts.publish(this)
        if (savedInstanceState == null) showShortcutTab(intent)
        setContent {
            val savedUi = rememberSaveableStateHolder()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    ComposeView(context).also { view ->
                        contentView = view
                        view.setContent {
                            savedUi.SaveableStateProvider("beacon-ui") {
                                BeaconApp(
                                    viewModel,
                                    notificationPermission,
                                    notificationHint,
                                    onRequestNotificationPermission = {
                                        notificationPermissionRequest.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    },
                                    onNotificationSettingsUnavailable = {
                                        notificationHint =
                                            R.string.settings_open_notification_settings_hint
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}
