package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A scrollable tab row over a horizontally-swipeable pager — the standard Compose pattern (shared
 * [androidx.compose.foundation.pager.PagerState]; tabs drive the pager via `animateScrollToPage`,
 * the pager's settled page drives the selected tab). This is a **fill-height** component: the pager
 * takes the remaining height after the tab row (`weight(1f)`) and each page owns its own scroll
 * (e.g. a `LazyColumn`), so long lists stay lazy. Host it in a fill-height parent (see `AppScaffold`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTabbedContent(
    tabs: List<String>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    edgePadding: Dp = 0.dp,
    containerColor: Color = Color.Transparent,
    pageContent: @Composable (page: Int) -> Unit,
) {
    if (tabs.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex, pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(safeIndex) {
        if (pagerState.currentPage != safeIndex) pagerState.animateScrollToPage(safeIndex)
    }
    // The collector outlives recompositions (keyed on the pager), so it must read the LATEST index and
    // callback: comparing against the first composition's index silently dropped a swipe back to a tab the
    // host had already moved off, leaving the saved tab and the shown tab out of step.
    val latestIndex by rememberUpdatedState(safeIndex)
    val latestOnSelect by rememberUpdatedState(onSelectIndex)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> if (page != latestIndex) latestOnSelect(page) }
    }

    val tabContent: @Composable () -> Unit = {
        tabs.forEachIndexed { index, label ->
            Tab(
                selected = index == pagerState.currentPage,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(label, maxLines = 1) },
            )
        }
    }
    val indicator: @Composable TabIndicatorScope.() -> Unit = { PagerTabIndicator(pagerState) }
    // The row can be narrower than its tabs (always on a phone, and on a wide window whose content column is
    // capped): a hard clip cut a tab mid-word at either edge. Fade whichever edge still has tabs past it.
    val tabScroll = rememberScrollState()
    Column(modifier = modifier) {
        if (tabs.size <= MaxFixedTabs) {
            // Few tabs span the full width edge to edge, equal shares — a scrollable row would bunch them
            // at the start and leave dead space at the end.
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = containerColor,
                indicator = indicator,
                tabs = tabContent,
            )
        } else {
            SecondaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.horizontalFadingEdges(tabScroll),
                scrollState = tabScroll,
                edgePadding = edgePadding,
                containerColor = containerColor,
                indicator = indicator,
                tabs = tabContent,
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            pageContent(page)
        }
    }
}

/**
 * The selected tab's line, placed straight from the pager's position: under the settled tab at rest, sliding
 * and resizing between two tabs as a swipe crosses them. Nothing animates on its own, so the first frame
 * already sits under the selected tab (the stock animated indicator drew the full row before tab positions
 * arrived), and the pager is read in the layout phase only, so a swipe moves the line without recomposing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabIndicatorScope.PagerTabIndicator(pager: PagerState) {
    TabRowDefaults.SecondaryIndicator(
        Modifier.tabIndicatorLayout { measurable, constraints, positions ->
            if (positions.isEmpty()) return@tabIndicatorLayout layout(0, 0) {}
            val page = pager.currentPage.coerceIn(positions.indices)
            val fraction = pager.currentPageOffsetFraction
            val from = positions[page]
            val to = positions[(if (fraction > 0f) page + 1 else page - 1).coerceIn(positions.indices)]
            val width = lerp(from.width, to.width, abs(fraction)).roundToPx()
            val left = lerp(from.left, to.left, abs(fraction)).roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(left, 0) }
        },
    )
}

/**
 * Fades the start and end of a horizontally scrolling row wherever content continues past that edge, so a
 * cut-off item reads as "more this way" instead of a clipped word. The scroll position is read in the draw
 * phase only, so scrolling redraws the mask without recomposing or re-laying-out the row.
 */
fun Modifier.horizontalFadingEdges(state: ScrollState, length: Dp = FadingEdgeLength): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = length.toPx().coerceAtMost(size.width / 2)
            if (state.value > 0) {
                drawRect(
                    brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.value < state.maxValue) {
                val start = size.width - fade
                drawRect(
                    brush = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = start, endX = size.width),
                    topLeft = Offset(start, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

private val FadingEdgeLength = 32.dp

/** Up to this many tabs fill the row's width; more scroll. Two: at three, a large font already clips a label. */
private const val MaxFixedTabs = 2
