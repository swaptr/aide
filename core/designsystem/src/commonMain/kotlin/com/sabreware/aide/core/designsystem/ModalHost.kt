package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/**
 * The layer every modal ([AppDialog], sheet or centered dialog) draws in: a stack IN THE APP'S OWN WINDOW, over
 * [content]. Installed once by [com.sabreware.aide.core.designsystem.theme.AideTheme], so every root has one.
 *
 * Modals used to open a platform `Dialog` — a second window with its own composition, insets and frame
 * pacing — so the same page animated differently in a sheet than on a screen. In one window, a page is a page
 * wherever it is drawn; the modal decides only where it sits. While any modal is up, the content underneath
 * is hidden from accessibility (the scrim already takes its touches), as a dialog window's would be.
 *
 * Focus follows the same rule a dialog window gave us for free: the moment a modal covers content, that content
 * gives up focus (so the keyboard it held goes down and typing cannot land under the sheet), and
 * [LocalCoveredByModal] tells any auto-focus engine beneath to stand down until the modal leaves.
 */
@Composable
fun ModalHost(content: @Composable () -> Unit) {
    val host = remember { ModalHostState() }
    CompositionLocalProvider(LocalModalHost provides host) {
        Box(Modifier.fillMaxSize()) {
            val covered = host.layers.isNotEmpty()
            Covered(covered, Modifier.semantics { if (covered) hideFromAccessibility() }, content)
            // Opened order is drawing order: a confirm opened from a sheet sits above it, and covers it.
            host.layers.forEachIndexed { index, layer ->
                key(layer.id) {
                    CompositionLocalProvider(layer.locals) {
                        Covered(index < host.layers.lastIndex, Modifier, layer.content)
                    }
                }
            }
        }
    }
}

/**
 * True while a modal sits over this content. An auto-focus engine (the chat composer's) must not take focus or
 * raise the keyboard while it is: the user is in the modal.
 */
val LocalCoveredByModal = compositionLocalOf { false }

/** [content] that gives up focus, and its keyboard, the moment something covers it. */
@Composable
private fun Covered(covered: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(covered) {
        // Only focus that is IN the covered content: the modal's own field may already have taken it.
        if (covered && hasFocus) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    CompositionLocalProvider(LocalCoveredByModal provides covered) {
        Box(modifier.fillMaxSize().onFocusChanged { hasFocus = it.hasFocus }) { content() }
    }
}

/**
 * Draws [content] in the nearest [ModalHost], above the app, for as long as this call stays composed. The
 * content keeps the CALLER's composition locals (theme, navigator, view-model owner, saved state), so it
 * behaves exactly as if it were composed here.
 */
@Composable
internal fun ModalLayer(content: @Composable () -> Unit) {
    val host = checkNotNull(LocalModalHost.current) { "A modal needs a ModalHost; AideTheme installs one at every root." }
    val locals = currentCompositionLocalContext
    val latest by rememberUpdatedState(content)
    val layer = remember(host) { ModalLayerEntry(host.nextId++, locals) { latest() } }
    layer.locals = locals
    DisposableEffect(host, layer) {
        host.layers += layer
        onDispose { host.layers -= layer }
    }
}

@Stable
private class ModalHostState {
    val layers = mutableStateListOf<ModalLayerEntry>()
    var nextId = 0
}

@Stable
private class ModalLayerEntry(val id: Int, locals: CompositionLocalContext, val content: @Composable () -> Unit) {
    var locals by mutableStateOf(locals)
}

private val LocalModalHost = staticCompositionLocalOf<ModalHostState?> { null }
