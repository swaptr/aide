package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle

/**
 * Skeleton building blocks over [Modifier.skeleton] — THE shapes every loading surface composes from, so
 * skeletons look identical app-wide and no screen hand-rolls its own boxes. Create ONE shimmer per surface
 * with [rememberSkeletonShimmer] and pass it to every shape so they sweep in phase.
 */

/** A shimmering capsule (fully rounded) — pill-shaped placeholders (e.g. the chat model pill). */
@Composable
fun SkeletonCapsule(width: Dp, height: Dp, shimmer: SkeletonShimmer, modifier: Modifier = Modifier) {
    Box(modifier.size(width = width, height = height).skeleton(true, shimmer, RoundedCornerShape(50)))
}

/** A shimmering text-line bar sized from [style]'s font size, so it matches the text it stands in for. */
@Composable
fun SkeletonTextBar(
    shimmer: SkeletonShimmer,
    style: TextStyle,
    widthFraction: Float,
    modifier: Modifier = Modifier,
) {
    val height = with(LocalDensity.current) { style.fontSize.toDp() }
    Box(modifier.fillMaxWidth(widthFraction).height(height).skeleton(true, shimmer))
}

// Single-line rows cycle bar widths so a column reads as distinct entries, not a striped block.
private val SingleLineWidths = listOf(0.72f, 0.5f, 0.62f)

/** Which leading kind a [SkeletonListRow] stands in for — the same kinds [AppListItem] sizes. */
enum class SkeletonLeading { None, Glyph, Media }

/**
 * A loading placeholder shaped like an [AppListItem] row. Reads the same [LocalAppListItemStyle]
 * insets/heights as the real rows, so the list doesn't jump when data lands.
 *
 * [lines] = 1 renders a single headline bar (e.g. drawer chat rows); 2+ renders a title bar plus
 * `lines - 1` body bars (e.g. connector rows). [leading] adds the placeholder of the real row's leading kind,
 * in the same slot and at the same size, so nothing shifts when the row lands.
 * Pass the item [index] so consecutive single-line bars vary in width.
 */
@Composable
fun SkeletonListRow(
    shimmer: SkeletonShimmer,
    modifier: Modifier = Modifier,
    lines: Int = 1,
    leading: SkeletonLeading = SkeletonLeading.None,
    index: Int = 0,
) {
    val style = LocalAppListItemStyle.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (lines > 1) style.minHeightTwoLine else style.minHeight)
            .padding(horizontal = style.contentInset, vertical = style.verticalInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val leadingSize = when (leading) {
            SkeletonLeading.None -> null
            SkeletonLeading.Glyph -> style.glyphSize
            SkeletonLeading.Media -> style.mediaSize
        }
        if (leadingSize != null) {
            LeadingSlot(style) { Box(Modifier.size(leadingSize).skeleton(true, shimmer, LeadingMediaShape)) }
        }
        Column(Modifier.weight(1f)) {
            if (lines == 1) {
                SkeletonTextBar(
                    shimmer = shimmer,
                    style = MaterialTheme.typography.bodyLarge,
                    widthFraction = SingleLineWidths[index % SingleLineWidths.size],
                )
            } else {
                SkeletonTextBar(shimmer, MaterialTheme.typography.titleMedium, widthFraction = 0.55f)
                repeat(lines - 1) { body ->
                    Spacer(Modifier.height(if (body == 0) 8.dp else 4.dp))
                    SkeletonTextBar(
                        shimmer = shimmer,
                        style = MaterialTheme.typography.bodyMedium,
                        widthFraction = if (body == 0) 1f else 0.8f,
                    )
                }
            }
        }
    }
}
