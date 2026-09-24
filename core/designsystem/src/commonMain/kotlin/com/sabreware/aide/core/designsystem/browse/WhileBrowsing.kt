package com.sabreware.aide.core.designsystem.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.ChromeMotion

/**
 * Content a collection page shows only while plainly browsing — its action tiles, a connection's action rail,
 * a New chat button — folded away with [ChromeMotion] while the user searches or selects. The ONE place that
 * rule and its motion live: a page never writes `if (!selection.active && !browse.searching)` itself, which
 * popped the tiles in and out with no animation and let each page drift from the next.
 */
@Composable
fun WhileBrowsing(
    browse: BrowseState,
    selection: SelectionState?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = !browse.searching && selection?.active != true,
        modifier = modifier,
        enter = ChromeMotion.enter,
        exit = ChromeMotion.exit,
    ) { content() }
}
