package io.github.beacon.app.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Keep a bounded position history without remounting the page or restarting its data source. */
@Composable
internal fun rememberScopedListState(key: String, initial: LazyListState): LazyListState {
    val positions = rememberSaveable(saver = listSaver<MutableMap<String, LazyListState>, Any>(
        save = { states -> states.entries.flatMap { (name, state) ->
            listOf(name, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        } },
        restore = { values -> values.chunked(3).associate { entry ->
            entry[0] as String to LazyListState(entry[1] as Int, entry[2] as Int)
        }.toMutableMap() },
    )) { linkedMapOf<String, LazyListState>() }
    return positions.getOrPut(key) {
        if (positions.isEmpty()) initial else {
            if (positions.size >= 12) positions.remove(positions.keys.first())
            LazyListState()
        }
    }
}
