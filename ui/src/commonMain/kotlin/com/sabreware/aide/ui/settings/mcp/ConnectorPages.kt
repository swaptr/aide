package com.sabreware.aide.ui.settings.mcp

import com.sabreware.aide.core.designsystem.browse.WhileBrowsing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.sabreware.aide.core.designsystem.state.PaneFailed
import com.sabreware.aide.ui.settings.OAuthRedirectStrategySheet
import com.sabreware.aide.ui.settings.mcp.directories.ConnectorSourcesSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AutoDismissNotice
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.appMenuSection
import com.sabreware.aide.core.designsystem.PageScaffold
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.ScrollOwner
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.search.rememberSearchResults
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.Facet
import com.sabreware.aide.core.domain.connector.ConnectorCategory
import com.sabreware.aide.ui.settings.mcp.browse.ConnectorList
import com.sabreware.aide.core.domain.connector.ConnectorAuthType
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.ui.settings.mcp.browse.ConnectorBrowseViewModel
import com.sabreware.aide.ui.settings.mcp.browse.ConnectorCatalogContent
import com.sabreware.aide.ui.settings.mcp.browse.ConnectorConnectEffects
import com.sabreware.aide.ui.settings.mcp.browse.ConnectorDetailContent
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import com.sabreware.aide.core.designsystem.browse.BrowseNoMatches
import com.sabreware.aide.core.designsystem.browse.collectionBar
import com.sabreware.aide.core.designsystem.browse.selectHeaderAction
import com.sabreware.aide.core.designsystem.browse.collectionHeader
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.designsystem.browse.rememberBrowseResult
import com.sabreware.aide.core.designsystem.browse.rememberBrowseState
import com.sabreware.aide.core.designsystem.browse.rememberSelectionState
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.valueOrNull
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.rememberLabelEditor
import org.koin.compose.viewmodel.koinViewModel

/**
 * The connector flow's pages — each defined ONCE and rendered as a full app page OR a sheet page (chrome via
 * [PageScaffold] + `LocalPagePresentation`; navigation via `navigator()`). Wired into the app NavHost (Settings)
 * and [ConnectorDialog] (chat). Per-page `koinViewModel()` is shared across pages inside a sheet (one host owner)
 * and per-route in the NavHost (state is derived from singleton catalog/repo, so it stays consistent).
 */

@Composable
fun ConnectorHomePage() {
    val vm: McpSettingsViewModel = koinViewModel()
    val labelsVm: LabelsViewModel = koinViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val labels by labelsVm.labels.collectAsStateWithLifecycle()
    val redirect by vm.redirectStrategy.collectAsStateWithLifecycle()
    val nav = navigator()
    val editor = rememberLabelEditor(labelsVm)
    val servers = state.servers.valueOrNull.orEmpty()
    val browse = rememberBrowseState()
    val spec = remember(labels) { connectorSpec(labels) }
    val result = rememberBrowseResult(servers, spec, browse)
    val selection = rememberSelectionState()
    val runner = rememberActionRunner(connectorActions(vm, editor, { labels }, selection))
    var openUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    var signInOpen by rememberSaveable { mutableStateOf(false) }
    val header = collectionHeader(
        selection, runner, result.items, key = { it.url },
        normal = collectionBar(
            title = "Connectors",
            browse = browse,
            placeholder = "Search connectors",
            facets = result.facets,
            countLabel = { if (it == 1) "1 connector" else "$it connectors" },
            select = selectHeaderAction(selection, enabled = servers.size > 1),
            searchable = servers.isNotEmpty(),
        ),
    )
    // The page's actions as tiles above the list: every one visible, none behind a menu.
    val pageActions = listOf(
        AppMenuEntry(key = "add", title = "Add", leadingIconRes = Res.drawable.ic_lc_plus, onClick = { nav.navigate(ConnectorRoute.Catalog) }),
        AppMenuEntry(key = "sources", title = "Sources", leadingIconRes = Res.drawable.ic_lc_book_open, onClick = { sourcesOpen = true }),
        AppMenuEntry(key = "sign-in", title = "Sign-in", leadingIconRes = Res.drawable.ic_lc_globe, onClick = { signInOpen = true }),
    )
    PageScaffold(
        title = header.title ?: "Connectors",
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        scroll = ScrollOwner.Content,
    ) { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            // Surface action failures (toggle/remove/reconnect) inline; auto-dismiss like a snackbar.
            AutoDismissNotice(
                state.error,
                onDismiss = vm::clearError,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                item("page-actions") {
                    WhileBrowsing(browse, selection) { AppMenu(items = pageActions, layout = AppMenuLayout.actions()) }
                }
                when (val loaded = state.servers) {
                    UiState.Loading -> item("loading") { ConnectorsLoading() }
                    is UiState.Failed -> item("failed") { PaneFailed(loaded.message, modifier = Modifier.fillMaxWidth()) }
                    is UiState.Ready -> when {
                        servers.isEmpty() -> item("empty") {
                            NoMcpServersPlaceholder(onAdd = { nav.navigate(ConnectorRoute.Catalog) }, modifier = Modifier.fillMaxWidth())
                        }
                        else -> {
                            if (result.isEmpty) {
                                item("none") { BrowseNoMatches(browse, result) }
                            } else {
                                appMenuSection(result.items, key = { it.url }) { row ->
                                    ConnectorServerRow(row, labels, runner, selection, onToggle = vm::setEnabled, onOpen = { openUrl = row.url })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    servers.firstOrNull { it.url == openUrl }?.let { row ->
        ConnectorSheet(row, labels, runner, onToggle = vm::setEnabled, onDismiss = { openUrl = null })
    }
    if (sourcesOpen) ConnectorSourcesSheet(onDismiss = { sourcesOpen = false })
    if (signInOpen) {
        OAuthRedirectStrategySheet(selected = redirect, onSelect = vm::setRedirectStrategy, onDismiss = { signInOpen = false })
    }
}

/**
 * Every connector there is to add. The header's search asks the directories as you type (the catalogue is only
 * the popular few; the long tail lives in the registries) and the results filter like any other collection.
 */
@Composable
fun ConnectorCatalogPage() {
    val vm: ConnectorBrowseViewModel = koinViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val nav = navigator()
    val browse = rememberBrowseState()
    val query = browse.query.text.trim()
    val found = rememberSearchResults(query) { if (it.isEmpty()) emptyList() else vm.searchConnectors(it) }
    // Freshest install/connect status for a found row comes from the live catalogue when it lists it.
    val rows = remember(found.items, state.connectors) {
        found.items.map { row -> state.connectors.firstOrNull { it.connector.id == row.connector.id } ?: row }
    }
    val result = rememberBrowseResult(rows, CatalogSearchSpec, browse)
    val header = collectionBar(
        title = "Add connector",
        browse = browse,
        placeholder = "Search connectors",
        facets = result.facets,
        countLabel = { if (it == 1) "1 connector" else "$it connectors" },
        actions = listOf(HeaderAction(Res.drawable.ic_lc_plus, "Custom server", onClick = { nav.navigate(ConnectorRoute.AddCustom()) })),
    )
    PageScaffold(
        title = header.title ?: "Add connector",
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        // A tabbed pager of lazy lists, or one list of results.
        scroll = ScrollOwner.Content,
    ) { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            if (query.isEmpty()) {
                ConnectorCatalogContent(
                    state = state,
                    iconLoader = vm.iconLoader,
                    onRowClick = { nav.navigate(ConnectorRoute.Detail(it.id)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                if (found.settled && result.isEmpty) {
                    BrowseNoMatches(browse, result)
                } else {
                    ConnectorList(
                        loading = !found.settled && found.items.isEmpty(),
                        error = null,
                        rows = result.items,
                        iconLoader = vm.iconLoader,
                        busyUrl = state.busyUrl,
                        onRowClick = { nav.navigate(ConnectorRoute.Detail(it.id)) },
                    )
                }
            }
        }
    }
}

/** Found connectors narrow by what they are for and how they sign in. Relevance comes from the directory. */
private val CatalogSearchSpec: BrowseSpec<ConnectorBrowseViewModel.ConnectorRow> by lazy {
    BrowseSpec(
        key = { it.connector.id },
        // The directory already matched the query; the text here only keeps the filter pass from dropping rows.
        text = { listOf(it.connector.name, it.connector.description, it.connector.serverUrl) },
        facets = listOf(
            Facet(
                id = "category",
                label = "Kind",
                valuesOf = { listOf(it.connector.category.name) },
                optionLabel = { ConnectorCategory.valueOf(it).label },
            ),
            Facet(
                id = "sign-in",
                label = "Sign-in",
                valuesOf = { listOf(it.connector.authType.name) },
                optionLabel = { ConnectorAuthType.valueOf(it).signInLabel() },
            ),
            Facet(
                id = "added",
                label = "Status",
                valuesOf = { listOf(if (it.installed) "added" else "new") },
                optionLabel = { if (it == "added") "Added" else "Not added" },
            ),
        ),
    )
}

private fun ConnectorAuthType.signInLabel(): String = when (this) {
    ConnectorAuthType.NONE -> "None needed"
    ConnectorAuthType.HEADER -> "Key"
    ConnectorAuthType.OAUTH -> "Account"
    ConnectorAuthType.UNKNOWN -> "Checked on connect"
}

@Composable
fun ConnectorDetailPage(connectorId: String) {
    val vm: ConnectorBrowseViewModel = koinViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val nav = navigator()
    ConnectorConnectEffects(vm) // OAuth Custom Tab launches (this VM is the one connecting)
    // Clear any stale connect-error when a fresh detail opens; a failed connect re-sets state.message.
    LaunchedEffect(connectorId) { vm.clearMessage() }
    val row = state.connectors.firstOrNull { it.connector.id == connectorId }
    val connector = row?.connector
    PageScaffold(title = connector?.name ?: "Connector") { contentModifier ->
        if (connector != null) {
            ConnectorDetailContent(
                connector = connector,
                installed = row.installed,
                busy = state.busyUrl == connector.serverUrl,
                onConnect = {
                    val connected = vm.connectOrPromptHeader(connector) { url -> nav.navigate(ConnectorRoute.AddCustom(url)) }
                    // OAUTH/UNKNOWN open a Custom Tab via THIS page's ConnectorConnectEffects collector, which
                    // is torn down on pop. connect() is fire-and-forget (discovery+registration run async, THEN
                    // the auth URL is emitted), so popping now drops the URL into a zero-subscriber SharedFlow →
                    // browser never opens → the flow hangs on receiver.await() → spinner forever. Stay mounted;
                    // the page shows busy/error inline and flips to Connected when sign-in completes. Only NONE
                    // (no browser) is safe to pop back to the catalog immediately.
                    if (connected && connector.authType == ConnectorAuthType.NONE) nav.goBack()
                },
                onDisconnect = { vm.disconnect(connector.serverUrl); nav.goBack() },
                error = state.message,
                modifier = contentModifier,
            )
        } else if (state.loading) {
            // Skeleton while the catalog's first emission is in flight — never a blank page under the title.
            val shimmer = rememberSkeletonShimmer()
            Column(contentModifier.fillMaxWidth()) {
                repeat(3) { SkeletonListRow(shimmer, lines = 2, index = it) }
            }
        } else {
            // Loaded (or failed) and the id matched nothing — say so instead of rendering nothing.
            Placeholder(
                modifier = contentModifier.fillMaxWidth(),
                iconRes = Res.drawable.ic_mcp,
                title = "Connector unavailable",
                subtitle = state.message ?: "This connector is not in the catalog.",
            )
        }
    }
}

@Composable
fun AddConnectorPage(initialUrl: String) {
    val vm: McpSettingsViewModel = koinViewModel()
    val nav = navigator()
    // A pasted OAuth-protected URL runs the sign-in flow: open its authorization URL in the system browser,
    // and cancel an unfinished flow if the user returns without completing it. The page stays mounted while
    // the form is "Connecting…", so this collector is live when the coordinator emits the URL.
    val openUrl = LocalPlatformAffordances.current.urlOpener?.rememberLauncher()
    if (openUrl != null) {
        LaunchedEffect(Unit) { vm.launchBrowser.collect { openUrl(it) } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onReturnedToForeground() }
    PageScaffold(title = "Custom connector") { contentModifier ->
        McpAddServerForm(
            onCancel = { nav.goBack() },
            onConnected = { nav.goBack() },
            initialUrl = initialUrl,
            viewModel = vm,
            modifier = contentModifier.padding(horizontal = 24.dp),
        )
    }
}
