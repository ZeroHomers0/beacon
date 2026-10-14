package io.github.beacon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun BeaconTimePreference(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BeaconText(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            key(value) {
                val parts = value.split(":")
                val hours =
                    rememberLazyListState(
                        initialFirstVisibleItemIndex =
                            (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 23)
                    )
                val minutes =
                    rememberLazyListState(
                        initialFirstVisibleItemIndex =
                            (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
                    )
                val latestChange by rememberUpdatedState(onChange)
                LaunchedEffect(hours, minutes, enabled) {
                    if (enabled)
                        snapshotFlow {
                            if (
                                hours.isScrollInProgress ||
                                    minutes.isScrollInProgress ||
                                    hours.layoutInfo.visibleItemsInfo.isEmpty() ||
                                    minutes.layoutInfo.visibleItemsInfo.isEmpty()
                            )
                                null
                            else
                                String.format(
                                    Locale.ROOT,
                                    "%02d:%02d",
                                    wheelSelection(hours),
                                    wheelSelection(minutes),
                                )
                        }
                            .collect { selected ->
                                if (selected != null && selected != value) latestChange(selected)
                            }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TimeNumberWheel(
                        hours,
                        24,
                        stringResource(R.string.time_picker_hour),
                        "$label-hour-wheel",
                        Modifier.weight(1f),
                        enabled,
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BeaconText(" ", style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.height(96.dp), contentAlignment = Alignment.Center) {
                            BeaconText(":", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    TimeNumberWheel(
                        minutes,
                        60,
                        stringResource(R.string.time_picker_minute),
                        "$label-minute-wheel",
                        Modifier.weight(1f),
                        enabled,
                    )
                }
            }
        }
    }
}

private fun wheelSelection(state: LazyListState): Int {
    val layout = state.layoutInfo
    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
        ?: state.firstVisibleItemIndex
}

@Composable
private fun TimeNumberWheel(
    state: LazyListState,
    count: Int,
    label: String,
    tag: String,
    modifier: Modifier,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    val selection by remember(state) { derivedStateOf { wheelSelection(state) } }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BeaconText(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth()
                    .height(32.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp),
                    )
            )
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().testTag(tag),
                contentPadding = PaddingValues(vertical = 32.dp),
                flingBehavior = rememberSnapFlingBehavior(state),
                userScrollEnabled = enabled,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items((0 until count).toList(), key = { it }) { number ->
                    val text = String.format(Locale.ROOT, "%02d", number)
                    Box(
                        Modifier.fillMaxWidth()
                            .height(32.dp)
                            .clickable(enabled = enabled) {
                                scope.launch { state.animateScrollToItem(number) }
                            }
                            .semantics {
                                selected = number == selection
                                contentDescription = "$label $text"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BeaconText(
                            text,
                            style = MaterialTheme.typography.titleMedium,
                            color =
                                if (number == selection)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}
