package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Max width for menu / form / detail content. On a wide window the content column caps here and centers
 * instead of stretching edge-to-edge (which reads as broken on desktop/tablet). Baked into [AppPage] and
 * [PageScaffold]'s screen chrome, so ordinary screens get it for free; fill-height screens that own their
 * scroll opt in with [ConstrainedContent].
 */
val ContentMaxWidth: Dp = 720.dp

/** Max width for chat prose — the message column + composer cap here and center (see `ChatScaffold`) while
 *  the background and bars stay full-bleed, so lines don't run painfully long on a wide window. */
val ReadingMaxWidth: Dp = 760.dp

/**
 * Wrapper for a fill-height screen that owns its own scroll (a `LazyColumn`/pager under [AppScaffold], which
 * [AppPage]/[PageScaffold] don't wrap). Keeps the scaffold's inset `contentModifier` on a full-bleed box and
 * hands the body a capped, centered fill-height modifier — so the list stops stretching on a wide window.
 *
 * ```
 * AppScaffold(...) { scaffoldModifier ->
 *     ConstrainedContent(scaffoldModifier) { contentModifier -> LazyColumn(modifier = contentModifier) { … } }
 * }
 * ```
 */
@Composable
fun ConstrainedContent(
    contentModifier: Modifier,
    max: Dp = ContentMaxWidth,
    content: @Composable (Modifier) -> Unit,
) {
    Box(modifier = contentModifier, contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = max).fillMaxSize())
    }
}
