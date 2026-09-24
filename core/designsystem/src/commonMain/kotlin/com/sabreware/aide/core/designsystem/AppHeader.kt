package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.sabreware.aide.core.designsystem.resources.*

/**
 * How an [AppHeader] aligns its band. There is no per-host placement: a page's header is the same on a screen,
 * in a sheet and in a dialog — only the container differs.
 *
 * - [Page] — every header. The title reads from the start like the content under it (Material's top-app-bar
 *   convention), between the slots.
 * - [CenteredPage] — a band holding a centered control rather than a title (the chat screen's model picker).
 *   The band is symmetric, so the control sits on the center line whatever the slots hold.
 */
enum class HeaderPlacement {
    Page,
    CenteredPage,
}

/**
 * **The** header. Every host draws this one component: the screen top bar ([AppScaffold], [AppPage]), a flow
 * page in either host ([PageScaffold]), and every sheet and dialog ([AppDialog]). Same slots, same action
 * data ([HeaderAction]), same renderer, same spacing ([LocalHeaderBandStyle]), same height.
 *
 * ```
 * edgeInset | leading | bandPadding | band (title + subtitle) | bandPadding | trailing | edgeInset
 * ```
 *
 * Both action slots are nullable and null by default. An empty slot draws nothing and reserves no width.
 * Each side of the band sits at the larger of:
 * - [HeaderBandStyle.textInset] — the menu's text inset, so the band lines up with the rows under it;
 * - `edgeInset + slot + bandPadding` when that side's slot holds something.
 *
 * [HeaderPlacement.Page] keeps each side's own inset, so the band follows each slot continuously while it
 * animates. [HeaderPlacement.CenteredPage] takes the wider side for BOTH sides.
 *
 * At most [MaxTrailingButtons] trailing buttons are drawn; the rest fold into one "More" menu ([withOverflow]),
 * so adding an action never squeezes the title into a sliver. The band is capped at
 * [HeaderBandStyle.maxWidth] and clips everything in it: an ellipsised or walking line ([MarqueeText]) never
 * reaches the padding, a slot, or the surface edge. One layout pass, no subcomposition.
 */
@Composable
fun AppHeader(
    title: String = "",
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingAction: HeaderAction? = null,
    trailingActions: List<HeaderAction>? = null,
    placement: HeaderPlacement = HeaderPlacement.Page,
    /**
     * Drawn in the band instead of [title] (the chat screen's model picker, a collection's search field). The
     * band is laid out and clipped exactly as for a title. Switching between the two is a change of ARGUMENT,
     * so the header stays one composable and its slots animate; a caller must never branch into two calls.
     */
    titleContent: (@Composable () -> Unit)? = null,
) {
    val style = LocalHeaderBandStyle.current
    val trailing = trailingActions.orEmpty().withOverflow()
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = style.minHeight),
        content = {
            // Both slots are ALWAYS drawn, empty or not: a slot behind an `if` appeared and vanished without
            // motion, so the band jumped wherever a slot empties (a sheet's first page leaving search).
            Box { HeaderActionRow(listOfNotNull(leadingAction)) }
            Box(Modifier.clipToBounds()) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    HeaderText(
                        title = title,
                        subtitle = subtitle,
                        textAlign = if (placement == HeaderPlacement.CenteredPage) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Box { HeaderActionRow(trailing) }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = measurables[0].measure(loose)
        val end = measurables[2].measure(loose)
        val width = constraints.maxWidth
        val edge = style.edgeInset.roundToPx()
        val gap = style.bandPadding.roundToPx()
        val text = style.textInset.roundToPx()
        // From the slots' MEASURED widths, so the band follows a slot while it animates open or shut.
        val ownStart = maxOf(text, if (leading.width > 0) edge + leading.width + gap else 0)
        val ownStop = maxOf(text, if (end.width > 0) edge + end.width + gap else 0)
        val maxBand = style.maxWidth.roundToPx()
        val centered = placement == HeaderPlacement.CenteredPage
        val bandWidth = if (centered) {
            (width - 2 * maxOf(ownStart, ownStop)).coerceIn(0, maxBand)
        } else {
            (width - ownStart - ownStop).coerceIn(0, maxBand)
        }
        val band = measurables[1].measure(Constraints.fixedWidth(bandWidth))
        val bandX = if (centered) (width - bandWidth) / 2 else ownStart
        val height = maxOf(constraints.minHeight, leading.height, end.height, band.height)
        layout(width, height) {
            leading.placeRelative(edge, (height - leading.height) / 2)
            band.placeRelative(bandX, (height - band.height) / 2)
            end.placeRelative(width - edge - end.width, (height - end.height) / 2)
        }
    }
}

/** Trailing buttons drawn before the rest fold into "More". */
internal const val MaxTrailingButtons = 3

/**
 * [this] with anything past [MaxTrailingButtons] folded into ONE "More" menu. Actions that open their own menu
 * stay visible (a menu cannot nest in a menu); the first plain actions stay in front, the rest become the
 * More sheet's rows in order.
 */
internal fun List<HeaderAction>.withOverflow(): List<HeaderAction> {
    if (size <= MaxTrailingButtons) return this
    val menus = filter { it.menu != null }
    val plain = filter { it.menu == null }
    val keep = (MaxTrailingButtons - 1 - menus.size).coerceAtLeast(0)
    val shown = plain.take(keep)
    val folded = plain.drop(keep)
    if (folded.isEmpty()) return this
    val more = HeaderAction(
        iconRes = Res.drawable.ic_lc_ellipsis_vertical,
        label = "More",
        menu = HeaderMenu(
            items = folded.map { action ->
                AppMenuAction(
                    label = action.label,
                    iconRes = action.iconRes,
                    enabled = action.enabled,
                    destructive = action.destructive,
                    onClick = action.onClick ?: {},
                )
            },
        ),
    )
    // Original order for what stays visible, More last.
    return filter { it in shown || it.menu != null } + more
}
