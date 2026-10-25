package io.github.beacon.app.widget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beacon.app.data.local.StateCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class WidgetEditorSnapshot(val rows: List<JSONObject>, val stored: String)
internal data class WidgetSaveResult(val raw: String? = null, val warning: String? = null, val failed: Boolean = false)

/** The editor never opens SQLite or rasterizes desktop RemoteViews on the main thread. */
internal class WidgetEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow<WidgetEditorSnapshot?>(null)
    val snapshot = mutable.asStateFlow()
    private val mutableFailure = MutableStateFlow(false)
    val failed = mutableFailure.asStateFlow()
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableSaveResult = MutableStateFlow<WidgetSaveResult?>(null)
    val saveResult = mutableSaveResult.asStateFlow()
    private var reading: Job? = null

    fun reload(id: Int) {
        mutableFailure.value = false
        reading?.cancel()
        reading = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    StateCache(getApplication()).use { WidgetEditorSnapshot(it.all(), it.preference("widget:$id")) }
                }
                mutable.value = result
                mutableFailure.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableFailure.value = true
            }
        }
    }

    fun consumeSaveResult(result: WidgetSaveResult) {
        if (mutableSaveResult.value == result) mutableSaveResult.value = null
    }

    fun save(id: Int, raw: String) {
        if (mutableSaving.value) return
        mutableSaving.value = true
        viewModelScope.launch {
            try {
                val warning = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    StateCache(context).use { it.preference("widget:$id", raw) }
                    try {
                        BeaconStateWidget.refresh(context, intArrayOf(id))
                        null
                    } catch (_: Exception) {
                        "设置已保存，桌面刷新未完成，可在小组件管理中下拉重试。"
                    }
                }
                mutableSaveResult.value = WidgetSaveResult(raw, warning)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableSaveResult.value = WidgetSaveResult(failed = true)
            } finally {
                mutableSaving.value = false
            }
        }
    }
}
