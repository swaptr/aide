package com.sabreware.aide.core.designsystem.state

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.Placeholder
import kotlinx.coroutines.delay

/** The one motion spec for loading→content→error swaps, matching the app's other 180ms crossfades. */
const val StateMotionMs = 180

/**
 * How long a load may run before its loading visual appears. Most loads here are disk-local and finish
 * inside this window; drawing a skeleton for them is a flash, not information. A load that lands first
 * fades from an empty pane straight into content.
 */
const val LoadingRevealDelayMs = 150L

private enum class Phase { Loading, Ready, Failed }

/**
 * The standard host for a [UiState]-driven pane: cross-fades between loading, failed, and ready content
 * with the app-wide [StateMotionMs] motion, so every async surface transitions identically. Value updates
 * within Ready recompose in place (no re-fade — the fade keys on the phase, not the value).
 *
 * Defaults render the shared visual language: [PaneLoading] (centered spinner) and [PaneFailed]
 * (placeholder-shaped error). Pass `loading = { … }` for skeleton-shaped surfaces. For LazyColumn
 * item-level states (rows inside a list), branch on the [UiState] directly and use the skeleton
 * primitives — a pane host can't wrap individual items.
 */
@Composable
fun <T> StatePane(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { PaneLoading() },
    failed: @Composable (String) -> Unit = { PaneFailed(it) },
    ready: @Composable (T) -> Unit,
) {
    // Latest per-phase payloads survive the cross-fade: while a pane fades out, [state] has already moved
    // on to the next phase, so the exiting pane renders from these instead of the live state.
    var lastReady by remember { mutableStateOf<UiState.Ready<T>?>(null) }
    var lastFailed by remember { mutableStateOf<UiState.Failed?>(null) }
    when (state) {
        is UiState.Ready -> lastReady = state
        is UiState.Failed -> lastFailed = state
        UiState.Loading -> Unit
    }
    val phase = when (state) {
        UiState.Loading -> Phase.Loading
        is UiState.Ready -> Phase.Ready
        is UiState.Failed -> Phase.Failed
    }
    Crossfade(
        targetState = phase,
        animationSpec = tween(StateMotionMs),
        modifier = modifier,
        label = "statePane",
    ) { current ->
        when (current) {
            Phase.Loading -> DelayedReveal(content = loading)
            Phase.Failed -> failed(lastFailed?.message.orEmpty())
            Phase.Ready -> lastReady?.let { ready(it.value) }
        }
    }
}

/** Composes [content] only once [LoadingRevealDelayMs] has passed — a fast load never shows it at all. */
@Composable
private fun DelayedReveal(content: @Composable () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LoadingRevealDelayMs)
        revealed = true
    }
    if (revealed) content()
}

/** Default full-pane loading: a centered indeterminate spinner. */
@Composable
fun PaneLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Default full-pane failure: placeholder-shaped, so error panes read like the app's empty states. */
@Composable
fun PaneFailed(message: String, modifier: Modifier = Modifier) {
    Placeholder(
        modifier = modifier.fillMaxSize(),
        title = "Something went wrong",
        subtitle = message.ifBlank { null },
    )
}
