package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.sabreware.aide.core.designsystem.theme.AppListItemStyle
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle
import com.sabreware.aide.core.designsystem.theme.MenuCardListItemStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the leading slot's contract: glyph and media are sized by their OWN tokens, so changing the media size
 * never moves a glyph row (and vice versa), a glyph never makes a menu row taller than a plain one, and the
 * headline starts at the same x whatever the row leads with.
 */
@OptIn(ExperimentalTestApi::class)
class LeadingSlotTest {

    private enum class Kind { None, Glyph, Media }

    @Composable
    private fun Row(kind: Kind, tag: String, headline: String, supporting: String? = null) {
        val fill: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) }
        AppListItem(
            headline = headline,
            supportingText = supporting,
            modifier = Modifier.testTag(tag),
            leading = if (kind == Kind.Glyph) fill else null,
            leadingMedia = if (kind == Kind.Media) fill else null,
        )
    }

    private fun ComposeUiTest.rowHeight(tag: String): Dp = onNodeWithTag(tag).getUnclippedBoundsInRoot().height

    private fun ComposeUiTest.headlineX(text: String): Dp =
        onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().left

    private fun ComposeUiTest.show(style: AppListItemStyle, content: @Composable () -> Unit) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalAppListItemStyle provides style) { Column { content() } }
        }
    }

    @Test
    fun glyphRowHeightIgnoresMediaSize() {
        val heights = listOf(40.dp, 72.dp).map { media ->
            var h = 0.dp
            runDesktopComposeUiTest(width = 400, height = 400) {
                show(MenuCardListItemStyle.copy(mediaSize = media, leadingSlot = media)) {
                    Row(Kind.Glyph, "glyph", "Pause")
                }
                h = rowHeight("glyph")
            }
            h
        }
        assertEquals(heights[0], heights[1])
    }

    @Test
    fun mediaRowHeightIgnoresGlyphSize() {
        val heights = listOf(24.dp, 36.dp).map { glyph ->
            var h = 0.dp
            runDesktopComposeUiTest(width = 400, height = 400) {
                show(MenuCardListItemStyle.copy(glyphSize = glyph)) { Row(Kind.Media, "media", "GitHub", "Code") }
                h = rowHeight("media")
            }
            h
        }
        assertEquals(heights[0], heights[1])
    }

    @Test
    fun glyphMenuRowIsAsCompactAsAPlainOne() = runDesktopComposeUiTest(width = 400, height = 400) {
        show(MenuCardListItemStyle) {
            Row(Kind.None, "plain", "Cancel")
            Row(Kind.Glyph, "glyph", "Pause")
        }
        assertEquals(rowHeight("plain"), rowHeight("glyph"))
    }

    @Test
    fun headlineStartsAtOneXWhateverTheRowLeadsWith() = runDesktopComposeUiTest(width = 400, height = 400) {
        show(AppListItemStyle()) {
            Row(Kind.Glyph, "glyph", "Pause")
            Row(Kind.Media, "media", "GitHub", "Code")
        }
        assertEquals(headlineX("Pause"), headlineX("GitHub"))
    }
}
