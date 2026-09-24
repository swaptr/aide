package com.sabreware.aide.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared spacing scale. Used by new/touched components; existing inline dp is left as-is. */
object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/**
 * Implicit styling for [com.sabreware.aide.core.designsystem.AppListItem]. Carried by [LocalAppListItemStyle]
 * so call sites never pass raw layout numbers — a container overrides the style once for its region.
 *
 * The leading visual: every row that has one reserves a [leadingSlot]-wide column followed by [leadingGap], so
 * the headline starts at the same x whatever the row leads with. Inside it, content is sized by KIND, each by
 * its own token: a glyph (tinted icon, status badge) is [glyphSize] with no background; media (logo, avatar)
 * is [mediaSize], clipped to `LeadingMediaShape`. The two are independent — neither ever takes the other's
 * size, so a glyph row's height comes from its text, never from [mediaSize]. Fixed dp, like Material's own
 * icon/avatar sizes: the text beside them grows with font scale, the visual does not.
 */
@Immutable
data class AppListItemStyle(
    val contentInset: Dp = 16.dp,
    val verticalInset: Dp = 8.dp,
    val minHeight: Dp = 48.dp,
    val minHeightTwoLine: Dp = 64.dp,
    val glyphSize: Dp = 24.dp,
    val mediaSize: Dp = 40.dp,
    val leadingSlot: Dp = 40.dp,
    val leadingGap: Dp = 16.dp,
) {
    init {
        require(leadingSlot >= glyphSize && leadingSlot >= mediaSize) {
            "leadingSlot ($leadingSlot) must hold both glyphSize ($glyphSize) and mediaSize ($mediaSize)"
        }
    }
}

/** Grouped-card menu rows: roomier vertical padding + taller two-line rows than the base list row. */
val MenuCardListItemStyle = AppListItemStyle(verticalInset = 12.dp, minHeightTwoLine = 72.dp)

val LocalAppListItemStyle = staticCompositionLocalOf { AppListItemStyle() }

/** Drawer variant: inset so chat rows line up under the NavigationDrawerItem icons. */
val DrawerListItemStyle = AppListItemStyle(contentInset = 20.dp)
