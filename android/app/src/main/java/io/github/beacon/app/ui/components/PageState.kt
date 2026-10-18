package io.github.beacon.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.beacon.app.R
import io.github.beacon.app.domain.model.AsyncPageState

/** What a page actually paints; refresh states keep painting the content underneath. */
private enum class PagePhase {
    LOADING,
    EMPTY,
    ERROR,
    CONTENT,
}

/**
 * Paints exactly one phase of an async page and crossfades between them.
 *
 * Pages used to encode loading by replacing their own section title and encode failure by appending
 * coloured text under a card, which made the same four states look like four different products.
 * The phases here are derived from [AsyncPageState] only, so a page cannot drift back to its own
 * variant, and the swap uses the shared motion token instead of each screen's default.
 *
 * Refresh states deliberately fall through to [content]: a refresh keeps the old content on screen
 * and reports itself inside the page, which is what the message list and the admin lists do.
 */
@Composable
internal fun PageStateHost(
    state: AsyncPageState,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { PageStateLoading() },
    empty: @Composable () -> Unit = {},
    error: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val phase =
        when (state) {
            AsyncPageState.IDLE,
            AsyncPageState.INITIAL_LOADING -> PagePhase.LOADING
            AsyncPageState.INITIAL_ERROR -> PagePhase.ERROR
            AsyncPageState.EMPTY -> PagePhase.EMPTY
            AsyncPageState.CONTENT,
            AsyncPageState.REFRESHING,
            AsyncPageState.REFRESH_ERROR -> PagePhase.CONTENT
        }
    Crossfade(
        targetState = phase,
        modifier = modifier,
        animationSpec = tween(BeaconMotion.StandardMs),
        label = "page-state",
    ) { shown ->
        when (shown) {
            PagePhase.LOADING -> loading()
            PagePhase.EMPTY -> empty()
            PagePhase.ERROR -> error()
            PagePhase.CONTENT -> content()
        }
    }
}

/** The single first-load placeholder: a small spinner over one line of copy. */
@Composable
internal fun PageStateLoading(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.settings_data_loading),
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = BeaconUiMetrics.SectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BeaconUiMetrics.Gap8),
    ) {
        CircularProgressIndicator(
            Modifier.size(BeaconUiMetrics.IconMedium),
            strokeWidth = 2.dp,
        )
        BeaconText(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A page-level failure the reader can retry.
 *
 * It reuses the notice surface so a failed read looks the same whether it belongs to a card or to a
 * whole page, and it never shows the internal status string that used to leak into these spots.
 */
@Composable
internal fun PageStateFailure(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    SettingsNotice(
        text = text,
        modifier = modifier,
        actionLabel = actionLabel ?: onRetry?.let { stringResource(R.string.action_retry) },
        onAction = onRetry,
    )
}
