package com.sabreware.aide.ui.settings.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.PlaceholderAction
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*

/** The "no connectors yet" empty state, with the one action that fixes it. */
@Composable
fun NoMcpServersPlaceholder(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Placeholder(
        modifier = modifier,
        iconRes = Res.drawable.ic_mcp,
        title = "No connectors",
        subtitle = "Add tools from other services.",
        actions = listOf(
            PlaceholderAction(label = "Add connector", iconRes = Res.drawable.ic_lc_plus, onClick = onAdd),
        ),
    )
}

/**
 * Skeleton rows while the encrypted store's first read is in flight, so the empty state never flashes before
 * the list has loaded.
 */
@Composable
internal fun ConnectorsLoading(modifier: Modifier = Modifier) {
    val shimmer = rememberSkeletonShimmer()
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(3) { SkeletonListRow(shimmer, lines = 2, index = it) }
    }
}
