package com.sabreware.aide.ui.settings.mcp.browse

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * One flat connector row: logo + name + description, and a small status affordance only (a spinner while
 * connecting, a check when connected) — no space-hungry Connect button. Tapping the row opens the detail
 * sheet, which carries the actual Connect action. Shared by the catalog screen + the search results.
 */
@Composable
fun ConnectorRowItem(
    row: ConnectorBrowseViewModel.ConnectorRow,
    iconLoader: ImageLoader,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val trailing: (@Composable () -> Unit)? = when {
        busy -> {
            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        row.connected -> {
            {
                Icon(
                    painter = painterResource(Res.drawable.ic_lc_check),
                    contentDescription = "Connected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        else -> null
    }
    com.sabreware.aide.core.designsystem.AppListItem(
        headline = row.connector.name,
        supportingText = row.connector.description,
        leadingMedia = { ConnectorIcon(row.connector, iconLoader) },
        trailing = trailing,
        onClick = onClick,
    )
}
