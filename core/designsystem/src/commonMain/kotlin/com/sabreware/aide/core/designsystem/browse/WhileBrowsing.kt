package com.sabreware.aide.core.designsystem.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import com.sabreware.aide.core.designsystem.ChromeMotion

/**
 * Content a collection page shows only while plainly browsing — its action tiles, a connection's action rail,
 * a New chat button — folded away with [ChromeMotion] while the user searches or selects. The ONE place that
 * rule and its motion live: a page never writes `if (!selection.active && !browse.searching)` itself, which
 * popped the tiles in and out with no animation and let each page drift from the next.
 *
 * Folded away it still keeps one pixel of height. A lazy list skips a zero-height item when it anchors its
 * scroll, so the first visible item would become the row BELOW the tiles, and the tiles would unfold above
 * the viewport, hidden until the user pulled the list down. One pixel keeps them the anchor at the top.
 */
@Composable
fun WhileBrowsing(
    browse: BrowseState,
    selection: SelectionState?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, maxOf(placeable.height, 1)) { placeable.place(0, 0) }
        },
    ) {
        AnimatedVisibility(
            visible = !browse.searching && selection?.active != true,
            enter = ChromeMotion.enter,
            exit = ChromeMotion.exit,
        ) { content() }
    }
}
