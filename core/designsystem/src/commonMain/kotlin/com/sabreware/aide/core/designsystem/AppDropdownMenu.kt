package com.sabreware.aide.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

sealed interface AppDropdownItem

data object AppDropdownDivider : AppDropdownItem

/**
 * The app's action menu. Presented as a bottom [AppDialog] — not an anchored popup dropdown — so every
 * menu opens from the bottom edge: reachable one-handed and visually consistent with the app's other
 * sheets. [items] render as grouped rows ([AppMenu]); each [AppDropdownDivider] starts a new group.
 * Tapping a row animates the sheet closed and then runs its action. The sheet is mounted only while
 * [expanded]; every dismissal path (scrim, back, drag-away, or a row tap) calls [onDismissRequest].
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<AppDropdownItem>,
    header: AppMenuSheetHeader? = null,
) {
    if (!expanded) return
    AppDialog(
        onDismiss = onDismissRequest,
        title = header?.title,
        subtitle = header?.subtitle,
    ) { controller ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            actionGroups(items).forEach { group ->
                AppMenu(
                    items = group.map { action ->
                        AppMenuEntry(
                            title = action.label,
                            leadingIconRes = action.iconRes,
                            enabled = action.enabled,
                            destructive = action.destructive,
                            // Animate the sheet out first, then run the action (mirrors the old
                            // dismiss-then-invoke order of the DropdownMenu item).
                            onClick = { controller.close(andThen = action.onClick) },
                        )
                    },
                    // The wrapping Column insets the content horizontally; the group fills that width.
                    groupPadding = PaddingValues(0.dp),
                )
            }
        }
    }
}

/** Split a flat item list into contiguous action groups, breaking on each [AppDropdownDivider]. */
private fun actionGroups(items: List<AppDropdownItem>): List<List<AppMenuAction>> {
    val groups = mutableListOf<List<AppMenuAction>>()
    var current = mutableListOf<AppMenuAction>()
    for (item in items) when (item) {
        is AppDropdownDivider -> if (current.isNotEmpty()) {
            groups += current
            current = mutableListOf()
        }
        is AppMenuAction -> current += item
    }
    if (current.isNotEmpty()) groups += current
    return groups
}
