package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [marquee]'s rules on the test clock (no real sleeps): a name that fits never walks and never
 * registers; a cut-off name walks on its own clock once it has been fully in view for [MarqueeSettleMs] (plus
 * its stagger) and then loops endlessly, resting as an ellipsis between passes; a row only partly in view —
 * vertically OR horizontally — does not walk; a walk dies the moment ITS row leaves view by any means (other
 * rows keep walking) and re-arms when it comes back; and the host's registry holds only the in-view names.
 *
 * "Walking" is read off the text's own layout: at rest the text is measured in its slot, while walking it is
 * laid out unbounded on one line — so the test needs no hook in the component.
 */
@OptIn(ExperimentalTestApi::class)
class MarqueeTextTest {

    private val long = "A remarkably long name that could never fit inside a narrow list row at all"

    private fun SemanticsNodeInteraction.isWalking(): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return !results.single().layoutInput.constraints.hasBoundedWidth
    }

    private fun ComposeUiTest.walking(text: String) = onNodeWithText(text, useUnmergedTree = true).isWalking()

    private fun ComposeUiTest.frames(count: Int) = repeat(count) { mainClock.advanceTimeByFrame() }

    /** Long enough for any name's own clock (settle + the largest stagger) to have started its first pass. */
    private val warmUp = MarqueeSettleMs + MarqueeStaggerMs + 100

    @Test
    fun fittingTextNeverWalksNorRegisters() = runDesktopComposeUiTest(width = 400, height = 400) {
        mainClock.autoAdvance = false
        var gate: MarqueeGate? = null
        setContent {
            MarqueeHost {
                gate = LocalMarqueeGate.current
                Box { MarqueeText("Short", Modifier.width(300.dp)) }
            }
        }
        mainClock.advanceTimeBy(MarqueeSettleMs * 3)
        assertFalse(walking("Short"))
        assertEquals(0, gate!!.registered)
    }

    @Test
    fun overflowingTextWalksAfterSettleThenRests() = runDesktopComposeUiTest(width = 400, height = 400) {
        mainClock.autoAdvance = false
        setContent {
            MarqueeHost { Box { MarqueeText(long, Modifier.width(120.dp)) } }
        }
        mainClock.advanceTimeBy(MarqueeSettleMs / 2)
        assertFalse(walking(long), "walked before its clock ran out")
        mainClock.advanceTimeBy(warmUp)
        assertTrue(walking(long), "did not walk once in view long enough")
        // Endless: over a long stretch it keeps coming back from rest (the ellipsis) to walk again.
        var starts = 0
        var was = true
        repeat(480) {
            mainClock.advanceTimeBy(250)
            val now = walking(long)
            if (now && !was) starts++
            was = now
        }
        assertTrue(starts >= 2, "the loop is not endless (restarted $starts times in 2 minutes)")
    }

    /** A modal covers the page; when it leaves and nothing on the page moves, the page's rows still resume. */
    @Test
    fun rowsWalkAfterACoverEndsWithNoFurtherMovement() = runDesktopComposeUiTest(width = 400, height = 400) {
        mainClock.autoAdvance = false
        var gate: MarqueeGate? = null
        setContent {
            MarqueeHost {
                gate = LocalMarqueeGate.current
                Box { MarqueeText(long, Modifier.width(120.dp)) }
            }
        }
        runOnIdle { gate!!.cover(true) }
        mainClock.advanceTimeBy(warmUp)
        assertFalse(walking(long), "walked while covered")
        runOnIdle { gate!!.cover(false) }
        mainClock.advanceTimeBy(warmUp)
        assertTrue(walking(long), "did not walk once the cover ended")
    }

    @Test
    fun paragraphTextNeverWalks() = runDesktopComposeUiTest(width = 400, height = 400) {
        mainClock.autoAdvance = false
        setContent {
            MarqueeHost { Box { Text(long, Modifier.width(120.dp).marquee(), maxLines = 3) } }
        }
        mainClock.advanceTimeBy(MarqueeSettleMs * 3)
        assertFalse(walking(long))
    }

    @Test
    fun noHostNeverWalks() = runDesktopComposeUiTest(width = 400, height = 400) {
        mainClock.autoAdvance = false
        setContent { Box { MarqueeText(long, Modifier.width(120.dp)) } }
        mainClock.advanceTimeBy(MarqueeSettleMs * 3)
        assertFalse(walking(long))
    }

    @Test
    fun scrolledOutStopsAloneAndScrolledBackWalksAgainAfterSettle() = runDesktopComposeUiTest(400, 400) {
        mainClock.autoAdvance = false
        val scroll = ScrollState(0)
        lateinit var scope: CoroutineScope
        var gate: MarqueeGate? = null
        setContent {
            scope = rememberCoroutineScope()
            MarqueeHost {
                gate = LocalMarqueeGate.current
                // A plain scrolled column keeps the row composed off-screen, so only visibility can stop it.
                // A programmatic scroll never passes through nested scroll: the gate stays still throughout.
                Column(Modifier.fillMaxWidth().height(200.dp).verticalScroll(scroll)) {
                    MarqueeText("top $long", Modifier.width(120.dp).height(48.dp))
                    Spacer(Modifier.height(20.dp))
                    MarqueeText("low $long", Modifier.width(120.dp).height(48.dp))
                    Spacer(Modifier.height(1000.dp))
                }
            }
        }
        mainClock.advanceTimeBy(warmUp)
        assertTrue(walking("top $long"))
        assertTrue(walking("low $long"))
        assertEquals(2, gate!!.registered)

        // 60px pushes the top row (48dp) out while the lower one (at 68dp) stays wholly in view.
        runOnIdle { scope.launch { scroll.scrollTo(60) } }
        frames(2)
        assertFalse(walking("top $long"), "kept walking out of view")
        assertTrue(walking("low $long"), "a row still in view was stopped by another row leaving")
        assertEquals(1, gate!!.registered, "the out-of-view row stayed registered")
        mainClock.advanceTimeBy(MarqueeSettleMs * 3)
        assertFalse(walking("top $long"), "walked while out of view")

        runOnIdle { scope.launch { scroll.scrollTo(0) } }
        frames(2)
        assertFalse(walking("top $long"), "walked before settling back in view")
        mainClock.advanceTimeBy(warmUp)
        assertTrue(walking("top $long"), "did not walk again once back in view")
    }

    @Test
    fun partlyClippedRowDoesNotWalk() = runDesktopComposeUiTest(width = 400, height = 400) {
        mainClock.autoAdvance = false
        setContent {
            MarqueeHost {
                // 100dp viewport over 48dp rows: row 0 is whole, row 2 is cut by the bottom edge.
                LazyColumn(Modifier.fillMaxWidth().height(100.dp)) {
                    items((0 until 6).toList()) {
                        MarqueeText("$it $long", Modifier.width(120.dp).height(48.dp))
                    }
                }
            }
        }
        mainClock.advanceTimeBy(warmUp)
        assertTrue(walking("0 $long"), "the whole row did not walk")
        assertFalse(walking("2 $long"), "a half-hidden row walked")
    }

    @Test
    fun registryHoldsOnlyInViewNamesAfterManyRowsScrollThrough() = runDesktopComposeUiTest(400, 400) {
        mainClock.autoAdvance = false
        val list = LazyListState()
        lateinit var scope: CoroutineScope
        var gate: MarqueeGate? = null
        setContent {
            scope = rememberCoroutineScope()
            MarqueeHost {
                gate = LocalMarqueeGate.current
                LazyColumn(Modifier.fillMaxWidth().height(200.dp), state = list) {
                    items((0 until 300).toList()) { MarqueeText("$it $long", Modifier.width(120.dp).height(40.dp)) }
                }
            }
        }
        frames(2)
        repeat(60) {
            runOnIdle { scope.launch { list.scrollBy(230f) } }
            frames(2)
        }
        mainClock.advanceTimeBy(MarqueeSettleMs * 2)
        val inView = list.layoutInfo.visibleItemsInfo.count {
            it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset
        }
        assertEquals(inView, gate!!.registered, "registry is not exactly the in-view cut-off names")
    }

    /**
     * The connector catalog's shape: tabs over a [HorizontalPager], each page a [LazyColumn] of [AppListItem]
     * rows (name + description + logo), inside a host — as on the full page and in the sheet.
     */
    @Test
    fun catalogPagerOnlyCurrentPageWalksAndASwipeStopsCutRows() = runDesktopComposeUiTest(width = 360, height = 640) {
        mainClock.autoAdvance = false
        val pager = PagerState { 3 }
        val lists = List(3) { LazyListState() }
        var gate: MarqueeGate? = null
        setContent {
            MaterialTheme {
                MarqueeHost {
                    gate = LocalMarqueeGate.current
                    HorizontalPager(pager, Modifier.fillMaxSize().testTag("pager"), beyondViewportPageCount = 1) { page ->
                        LazyColumn(Modifier.fillMaxSize(), state = lists[page]) {
                            items((0 until 60).toList()) { i ->
                                AppListItem(
                                    headline = "p$page c$i Connector with a rather long product name",
                                    supportingText = "p$page d$i Reads and writes the things this connector reaches",
                                    leadingMedia = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary)) },
                                    onClick = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(warmUp)
        // (a) the current page's first row walks; the adjacent page, composed by the pager, never does.
        assertTrue(walking("p0 c0 Connector with a rather long product name"))
        assertTrue(walking("p0 d0 Reads and writes the things this connector reaches"))
        assertFalse(walking("p1 c0 Connector with a rather long product name"), "the off-screen page walked")
        // (d) only in-view cut-off names are registered: two per whole row of the current page.
        val rows = lists[0].layoutInfo.visibleItemsInfo.count {
            it.offset >= 0 && it.offset + it.size <= lists[0].layoutInfo.viewportEndOffset
        }
        assertEquals(rows * 2, gate!!.registered)

        // (b) a swipe cuts every row of the page by the pager's edge: each one is no longer fully in view, so
        // each one's own walk stops at once.
        onNodeWithTag("pager").performTouchInput {
            down(center)
            // Deep enough that the pager's edge cuts into the text itself (it starts ~72px into the row).
            moveBy(Offset(-10f, 0f))
            moveBy(Offset(-140f, 0f))
        }
        mainClock.advanceTimeByFrame()
        assertFalse(walking("p0 c0 Connector with a rather long product name"), "a swipe did not stop the walk")
        assertFalse(walking("p0 d0 Reads and writes the things this connector reaches"))
        // Half-swiped and held: nothing re-arms while cut by the pager's edge.
        mainClock.advanceTimeBy(warmUp)
        assertFalse(walking("p0 c0 Connector with a rather long product name"), "a half-swiped row walked")
        assertFalse(walking("p1 c0 Connector with a rather long product name"), "a half-swiped row walked")
    }
}
