package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the one header spec ([HeaderBandStyle]): in a modal header the title and subtitle share ONE band — the
 * node each [MarqueeText] clips to — centered with equal insets whatever the slots hold, kept
 * [HeaderBandStyle.bandPadding] clear of the wider slot, and capped at [HeaderBandStyle.maxWidth]. A page header
 * ([HeaderPlacement.Page]) keeps each side's own inset, and with no actions lines up with the menu text; a
 * [HeaderPlacement.CenteredPage] header is symmetric like a modal one.
 */
@OptIn(ExperimentalTestApi::class)
class HeaderBandTest {

    private val title = "A header title long enough that it can never fit on one line of any sheet"
    private val subtitle =
        "And a subtitle that is even longer than the title so that it has to walk as well, even in the widest band"
    private val spec = HeaderBandStyle()
    private val tolerance = 1.dp

    private fun ComposeUiTest.bounds(text: String): DpRect = onNodeWithText(text).getBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp, what: String) =
        assertTrue(abs((expected - actual).value) <= tolerance.value, "$what: expected $expected, was $actual")

    private fun headerCase(width: Dp, content: @Composable () -> Unit, check: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width.value.toInt(), height = 300) {
            setContent { Box(Modifier.width(width)) { content() } }
            val t = bounds(title)
            val s = bounds(subtitle)
            // One band: the subtitle's edges are the title's.
            assertNear(t.left, s.left, "subtitle left")
            assertNear(t.right, s.right, "subtitle right")
            // Symmetric inside the header.
            assertNear(t.left, width - t.right, "left inset vs right inset")
            check()
        }

    private fun minInset(slot: Dp) = spec.edgeInset + slot + spec.bandPadding

    private val back = HeaderAction.back {}
    private fun action(label: String) = HeaderAction(back.iconRes, label, onClick = {})

    @Test
    fun bandIsSymmetricWithoutSlots() = headerCase(400.dp, { AppHeader(title, subtitle = subtitle) }) {
        assertNear(spec.textInset, bounds(title).left, "inset with empty slots")
    }

    @Test
    fun emptyTrailingListReservesNothing() =
        headerCase(400.dp, { AppHeader(title, subtitle = subtitle, trailingActions = emptyList()) }) {
            assertNear(spec.textInset, bounds(title).left, "inset with an empty trailing list")
        }

    @Test
    fun bandSpansTheMenuTextColumn() = runDesktopComposeUiTest(width = 400, height = 400) {
        val rowTitle = "A menu row title long enough that it can never fit on one line of any sheet either"
        setContent {
            Column(Modifier.width(400.dp)) {
                AppHeader(title, subtitle = subtitle)
                AppMenu(items = listOf(AppMenuEntry(title = rowTitle)))
            }
        }
        val band = bounds(title)
        val row = bounds(rowTitle)
        assertNear(row.left, band.left, "band start vs menu text start")
        assertNear(row.right, band.right, "band end vs menu text end")
    }

    @Test
    fun bandIsSymmetricWithBackButton() =
        headerCase(400.dp, { AppHeader(title, subtitle = subtitle, leadingAction = back) }) {
            assertNear(minInset(spec.slotSize), bounds(title).left, "inset with a back chevron")
        }

    @Test
    fun widerTrailingSlotPushesBothSidesWhileRoomy() = headerCase(
        600.dp,
        { AppHeader(title, subtitle = subtitle, leadingAction = back, trailingActions = listOf(action("a"), action("b"))) },
    ) {
        assertNear(minInset(spec.slotSize * 2), bounds(title).left, "inset follows the wider slot")
    }

    // A back button against two actions on a phone leaves a symmetric band under 200dp: a long title then takes
    // the room between the slots rather than being cut to a sliver.
    @Test
    fun crampedModalBandTakesTheRoomBetweenSlots() = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent {
            Box(Modifier.width(400.dp)) {
                AppHeader(title, subtitle = subtitle, leadingAction = back, trailingActions = listOf(action("a"), action("b")))
            }
        }
        val t = bounds(title)
        assertNear(minInset(spec.slotSize), t.left, "starts clear of the leading slot, not the trailing one")
        assertNear(minInset(spec.slotSize * 2), 400.dp - t.right, "ends clear of the trailing slot")
    }

    @Test
    fun bandIsCappedAtMaxWidth() = headerCase(1200.dp, { AppHeader(title, subtitle = subtitle, leadingAction = back) }) {
        val t = bounds(title)
        assertNear(spec.maxWidth, t.right - t.left, "band width")
    }

    @Test
    fun shortLinesAreCenteredByLayout() = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent { Box(Modifier.width(400.dp)) { AppHeader("Short", subtitle = "Also short", leadingAction = back) } }
        for (text in listOf("Short", "Also short")) {
            val b = bounds(text)
            assertTrue(b.right - b.left < 200.dp, "$text wraps its content")
            assertNear(b.left, 400.dp - b.right, "$text centered")
        }
    }

    @Test
    fun pageHeaderKeepsEachSidesOwnInset() = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent {
            Box(Modifier.width(400.dp)) {
                AppHeader(title, subtitle = subtitle, leadingAction = back, placement = HeaderPlacement.Page)
            }
        }
        val t = bounds(title)
        val s = bounds(subtitle)
        assertNear(t.left, s.left, "subtitle left")
        assertNear(t.right, s.right, "subtitle right")
        // Start clears the back button; the end, with nothing there, sits at the menu text inset.
        assertNear(minInset(spec.slotSize), t.left, "start inset past the back button")
        assertNear(400.dp - spec.textInset, t.right, "end inset with no trailing actions")
    }

    @Test
    fun centeredPageBandSitsOnTheCenterLineOfAWideWindow() =
        headerCase(1200.dp, {
            AppHeader(
                title,
                subtitle = subtitle,
                leadingAction = back,
                trailingActions = listOf(action("One"), action("Two")),
                placement = HeaderPlacement.CenteredPage,
            )
        }) {
            // Capped band, centered on the header: a landscape phone's model picker no longer drifts start-ward.
            val t = bounds(title)
            assertNear(spec.maxWidth, t.right - t.left, "band width")
        }

    @Test
    fun pageHeaderWithoutActionsLinesUpWithMenuText() = runDesktopComposeUiTest(width = 400, height = 300) {
        setContent { Box(Modifier.width(400.dp)) { AppHeader("Short", placement = HeaderPlacement.Page) } }
        assertNear(spec.textInset, bounds("Short").left, "page title start")
    }

    /**
     * A slot that EMPTIES animates like one that changes. It used to sit behind an `if`, so a sheet's first page
     * leaving search (Close search, then nothing) snapped the title across while a screen (Close search, then
     * the drawer) glided. Mid-transition the title must be between where it started and where it ends.
     */
    @Test
    fun anEmptyingSlotMovesTheBandSmoothly() = runDesktopComposeUiTest(width = 400, height = 300) {
        var leading by mutableStateOf<HeaderAction?>(back)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.width(400.dp)) { AppHeader("Short", leadingAction = leading, placement = HeaderPlacement.Page) } }
        mainClock.advanceTimeBy(ChromeMotion.DurationMs * 2L)
        val start = bounds("Short").left
        assertNear(minInset(spec.slotSize), start, "with a slot")

        leading = null
        mainClock.advanceTimeBy(ChromeMotion.DurationMs / 2L)
        val mid = bounds("Short").left
        assertTrue(mid < start - tolerance && mid > spec.textInset + tolerance, "mid-transition title at $mid, not between $start and ${spec.textInset}")

        mainClock.advanceTimeBy(ChromeMotion.DurationMs * 2L)
        assertNear(spec.textInset, bounds("Short").left, "slot emptied")
    }

    @Test
    fun anActionHasExactlyOneTarget() {
        assertFailsWith<IllegalArgumentException> { HeaderAction(back.iconRes, "neither") }
        assertFailsWith<IllegalArgumentException> {
            HeaderAction(back.iconRes, "both", menu = HeaderMenu(emptyList()), onClick = {})
        }
    }
}
