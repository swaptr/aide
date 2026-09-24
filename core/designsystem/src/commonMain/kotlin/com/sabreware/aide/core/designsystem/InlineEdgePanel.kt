package com.sabreware.aide.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Which screen edge an [InlineEdgePanel] docks to — drives both which way it shoves sibling content and
 *  which system-bar inset it bakes in. */
enum class PanelEdge { Top, Bottom }

/**
 * Docked-edge motion. Fade is half the duration on purpose — full opacity is reached while the height is
 * still expanding, else the strip looks wiped in rather than grown.
 */
internal const val EdgePanelMotionMs = 260

internal fun edgePanelEnter(edge: PanelEdge): EnterTransition =
    expandVertically(
        animationSpec = tween(EdgePanelMotionMs, easing = FastOutSlowInEasing),
        expandFrom = if (edge == PanelEdge.Top) Alignment.Top else Alignment.Bottom,
    ) + fadeIn(animationSpec = tween(durationMillis = EdgePanelMotionMs / 2, delayMillis = 40))

internal fun edgePanelExit(edge: PanelEdge): ExitTransition =
    shrinkVertically(
        animationSpec = tween(EdgePanelMotionMs, easing = FastOutSlowInEasing),
        shrinkTowards = if (edge == PanelEdge.Top) Alignment.Top else Alignment.Bottom,
    ) + fadeOut(animationSpec = tween(durationMillis = EdgePanelMotionMs / 2))

/**
 * A non-modal, *displacing* edge panel — the inline counterpart to [AppDialog], deliberately **flat** (a
 * full-width strip with square corners, flush to its edge — the "Set up a model to begin" language, not a
 * rounded floating sheet). It is not a `Dialog` and draws no scrim; it takes part in the normal layout pass,
 * so place it at the matching screen edge (a `Scaffold` `topBar`/`bottomBar`, or the first/last child of a
 * weighted `Column`) and when [visible] flips on it grows from its [edge] and pushes neighbouring content
 * away rather than covering it.
 *
 * **Inset management is baked in by [edge]** — that's the whole point of choosing an edge: a [PanelEdge.Top]
 * panel sits *under the status bar* (its surface bleeds to the very top, its content is inset below the
 * status bar); a [PanelEdge.Bottom] panel sits *under the nav bar* the same way. So a caller docks it to an
 * edge and gets correct edge-to-edge behaviour for free — no inset wiring at the call site. (It must
 * therefore be placed at the real screen edge, outside any parent that already consumed that bar.)
 */
@Composable
fun InlineEdgePanel(
    visible: Boolean,
    edge: PanelEdge,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fromTop = edge == PanelEdge.Top
    // The system bar for this edge: padded into the content (so text clears it) while the Surface bg bleeds
    // under it to the screen edge.
    val barInsets = if (fromTop) WindowInsets.statusBars else WindowInsets.navigationBars
    AnimatedVisibility(
        visible = visible,
        // Grow out of the docked edge (+ fade) so the panel slides into place and shoves the sibling content
        // aside; reverse on hide. The height animation carries the reflow.
        enter = edgePanelEnter(edge),
        exit = edgePanelExit(edge),
    ) {
        // Flat: Surface's default RectangleShape — square corners, flush full width, bg bleeding under the
        // system bar; the inset padding keeps the content itself clear of it.
        Surface(modifier = modifier.fillMaxWidth(), color = color) {
            Column(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(barInsets),
                content = content,
            )
        }
    }
}
