package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import com.sabreware.aide.core.designsystem.theme.MenuCardListItemStyle
import org.jetbrains.compose.resources.painterResource

/**
 * The app's grouped-container family. **One look, one data model, one layout rule** — reach for these
 * before writing a container of your own.
 *
 * Everything below renders a list of [AppMenuEntry] as ONE cohesive rounded block: only the corners on
 * the group's OUTSIDE are rounded, every internal edge is square, and cells are separated by a thin
 * TRANSPARENT [AppMenuGap] so the parent surface shows through instead of a drawn divider line.
 *
 * There is exactly ONE menu component — [AppMenu] — and a shape is an argument to it, not another
 * component: `AppMenuLayout.Rows` (the default) · `Rail` (horizontal scrolling tiles) · `Grid(columns)`.
 * A row that needs more than a title/subtitle/toggle describes it as DATA on [AppMenuEntry] (see
 * [AppMenuEntryAction]) — there is no slot to escape through, so every menu in the app stays identical.
 *
 * [AppMenuCard] is its sibling, not an exception: the same card for a slab of arbitrary content (an
 * expandable section's body, a license text, an empty state) rather than a list of entries.
 *
 * Everything else here — the engine, the cells, the corner rules — is `internal` to this family.
 */
internal enum class AppMenuAxis { Vertical, Horizontal }

/** Transparent seam between cells — thin, so the group still reads as one unit. */
internal val AppMenuGap = 2.dp

/** One corner: the group's radius on the group's outside, square where it meets a neighbour. */
internal fun appMenuCorner(outer: Boolean): Dp = if (outer) AppSpacing.lg else 0.dp

/** Shape for cell [index] of [count] in a 1-D group running along [axis]. */
internal fun appMenuShape(
    index: Int,
    count: Int,
    axis: AppMenuAxis = AppMenuAxis.Vertical,
): RoundedCornerShape {
    val first = index == 0
    val last = index == count - 1
    return when (axis) {
        AppMenuAxis.Vertical -> RoundedCornerShape(
            topStart = appMenuCorner(first),
            topEnd = appMenuCorner(first),
            bottomStart = appMenuCorner(last),
            bottomEnd = appMenuCorner(last),
        )
        AppMenuAxis.Horizontal -> RoundedCornerShape(
            topStart = appMenuCorner(first),
            bottomStart = appMenuCorner(first),
            topEnd = appMenuCorner(last),
            bottomEnd = appMenuCorner(last),
        )
    }
}

/**
 * Shape for a cell in a 2-D group. [colCount] is that ROW's width, not the grid's — a short last row
 * stretches, so its final cell is still on the block's outside edge.
 */
internal fun appMenuShape(rowIndex: Int, rowCount: Int, colIndex: Int, colCount: Int): RoundedCornerShape {
    val top = rowIndex == 0
    val bottom = rowIndex == rowCount - 1
    val start = colIndex == 0
    val end = colIndex == colCount - 1
    return RoundedCornerShape(
        topStart = appMenuCorner(top && start),
        topEnd = appMenuCorner(top && end),
        bottomStart = appMenuCorner(bottom && start),
        bottomEnd = appMenuCorner(bottom && end),
    )
}

/**
 * The layout engine: lays [items] out along [axis] with the shared seam and hands each [cell] the shape
 * for its position, so no caller computes corners itself.
 *
 * Both axes are bounded and non-lazy — menus are a short, fixed handful of entries, so lazy machinery
 * would cost a subcomposition per cell for nothing. Vertical composes inside a scrolling column;
 * Horizontal scrolls itself when its tiles overflow the width.
 */
@Composable
internal fun <T> AppMenuGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    axis: AppMenuAxis = AppMenuAxis.Vertical,
    groupPadding: PaddingValues = AppMenuGroupPadding,
    cell: @Composable (item: T, shape: Shape) -> Unit,
) {
    when (axis) {
        AppMenuAxis.Vertical -> Column(
            modifier = modifier.fillMaxWidth().padding(groupPadding),
            verticalArrangement = Arrangement.spacedBy(AppMenuGap),
        ) {
            items.forEachIndexed { index, item ->
                cell(item, appMenuShape(index, items.size, axis))
            }
        }
        // padding INSIDE the scroll modifier: cells must scroll UNDER the inset rather than be clipped
        // by it, while the resting first/last cell still lines up with the groups above and below.
        AppMenuAxis.Horizontal -> Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(groupPadding),
            horizontalArrangement = Arrangement.spacedBy(AppMenuGap),
        ) {
            items.forEachIndexed { index, item ->
                cell(item, appMenuShape(index, items.size, axis))
            }
        }
    }
}

/** The side margin every group owns, so groups line up down a page. */
val AppMenuGroupInset = AppSpacing.lg

/** [AppMenuGroupInset] on both sides, plus the vertical gap between groups. */
val AppMenuGroupPadding = PaddingValues(horizontal = AppMenuGroupInset, vertical = AppSpacing.xs)

/**
 * Where a menu row's text starts and ends, measured from the surface edge: the group's margin plus the row's
 * own inset. Anything that should line up with menu text (a modal header's title and subtitle) reads this.
 */
val AppMenuTextInset = AppMenuGroupInset + MenuCardListItemStyle.contentInset

/**
 * How an [AppMenu] arranges its entries. This is the one knob that turns a settings list into the
 * composer's attach rail or the "Add a model" grid — nothing else about the block changes.
 *
 * Each variant carries its own knobs, so [AppMenu]'s signature never grows a `tileWidth` that means
 * nothing for rows or a `columns` that means nothing for a rail.
 */
sealed interface AppMenuLayout {

    companion object {
        /**
         * A row of actions (a page's or an item's): one horizontal strip of square tiles, the same as the
         * composer's attach rail, scrolling sideways when they overflow. Everything is one tap away; nothing hides
         * in a menu.
         */
        fun actions(): AppMenuLayout = Rail()
    }

    /** Full-width rows, top to bottom. The common case, and the default. */
    data object Rows : AppMenuLayout

    /**
     * A horizontal strip of tiles. [tileWidth] is a minimum: on a host wide enough for every tile the
     * tiles stretch equally to fill the row; otherwise they hold that width and the strip runs off the
     * edge and scrolls — that overflow is what tells the user it scrolls.
     */
    data class Rail(
        val tileWidth: Dp = RailTileWidth,
        val tileHeight: Dp = RailTileHeight,
    ) : AppMenuLayout

    /**
     * A 2-D block of tiles, [columns] wide. A short last row is NOT padded with blanks — its cells
     * stretch to fill the width, so the block never ends in an empty notch.
     */
    data class Grid(
        val columns: Int = 2,
        val tileHeight: Dp = GridTileHeight,
    ) : AppMenuLayout
}

/**
 * The 2-D tile layout behind [AppMenuLayout.Grid]. Non-lazy by design: a handful of fixed choices, so it
 * composes inside a scrolling column without the nested-scroll trap a LazyVerticalGrid brings.
 */
@Composable
internal fun AppMenuTileGrid(
    items: List<AppMenuEntry>,
    columns: Int,
    groupPadding: PaddingValues,
    tileHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val rows = items.chunked(columns)
    Column(
        modifier = modifier.fillMaxWidth().padding(groupPadding),
        verticalArrangement = Arrangement.spacedBy(AppMenuGap),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppMenuGap),
            ) {
                row.forEachIndexed { colIndex, entry ->
                    AppMenuTile(
                        entry = entry,
                        shape = appMenuShape(rowIndex, rows.size, colIndex, row.size),
                        modifier = Modifier.weight(1f),
                        height = tileHeight,
                    )
                }
            }
        }
    }
}

/**
 * The tile cell — icon over a one-line title over an optional one-line subtitle, centered; either walks as a
 * [MarqueeText] when it does not fit. Used by BOTH [AppMenuLayout.Rail] and
 * [AppMenuLayout.Grid], so a tile looks the same wherever it sits; sizing comes through [modifier] because
 * the two size differently on purpose (the rail fixes a width and scrolls, the grid weights and wraps).
 *
 * Reads the same [AppMenuEntry] the row containers do — including [AppMenuEntry.selected] and
 * [AppMenuEntry.destructive], which a tile shows the same way a row does (a filled selection, an error-toned
 * label). A tile that dropped them was the exact pressure that makes someone hand-roll a grid. Row-only
 * fields (toggle, context actions) genuinely do not apply — a tile is a single tap target.
 */
@Composable
internal fun AppMenuTile(
    entry: AppMenuEntry,
    shape: Shape,
    modifier: Modifier = Modifier,
    height: Dp = RailTileHeight,
) {
    val clickable = entry.enabled && entry.onClick != null
    val contentAlpha = if (clickable) 1f else 0.38f
    // Same tokens AppListItem uses, so a tile and a row read as one selection model.
    val titleColor = when {
        entry.destructive -> MaterialTheme.colorScheme.error
        entry.selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
    // The tile's hover/focus is its names' attention (see MarqueeText).
    val interactions = remember { MutableInteractionSource() }
    Surface(
        onClick = entry.onClick ?: {},
        enabled = clickable,
        interactionSource = interactions,
        modifier = modifier,
        shape = shape,
        color = if (entry.selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            entry.leadingIconRes?.let { iconRes ->
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = supportingColor.copy(alpha = contentAlpha),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(AppSpacing.sm))
            }
            CompositionLocalProvider(LocalMarqueeAttention provides interactions) {
                MarqueeText(
                    text = entry.title,
                    // The same regular weight as a row's headline, so tiles and rows read as one menu.
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor.copy(alpha = contentAlpha),
                    textAlign = TextAlign.Center,
                )
                entry.subtitle?.let { subtitle ->
                    MarqueeText(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = supportingColor.copy(alpha = contentAlpha),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Minimum tile width: wide enough for a one-word label; narrow enough that a full rail on a phone
 *  visibly overflows — which is the only thing that tells the user it scrolls. */
internal val RailTileWidth = 100.dp

internal val RailTileHeight = 104.dp

/** Widest a rail tile grows to fit its label; past this the label walks ([MarqueeText]). */
internal val RailTileMaxWidth = 180.dp

/** Room either side of a fitted label: the tile's own padding plus a little air, so it never touches the edge. */
internal val RailLabelInset = AppSpacing.sm + 6.dp

/** Taller than a rail tile: grid tiles carry a subtitle under the title. Fixed, so no cell outgrows its row. */
internal val GridTileHeight = 152.dp
