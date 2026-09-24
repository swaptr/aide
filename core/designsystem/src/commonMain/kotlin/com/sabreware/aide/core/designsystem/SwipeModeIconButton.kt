package com.sabreware.aide.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A circular icon button you **swipe vertically to switch modes** and **tap to act** on the current
 * one. The switch animates as a directional slide — the outgoing glyph slides off the way you swiped,
 * the next slides in from the opposite edge (clipped to the circle) — so the change reads as the button
 * itself moving, not the glyph swapping in place.
 *
 * Architecture follows Android's gesture guidance (build on the highest-level modifier that fits, drop
 * down only for the gap):
 *  - [clickable] owns the **tap** — and with it ripple, focus, keyboard, and the accessibility activate
 *    action a raw `pointerInput` would silently drop. It's gated by [enabled] for the current mode.
 *  - One [pointerInput] adds only what clickable can't: a **vertical swipe**. It `consume()`s the change
 *    once the drag crosses touch slop so clickable cancels its tap for that gesture. Horizontal drags
 *    are ignored (vertical only) so it never fights a horizontal parent scroll.
 *  - The swipe is also a **custom accessibility action** (swiping a small target is unusable for
 *    TalkBack), and [stateDescription] announces the current mode.
 *  - `pointerInput(Unit)` + [rememberUpdatedState] keep the gesture coroutine from being recreated each
 *    recomposition while still invoking the latest callbacks.
 *
 * **Controlled**: the caller owns [mode] and updates it from [onModeChange]. The component never tints
 * or dims on its own — [content] is told whether the rendered mode `isEnabled` and decides the look, so
 * a host can apply its own disabled/active styling.
 *
 * @param mode the currently selected mode.
 * @param modes all modes in cycle order; swipe up advances to the next, down to the previous (wraps).
 * @param onModeChange invoked with the newly selected mode when a swipe (or the a11y action) switches.
 * @param onTap invoked when the current mode is tapped (fires only while [enabled] of [mode] is true).
 * @param enabled whether a given mode is tappable; the current mode's value gates the tap and is handed
 *   to [content].
 * @param content draws the visual for a mode; `isEnabled` is that mode's [enabled] value.
 */
@Composable
fun <T> SwipeModeIconButton(
    mode: T,
    modes: List<T>,
    onModeChange: (T) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    size: Dp = 48.dp,
    onClickLabel: String? = null,
    stateLabel: String? = null,
    switchActionLabel: String = "Switch mode",
    transitionMillis: Int = 260,
    content: @Composable (mode: T, isEnabled: Boolean) -> Unit,
) {
    // Latest-snapshots so the once-started gesture loop and the a11y action always act on current state.
    val latestModes by rememberUpdatedState(modes)
    val latestMode by rememberUpdatedState(mode)
    val latestOnModeChange by rememberUpdatedState(onModeChange)
    // Direction of the most recent switch, so the slide matches the swipe (up = next, slides upward).
    var lastForward by remember { mutableStateOf(true) }

    fun switch(forward: Boolean) {
        lastForward = forward
        val list = latestModes
        val i = list.indexOf(latestMode)
        if (i < 0 || list.isEmpty()) return
        val next = list[(i + (if (forward) 1 else -1) + list.size) % list.size]
        if (next != latestMode) latestOnModeChange(next)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Leave the down UNCONSUMED so clickable still sees a tap; consume only once a
                    // vertical-dominant drag past slop turns the gesture into a mode switch.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val d = change.position - down.position
                        if (d.getDistance() > slop) {
                            if (abs(d.y) > abs(d.x)) {
                                switch(forward = d.y < 0) // swipe up = forward
                                change.consume()
                            }
                            break
                        }
                        if (change.changedToUp() || !change.pressed) break
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = size / 2),
                enabled = enabled(mode),
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onTap,
            )
            .semantics {
                if (stateLabel != null) stateDescription = stateLabel
                customActions = listOf(
                    CustomAccessibilityAction(label = switchActionLabel) { switch(forward = true); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = mode,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val up = lastForward
                (slideInVertically(tween(transitionMillis)) { h -> if (up) h else -h } + fadeIn(tween(transitionMillis))) togetherWith
                    (slideOutVertically(tween(transitionMillis)) { h -> if (up) -h else h } + fadeOut(tween(transitionMillis)))
            },
            label = "swipe-mode",
        ) { m ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                content(m, enabled(m))
            }
        }
    }
}
