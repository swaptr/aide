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
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.resources.*

/**
 * Where an [AppHeader] sits, which decides the two things that differ between hosts: alignment and height.
 *
 * - [Page] — a full screen's top bar. The title reads from the start like the content under it (Material's
 *   top-app-bar convention), and the bar is [HeaderBandStyle.pageMinHeight] tall.
 * - [CenteredPage] — a full screen's top bar whose band holds a centered control rather than a title (the chat
 *   screen's model picker). Page height, but the band is symmetric like [Modal], so the control sits on the
 *   screen's center line whatever the slots hold and however wide the window is. A start-anchored band put it
 *   in the middle of a band that began after the drawer button and stopped at [HeaderBandStyle.maxWidth], so
 *   on a landscape phone it drifted far left of center.
 * - [Modal] — a bottom sheet, a centered dialog, or a flow page inside one. The title is centered, and the
 *   header is [HeaderBandStyle.minHeight] tall.
 */
enum class HeaderPlacement {
    Page,
    CenteredPage,
    Modal,
    ;

    internal val centered: Boolean get() = this != Page
    internal val tall: Boolean get() = this != Modal
}

/**
 * **The** header. Every host draws this one component: the screen top bar ([AppScaffold], [AppPage]), a flow
 * page in either host ([PageScaffold]), and every sheet and dialog ([AppDialog]). Same slots, same action
 * data ([HeaderAction]), same renderer, same spacing ([LocalHeaderBandStyle]); only [placement] differs.
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
 * A centered placement ([HeaderPlacement.Modal], [HeaderPlacement.CenteredPage]) then takes the wider side for
 * BOTH sides, so the band stays centered on the header whatever the slots hold. One exception, [HeaderPlacement.Modal]
 * only: when that symmetric band is cramped (under [CrampedBand] — a back button against several actions) and a
 * title does not fit it, the band takes all the room between the slots instead: a title a little off-center
 * reads better than one cut to a sliver.
 * [HeaderPlacement.Page] keeps each side's own inset.
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
    placement: HeaderPlacement = HeaderPlacement.Modal,
    /**
     * Drawn in the band instead of [title] (the chat screen's model picker, a collection's search field). The
     * band is laid out and clipped exactly as for a title. Switching between the two is a change of ARGUMENT,
     * so the header stays one composable and its slots animate; a caller must never branch into two calls.
     */
    titleContent: (@Composable () -> Unit)? = null,
) {
    val style = LocalHeaderBandStyle.current
    val trailing = trailingActions.orEmpty().withOverflow()
    val hasLeading = leadingAction != null
    val hasTrailing = trailing.isNotEmpty()
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (placement.tall) style.pageMinHeight else style.minHeight),
        content = {
            Box { if (leadingAction != null) HeaderActionRow(listOf(leadingAction)) }
            Box(Modifier.clipToBounds()) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    HeaderText(
                        title = title,
                        subtitle = subtitle,
                        textAlign = if (placement.centered) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Box { if (hasTrailing) HeaderActionRow(trailing) }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = measurables[0].measure(loose)
        val end = measurables[2].measure(loose)
        val width = constraints.maxWidth
        val edge = style.edgeInset.roundToPx()
        val gap = style.bandPadding.roundToPx()
        val text = style.textInset.roundToPx()
        val ownStart = maxOf(text, if (hasLeading) edge + leading.width + gap else 0)
        val ownStop = maxOf(text, if (hasTrailing) edge + end.width + gap else 0)
        val maxBand = style.maxWidth.roundToPx()
        val symmetric = (width - 2 * maxOf(ownStart, ownStop)).coerceIn(0, maxBand)
        val between = (width - ownStart - ownStop).coerceIn(0, maxBand)
        // Centered, unless that leaves a modal title cramped (a back button against several actions) while the
        // room between the slots is wider and the title would use it. The chat picker ([HeaderPlacement.CenteredPage])
        // always stays on the center line; so does any title that fits, and any band already at its cap.
        val fitsCentered = placement != HeaderPlacement.Modal ||
            symmetric >= CrampedBand.roundToPx() ||
            between <= symmetric ||
            measurables[1].maxIntrinsicWidth(constraints.maxHeight) <= symmetric
        val bandWidth = if (placement.centered && fitsCentered) symmetric else between
        val band = measurables[1].measure(Constraints.fixedWidth(bandWidth))
        val bandX = when {
            placement.centered && fitsCentered -> (width - bandWidth) / 2
            placement.centered -> ownStart + ((width - ownStart - ownStop) - bandWidth) / 2
            else -> ownStart
        }
        val height = maxOf(constraints.minHeight, leading.height, end.height, band.height)
        layout(width, height) {
            leading.placeRelative(edge, (height - leading.height) / 2)
            band.placeRelative(bandX, (height - band.height) / 2)
            end.placeRelative(width - edge - end.width, (height - end.height) / 2)
        }
    }
}

/** Below this, a centered modal band counts as cramped and may take the room between its slots instead. */
private val CrampedBand = 200.dp

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
