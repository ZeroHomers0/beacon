package io.github.beacon.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import io.github.beacon.app.R
import io.github.beacon.app.data.local.UiStyleStore
import io.github.beacon.app.domain.model.BeaconThemeMode
import io.github.beacon.app.domain.model.BeaconUiStyle
import io.github.beacon.app.domain.model.MessageKey
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.domain.model.ReceivePlan
import io.github.beacon.app.domain.model.RuntimeState
import io.github.beacon.app.feature.settings.ConnectionFormState
import io.github.beacon.app.ipc.RuntimeActions
import io.github.beacon.app.ui.components.SettingsScreen
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Page state owned by [BeaconViewModel] instead of the composable.
 *
 * Fields the UI used to keep in `rememberSaveable` write through to [SavedStateHandle], so they
 * still survive process death. Transient dialog state stays ordinary Compose state. Appearance
 * settings are backed by [UiStyleStore] because they outlive the process on their own.
 */
class BeaconUiState(
    handle: SavedStateHandle,
    private val styleStore: UiStyleStore,
) {
    /** Page titles as resource ids; the composable that renders them resolves the text. */
    val pages =
        listOf(
            R.string.ui_nav_messages_title,
            R.string.ui_nav_settings_title,
        )

    val connectionForm = ConnectionFormState()
    val receptionForm = io.github.beacon.app.feature.settings.ReceptionFormState()
    val notificationForm = io.github.beacon.app.feature.settings.NotificationFormState()
    var pendingNavigation by mutableStateOf<(() -> Unit)?>(null)
    var shouldConfirmExit: (SettingsScreen) -> Boolean = { false }
    var discardDrafts: () -> Unit = {}

    fun requestNavigation(destination: SettingsScreen, action: () -> Unit) {
        if (shouldConfirmExit(destination)) pendingNavigation = action else action()
    }

    fun discardAndContinue() {
        val action = pendingNavigation
        pendingNavigation = null
        discardDrafts()
        action?.invoke()
    }

    val quietHoursForm = io.github.beacon.app.feature.settings.QuietHoursFormState()
    val updateState = io.github.beacon.app.feature.settings.UpdateCheckState()

    private val uiStyleState = mutableStateOf(styleStore.load())

    var uiStyle: BeaconUiStyle
        get() = uiStyleState.value
        set(value) {
            uiStyleState.value = value
            styleStore.save(value)
        }

    private val themeModeState = mutableStateOf(styleStore.loadMode())

    var themeMode: BeaconThemeMode
        get() = themeModeState.value
        set(value) {
            themeModeState.value = value
            styleStore.saveMode(value)
        }

    private val receivePlanState = mutableStateOf(ReceivePlan())
    var receivePlan: ReceivePlan
        get() = receivePlanState.value
        set(value) {
            receivePlanState.value = value
        }

    var receiveModesOpen by mutableStateOf(false)
    var readingKey: MessageKey? = null
    var readingOffset: Int = 0
    var restoreReadingPosition by mutableStateOf(false)

    init {
        handle["page"] = (handle.get<Int>("page") ?: 0).coerceIn(0, 1)
    }

    var page: Int by savedState(handle, "page", 0)
    var search: String by savedState(handle, "search", "")
    var readFilter: MessageReadFilter by savedState(handle, "readFilter", MessageReadFilter.ALL)
    var selectionMode: Boolean by savedState(handle, "selectionMode", false)
    var selectedKeys: List<MessageKey> by savedState(handle, "selectedKeys", emptyList())
    var detailKey: MessageKey? by savedState(handle, "detailKey", null)
    var pendingNotification: MessageKey? by savedState(handle, "pendingNotification", null)
    var pendingDelete: List<MessageKey>? by savedState(handle, "pendingDelete", null)
    var selectionHint: String by savedState(handle, "selectionHint", "")
    var settingsScreen: SettingsScreen by savedState(handle, "settingsScreen", SettingsScreen.HOME)
    var settingsHistory: ArrayList<SettingsScreen> by
        savedState(handle, "settingsHistory", arrayListOf())
    var settingsNavigatingBack by mutableStateOf(false)
    val settingsBackTarget: SettingsScreen
        get() = settingsHistory.lastOrNull() ?: SettingsScreen.HOME

    fun navigateSettings(destination: SettingsScreen) {
        if (destination == settingsScreen) return
        requestNavigation(destination) {
            invalidateQuietHoursOnExit(destination)
            settingsNavigatingBack = destination == SettingsScreen.HOME
            settingsHistory =
                if (destination == SettingsScreen.HOME) arrayListOf()
                else ArrayList(settingsHistory + settingsScreen)
            settingsScreen = destination
        }
    }

    fun backSettings() {
        requestNavigation(settingsBackTarget) {
            settingsNavigatingBack = true
            invalidateQuietHoursOnExit(settingsBackTarget)
            settingsScreen = settingsBackTarget
            settingsHistory = ArrayList(settingsHistory.dropLast(1))
        }
    }

    fun finishNavigation(index: Int) {
        if (page == 1 && index == 1 && settingsScreen != SettingsScreen.HOME) backSettings()
        else
            requestNavigation(SettingsScreen.HOME) {
                invalidateQuietHoursOnExit(SettingsScreen.HOME)
                page = index
                settingsHistory = arrayListOf()
                settingsScreen = SettingsScreen.HOME
            }
    }

    private fun invalidateQuietHoursOnExit(destination: SettingsScreen) {
        val pages = setOf(SettingsScreen.NOTIFICATION_RULES, SettingsScreen.QUIET_HOURS)
        // Preserve the shared draft only within this editor. Re-entering from elsewhere rereads
        // the runtime, including policy changes made by importing a backup.
        if (settingsScreen in pages && destination !in pages) {
            quietHoursForm.loaded = false
            notificationForm.loaded = false
        }
    }

    var filtersOpen: Boolean by savedState(handle, "filtersOpen", false)

    init {
        if (pendingNotification == null && page == 0) pendingNotification = detailKey
    }

    fun closeDetail(actions: RuntimeActions) {
        detailKey = null
        pendingNotification = null
        actions.closeDetail()
    }

    var operationDetails by mutableStateOf<String?>(null)

    fun applyFilter(
        actions: RuntimeActions,
        text: String,
        filter: MessageReadFilter,
    ) {
        selectedKeys = emptyList()
        selectionMode = false
        selectionHint = ""
        search = text
        readFilter = filter
        actions.readMessages(search = text, readFilter = filter)
    }

    fun connectionDirty(runtime: RuntimeState): Boolean =
        connectionForm.connectionEdited &&
            (connectionForm.endpoint.trim() != runtime.configuredEndpoint ||
                connectionForm.bindingCode.isNotEmpty())

    fun navigate(actions: RuntimeActions, runtime: RuntimeState, index: Int) {
        closeDetail(actions)
        finishNavigation(index)
    }

    private class SavedStateProperty<T>(
        private val handle: SavedStateHandle,
        private val key: String,
        default: T,
    ) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(handle.get<T>(key) ?: default)

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            handle[key] = value
        }
    }

    private fun <T> savedState(handle: SavedStateHandle, key: String, default: T) =
        SavedStateProperty(handle, key, default)
}
