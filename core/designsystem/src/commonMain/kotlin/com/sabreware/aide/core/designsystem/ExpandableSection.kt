package com.sabreware.aide.core.designsystem

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.AppSpacing
import org.jetbrains.compose.resources.painterResource

/**
 * A titled section that lives in ONE rounded card ([AppMenuCard]): the header row ([AppListItem] with
 * a rotating chevron) toggles [expanded], and the body grows/collapses *inside* the same rounded box
 * below it — so expanding feels like the whole card opening up. Matches the app's rounded menu
 * language. The caller hoists [expanded] so it survives recreation.
 *
 * The body is **full-bleed** — it adds no inset of its own. List rows ([AppListItem]) fill the card
 * width (so their press/selection background spans edge to edge, like a real menu) while their own
 * `contentInset` keeps the text aligned; any non-row content (labels, buttons, form fields) must bring
 * its own padding. This is deliberate: a forced default inset clipped/cramped the rows.
 *
 * Animation = a single [Modifier.animateContentSize] on the card's content column (Compose's optimized,
 * documented pattern for expand/collapse — there's no official Material expandable component).
 *
 * [cardPadding] is the card's outer margin: defaults to the full-bleed page inset; a container that
 * already pads horizontally (e.g. a sheet form) passes `PaddingValues(0.dp)` so it lines up.
 */
@Composable
fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    cardPadding: PaddingValues = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "section-chevron")
    AppMenuCard(modifier = modifier, groupPadding = cardPadding) {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
            AppListItem(
                headline = title,
                onClick = { onExpandedChange(!expanded) },
                trailing = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_lc_chevron_down),
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
            )
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}
