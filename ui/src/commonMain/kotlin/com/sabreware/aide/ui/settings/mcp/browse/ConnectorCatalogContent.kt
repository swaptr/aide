package com.sabreware.aide.ui.settings.mcp.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sabreware.aide.core.designsystem.appMenuSection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.SkeletonLeading
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.SwipeableTabbedContent
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.PaneFailed
import com.sabreware.aide.core.domain.connector.Connector
import com.sabreware.aide.core.domain.connector.ConnectorCategory

/**
 * The browsable connector catalog body — category tabs over a flat connector list (with a loading skeleton
 * while the first emission is in flight). Pure content, no scaffold/sheet, so it is shared by the full-screen
 * catalog (`ConnectorCatalogPage`) and the chat connector sheet's
 * Catalog page. Each row's trailing affordance still reflects connecting/connected state; *managing* installed
 * servers lives on the connectors home, not here, so this is browse-only.
 *
 * Host it in a bounded-height parent (the full screen's scaffold content, or a fixed-height box in the sheet)
 * — the tab pager + list own their own scrolling.
 */
@Composable
fun ConnectorCatalogContent(
    state: ConnectorBrowseViewModel.BrowseState,
    iconLoader: ImageLoader,
    onRowClick: (Connector) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = remember(state.connectors) {
        listOf(ConnectorCategory.ALL) +
            ConnectorCategory.entries.filter { c ->
                c != ConnectorCategory.ALL && state.connectors.any { it.connector.category == c }
            }
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    val safeTab = tab.coerceIn(0, categories.lastIndex)

    SwipeableTabbedContent(
        tabs = categories.map { it.label },
        selectedIndex = safeTab,
        onSelectIndex = { tab = it },
        modifier = modifier.fillMaxSize(),
    ) { page ->
        val category = categories[page]
        val rows = if (category == ConnectorCategory.ALL) {
            state.connectors
        } else {
            state.connectors.filter { it.connector.category == category }
        }
        ConnectorList(
            loading = state.loading,
            error = state.message,
            rows = rows,
            iconLoader = iconLoader,
            busyUrl = state.busyUrl,
            onRowClick = onRowClick,
        )
    }
}

@Composable
internal fun ConnectorList(
    loading: Boolean,
    error: String?,
    rows: List<ConnectorBrowseViewModel.ConnectorRow>,
    iconLoader: ImageLoader,
    busyUrl: String?,
    onRowClick: (Connector) -> Unit,
) {
    if (loading) {
        // Skeleton instead of the empty state while the first emission is still in flight, so "No connectors"
        // never flashes before the catalog has loaded.
        val shimmer = rememberSkeletonShimmer()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            userScrollEnabled = false,
        ) {
            // Title + one subtitle line beside the leading tile — ConnectorRowItem's exact shape.
            items(8) { SkeletonListRow(shimmer, lines = 2, leading = SkeletonLeading.Media) }
        }
        return
    }
    if (rows.isEmpty() && error != null) {
        // The catalog failed to load — say so, rather than reading as an empty catalog.
        PaneFailed(error)
        return
    }
    if (rows.isEmpty()) {
        // No center action button — add-custom lives in the top bar ("+") of the hosting screen/page.
        Placeholder(
            modifier = Modifier.fillMaxSize(),
            iconRes = Res.drawable.ic_mcp,
            title = "No connectors",
            subtitle = "Add a custom MCP server by URL.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        appMenuSection(rows, key = { it.connector.id }) { row ->
            ConnectorRowItem(
                row = row,
                iconLoader = iconLoader,
                busy = busyUrl == row.connector.serverUrl,
                onClick = { onRowClick(row.connector) },
            )
        }
    }
}
