package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppSpacing

/**
 * **The** header spec, read by [AppHeader] in every host (bottom sheet, centered dialog, full-page top bar):
 * how a title and its subtitle are laid out. One band holds both lines (so their edges line up), capped at [maxWidth], and kept [bandPadding]
 * clear of the slots on BOTH sides; a cut-off line walks ([MarqueeText]) clipped to that band, never to the
 * surface edge. Read through [LocalHeaderBandStyle]; no host copies a number from here.
 *
 * - [slotSize] — the size of every header button ([HeaderAction]), a touch target.
 * - [edgeInset] — between the surface edge and a slot; Material's top bar uses the same 4dp.
 * - [bandPadding] — between an occupied slot and the band, on both sides, in every host.
 * - [textInset] — the band's inset from the surface edge when no slot pushes it further in: the menu's
 *   text inset ([AppMenuTextInset]), so a title starts exactly where its rows' text does.
 * - [minHeight] — every header's height (Material's top bar), in every host.
 * - [lineGap] — between title and subtitle.
 */
@Immutable
data class HeaderBandStyle(
    val slotSize: Dp = 48.dp,
    val edgeInset: Dp = AppSpacing.xs,
    val bandPadding: Dp = AppSpacing.md,
    val textInset: Dp = AppMenuTextInset,
    val maxWidth: Dp = 480.dp,
    val minHeight: Dp = 64.dp,
    val lineGap: Dp = 2.dp,
)

val LocalHeaderBandStyle = staticCompositionLocalOf { HeaderBandStyle() }

/**
 * The title + optional subtitle, typed by the header spec. [modifier] must give the band its width.
 *
 * Centering is done by LAYOUT, never by [TextAlign.Center] on the text: a centered one-line text that ellipsises
 * is positioned from its unellipsised width, so an overrunning subtitle drew off-center — inset at one edge and
 * flush with the other. Each line instead wraps its content (capped at the band) and the column centers it, so a
 * line that fits sits in the middle and one that overruns fills the band exactly: starts at its start edge,
 * ellipsis at its end edge, and its [MarqueeText] clip IS the band.
 */
@Composable
internal fun HeaderText(
    title: String,
    subtitle: String?,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    val style = LocalHeaderBandStyle.current
    val centered = textAlign == TextAlign.Center
    // Centered: shrink-wrap each line so the column centers it. Start: every line fills the band.
    val line = if (centered) Modifier else Modifier.fillMaxWidth()
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(style.lineGap),
    ) {
        MarqueeText(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
            modifier = line,
        )
        if (subtitle != null) {
            // One line like every subtitle; a longer explanation walks once the surface settles.
            MarqueeText(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = line,
            )
        }
    }
}
