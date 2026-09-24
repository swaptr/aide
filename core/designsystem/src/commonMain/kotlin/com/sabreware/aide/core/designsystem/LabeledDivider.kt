package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sabreware.aide.core.designsystem.theme.AppSpacing

/** Where the label sits between the two divider lines. */
enum class LabeledDividerSide { Start, Center, End }

/**
 * A short label flanked by divider lines on **both** sides — e.g. an "or" between two choices, or a
 * section header. [side] shifts the label: [Center] gives equal lines either side; [Start]/[End] hug
 * the label to that edge with a short [stubWidth] line on the near side and a filling line on the far
 * side (the drawer uses Start with a wider stub to offset its section titles past the row inset).
 */
@Composable
fun LabeledDivider(
    text: String,
    modifier: Modifier = Modifier,
    side: LabeledDividerSide = LabeledDividerSide.Center,
    contentPadding: PaddingValues = PaddingValues(vertical = AppSpacing.md),
    stubWidth: Dp = AppSpacing.lg,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Row(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading line: a short stub when the label hugs the start, otherwise it fills.
        if (side == LabeledDividerSide.Start) {
            HorizontalDivider(modifier = Modifier.width(stubWidth), color = lineColor)
        } else {
            HorizontalDivider(modifier = Modifier.weight(1f), color = lineColor)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        // Trailing line: a short stub when the label hugs the end, otherwise it fills.
        if (side == LabeledDividerSide.End) {
            HorizontalDivider(modifier = Modifier.width(stubWidth), color = lineColor)
        } else {
            HorizontalDivider(modifier = Modifier.weight(1f), color = lineColor)
        }
    }
}
