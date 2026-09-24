package com.sabreware.aide.ui.settings.mcp.directories

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuToggle
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectoryKind
import org.jetbrains.compose.resources.DrawableResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Which connector sources feed the "Add connector" list, as a sheet over the Connectors page (its "Sources"
 * tile). Each row turns a source on or off; the list and search update live.
 */
@Composable
fun ConnectorSourcesSheet(onDismiss: () -> Unit, viewModel: ConnectorDirectoriesViewModel = koinViewModel()) {
    AppDialog(onDismiss = onDismiss, title = "Sources") { _ -> ConnectorSourcesContent(viewModel) }
}


@Composable
private fun ConnectorSourcesContent(viewModel: ConnectorDirectoriesViewModel) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    AppMenu(
        items = rows.map { row ->
            AppMenuEntry(
                key = row.descriptor.id.value,
                title = row.descriptor.title,
                subtitle = row.descriptor.description,
                leadingIconRes = iconFor(row.descriptor.kind),
                toggle = AppMenuToggle(checked = row.enabled, onCheckedChange = { viewModel.setEnabled(row.descriptor.id, it) }),
            )
        },
    )
}

private fun iconFor(kind: ConnectorDirectoryKind): DrawableResource = when (kind) {
    ConnectorDirectoryKind.BUNDLED -> Res.drawable.ic_lc_book_open
    ConnectorDirectoryKind.REGISTRY -> Res.drawable.ic_lc_database
}
