package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle
import com.sabreware.aide.core.designsystem.theme.MenuCardListItemStyle
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Data model for a row in [AppMenu]. **Data only** — no `@Composable` fields, so it stays a
 * stable/skippable `@Immutable` value. For rows that need custom leading/supporting/trailing
 * composables, use [AppListItem] directly (its slots are parameters, not data).
 */
@Immutable
data class AppMenuEntry(
    val title: String,
    val subtitle: String? = null,
    val leadingIconRes: DrawableResource? = null,
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val key: Any? = null,
    val contextActions: List<AppMenuAction>? = null,
    val contextHeader: AppMenuSheetHeader? = null,
    val toggle: AppMenuToggle? = null,
    val action: AppMenuEntryAction? = null,
)

/**
 * A secondary icon action on a row, drawn left of its [AppMenuToggle] — e.g. "configure this thing"
 * next to the switch that enables it. Data, not a composable slot, so [AppMenuEntry] stays `@Immutable`
 * and every such row keeps the same metrics. [badgeCount] > 0 badges the icon.
 */
@Immutable
data class AppMenuEntryAction(
    val iconRes: DrawableResource,
    val contentDescription: String,
    val badgeCount: Int = 0,
    val onClick: () -> Unit,
)

@Immutable
data class AppMenuToggle(
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val enabled: Boolean = true,
)

/**
 * Optional header for an action sheet ([AppDropdownMenu]) — the [title] of the thing being acted on
 * (e.g. a chat's name) over one dim [subtitle] metadata line (e.g. "Created …"). Rendered through the
 * sheet's standard [AppHeader] (centered title + muted subtitle). **Data only** so it can ride on the
 * [@Immutable][Immutable] [AppMenuEntry]/`contextHeader` path.
 */
@Immutable
data class AppMenuSheetHeader(
    val title: String,
    val subtitle: String? = null,
)

/**
 * **The** menu component: a bounded, non-lazy group of [AppMenuEntry]s. For dynamic/unbounded lists,
 * render [AppListItem] inside a `LazyColumn` instead.
 *
 * [layout] is the ONLY thing that changes between a settings list, the composer's attach rail, and the
 * "Add a model" grid — same entries, same block look, same corner rule. The layout also picks the cell
 * that suits it ([AppMenuSegment] rows for [AppMenuLayout.Rows], [AppMenuTile] tiles for the others),
 * because a full-width row in a horizontal rail (or a centered tile in a settings list) is never what
 * the caller meant. Per-layout knobs (tile size, columns) live on the layout, not on this signature.
 *
 * Renders as one cohesive block: OUTER corners rounded, inner edges square, cells separated by a thin
 * TRANSPARENT [AppMenuGap] so the parent surface shows through rather than a drawn line. The group owns
 * its side margin ([groupPadding]); containers that already pad horizontally pass `PaddingValues(0.dp)`.
 * Stack several [AppMenu]s (each with a [title]) to get multiple sections.
 */
@Composable
fun AppMenu(
    items: List<AppMenuEntry>,
    modifier: Modifier = Modifier,
    layout: AppMenuLayout = AppMenuLayout.Rows,
    title: String? = null,
    groupPadding: PaddingValues = AppMenuGroupPadding,
    emptyMessage: String = "No items available",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrEmpty()) AppMenuSectionTitle(title)
        if (items.isEmpty()) {
            AppMenuCard(groupPadding = groupPadding) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Column
        }
        when (layout) {
            AppMenuLayout.Rows -> AppMenuGroup(
                items = items,
                groupPadding = groupPadding,
            ) { entry, shape ->
                AppMenuSegment(shape) { MenuEntryRow(entry) }
            }

            is AppMenuLayout.Rail -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val layoutDirection = LocalLayoutDirection.current
                val available = maxWidth -
                    groupPadding.calculateStartPadding(layoutDirection) -
                    groupPadding.calculateEndPadding(layoutDirection)
                // Each tile is as wide as its OWN label needs on one line (measured at the device's real font size),
                // never narrower than square and never wider than RailTileMaxWidth — a label only walks past that.
                // Short labels stay square, a long one widens just its tile. On a host wide enough for all of them
                // the spare width is shared equally, so the strip still fills the row.
                val measurer = rememberTextMeasurer()
                val labelStyle = MaterialTheme.typography.titleSmall
                val density = LocalDensity.current
                val natural = remember(items, labelStyle, density, layout.tileWidth) {
                    items.map { entry ->
                        val widest = maxOf(
                            measurer.measure(entry.title, labelStyle, maxLines = 1).size.width,
                            entry.subtitle?.let { measurer.measure(it, labelStyle, maxLines = 1).size.width } ?: 0,
                        )
                        (with(density) { widest.toDp() } + RailLabelInset * 2)
                            .coerceIn(layout.tileWidth, max(layout.tileWidth, RailTileMaxWidth))
                    }
                }
                val used = natural.fold(0.dp) { sum, w -> sum + w } + AppMenuGap * (items.size - 1)
                val spare = if (used < available) (available - used) / items.size else 0.dp
                val widths = items.indices.associate { items[it] to natural[it] + spare }
                AppMenuGroup(
                    items = items,
                    axis = AppMenuAxis.Horizontal,
                    groupPadding = groupPadding,
                ) { entry, shape ->
                    val tileWidth = widths.getValue(entry)
                    AppMenuTile(entry, shape, Modifier.width(tileWidth), height = layout.tileHeight)
                }
            }

            is AppMenuLayout.Grid -> AppMenuTileGrid(
                items = items,
                columns = layout.columns,
                groupPadding = groupPadding,
                tileHeight = layout.tileHeight,
            )
        }
    }
}

/**
 * The shared rounded "grouped card" container — one [Surface] (single clip), brand
 * [surfaceContainerHigh][androidx.compose.material3.ColorScheme.surfaceContainerHigh] fill, owning its
 * own side margin so it reads as inset-grouped everywhere. Use for a single all-rounded card (one
 * row, or an empty state); for a multi-row group use a column of [AppMenuSegment]s spaced by
 * [AppMenuGap] (as [AppMenu] does) so the rows share rounded outer corners with transparent
 * gaps between.
 */
@Composable
fun AppMenuCard(
    modifier: Modifier = Modifier,
    groupPadding: PaddingValues = AppMenuGroupPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(groupPadding),
        shape = RoundedCornerShape(AppSpacing.lg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // Roomier row metrics inside the card than the base list row.
        CompositionLocalProvider(LocalAppListItemStyle provides MenuCardListItemStyle) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/**
 * One cell of a segmented menu group: a filled [Surface] with the roomier in-card row metrics. [shape]
 * comes from the container ([AppMenuGroup] hands each cell its own), so a group of these reads as a
 * single rounded card split by transparent [AppMenuGap] seams rather than as separate pills.
 */
@Composable
internal fun AppMenuSegment(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        CompositionLocalProvider(LocalAppListItemStyle provides MenuCardListItemStyle) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** The row's secondary icon action — badged when [AppMenuEntryAction.badgeCount] is positive. */
@Composable
private fun AppMenuRowAction(action: AppMenuEntryAction) {
    BadgedBox(badge = { if (action.badgeCount > 0) Badge { Text("${action.badgeCount}") } }) {
        IconButton(onClick = action.onClick) {
            Icon(
                painter = painterResource(action.iconRes),
                contentDescription = action.contentDescription,
            )
        }
    }
}

/** Section label above an [AppMenuCard]/[AppMenu] group — smaller + dimmer than the row titles. */
@Composable
fun AppMenuSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** Renders one data [AppMenuEntry] through the shared slot-based [AppListItem]. */
@Composable
internal fun MenuEntryRow(entry: AppMenuEntry) {
    val toggle = entry.toggle
    val action = entry.action
    val toggleEnabled = entry.enabled && (toggle?.enabled ?: true)
    // A toggle with no other tap action turns the whole row into the switch.
    val rowOwnsToggle = toggle != null && entry.onClick == null
    AppListItem(
        headline = entry.title,
        supportingText = entry.subtitle,
        leadingIconRes = entry.leadingIconRes,
        trailing = if (toggle == null && action == null) {
            null
        } else {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    action?.let { AppMenuRowAction(it) }
                    toggle?.let {
                        AppMenuTrailingSwitch(
                            checked = it.checked,
                            // Interactive only when the row itself does something else. With no [onClick] the
                            // whole row IS the switch (one big target); with one, the switch has to be its own
                            // target or the entry's tap action would be unreachable.
                            onCheckedChange = if (rowOwnsToggle || !toggleEnabled) {
                                null
                            } else {
                                toggle.onCheckedChange
                            },
                            enabled = toggleEnabled,
                        )
                    }
                }
            }
        },
        onClick = when {
            // "Tap the row to configure, tap the switch to enable" is a shape callers write naturally. It used
            // to compile and then silently drop `onClick`, leaving the row a toggle and the detail page
            // unreachable — so both are honoured, and which one the row tap runs is decided here, once.
            rowOwnsToggle -> if (toggleEnabled) ({ toggle.onCheckedChange(!toggle.checked) }) else null
            else -> entry.onClick
        },
        onLongClick = entry.onLongClick,
        enabled = entry.enabled,
        selected = entry.selected,
        destructive = entry.destructive,
        contextActions = entry.contextActions,
        contextHeader = entry.contextHeader,
    )
}

@Composable
fun AppMenuTrailingSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

data class AppMenuAction(
    val label: String,
    val iconRes: DrawableResource? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
) : AppDropdownItem

