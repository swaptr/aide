package com.sabreware.aide.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.collectLatest

/**
 * Whether content here may take focus (and so raise the keyboard) on its own: nothing covers it
 * ([LocalCoveredByModal]), its page is the settled, RESUMED destination, [enabled] (the caller's own gates) holds,
 * AND all of that has held for [FocusSettleFrames] frames.
 *
 * The settle is the point. A modal says it covers content only once its layer is registered, a frame after it was
 * asked for; a sheet handing off to another (the "+" sheet opening the connector flow) leaves a frame with no
 * modal at all; a page moving into a modal scene composes afresh under a cover it has not heard of yet. Focus
 * taken in any of those frames raises a keyboard the cover then races to hide, and on Android the show can land
 * after the hide. Waiting out the settle means those frames never grant focus. A drop is immediate.
 *
 * Every auto-focus goes through this (directly, or through [AutoFocus]); never `LaunchedEffect(Unit) {
 * requestFocus() }`.
 */
@Composable
fun rememberCanTakeFocus(enabled: Boolean = true): State<Boolean> {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val open = enabled && !LocalCoveredByModal.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val latest = rememberUpdatedState(open)
    val settled = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest.value }.collectLatest { now ->
            settled.value = false
            if (now) {
                repeat(FocusSettleFrames) { withFrameNanos { } }
                settled.value = true
            }
        }
    }
    return remember { derivedStateOf { latest.value && settled.value } }
}

/**
 * Takes focus for [focusRequester] (and shows the keyboard when [showKeyboard]) the first time
 * [rememberCanTakeFocus] grants it: appearing is the request to type. Once only — a sheet closing over it later
 * does not raise the keyboard again, and it never releases focus (a cover takes it away itself, [ModalHost]).
 * Content that must follow the user's keyboard intent across covers (the chat composer) drives
 * [rememberCanTakeFocus] itself.
 */
@Composable
fun AutoFocus(focusRequester: FocusRequester, enabled: Boolean = true, showKeyboard: Boolean = true) {
    val canTakeFocus by rememberCanTakeFocus(enabled)
    val keyboard = LocalSoftwareKeyboardController.current
    var taken by remember { mutableStateOf(false) }
    LaunchedEffect(canTakeFocus) {
        if (canTakeFocus && !taken) {
            taken = true
            // Throws when the field's node is detaching (a page mid pop); the next grant finds a live one.
            runCatching { focusRequester.requestFocus() }
            if (showKeyboard) keyboard?.show()
        }
    }
}

/**
 * Frames every gate must hold before focus is granted: one for the scene holding a new cover to compose, one for
 * the cover's layer to register, one for the covered content to recompose with it. About 50 ms: below notice for
 * a page that opens to type.
 */
const val FocusSettleFrames = 3
