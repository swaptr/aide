package com.sabreware.aide.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.AutoDismissNotice
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.PageScaffold
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.ScrollOwner
import com.sabreware.aide.core.designsystem.SkeletonLeading
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.SwipeableTabbedContent
import com.sabreware.aide.core.designsystem.browse.BrowseNoMatches
import com.sabreware.aide.core.designsystem.browse.collectionBar
import com.sabreware.aide.core.designsystem.browse.selectHeaderAction
import com.sabreware.aide.core.designsystem.browse.collectionHeader
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.designsystem.browse.rememberBrowseResult
import com.sabreware.aide.core.designsystem.browse.rememberBrowseState
import com.sabreware.aide.core.designsystem.browse.rememberSelectionState
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.isLoading
import com.sabreware.aide.core.designsystem.state.map
import com.sabreware.aide.core.designsystem.state.valueOrNull
import com.sabreware.aide.core.domain.browse.BrowseQuery
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.rememberLabelEditor
import com.sabreware.aide.ui.models.connections.ConnectionItem
import com.sabreware.aide.ui.models.connections.ConnectionsViewModel
import com.sabreware.aide.ui.models.connections.availableServices
import com.sabreware.aide.ui.models.connections.connectionActions
import com.sabreware.aide.ui.models.connections.noticeText
import com.sabreware.aide.core.designsystem.browse.ActionRail
import com.sabreware.aide.core.designsystem.browse.ActionRunner
import kotlinx.coroutines.launch
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import org.koin.compose.viewmodel.koinViewModel

/**
 * The model select/add flow's pages — each defined ONCE and rendered as a full app page OR a sheet page
 * (chrome via [PageScaffold] + `LocalPagePresentation`; navigation via `navigator()`). Wired into the app
 * NavHost (from Settings) and [ModelDialog] (from chat), mirroring the connector flow. A page's
 * `koinViewModel()` is shared across pages inside a sheet (one host owner) and per-route in the NavHost;
 * since the state derives from the singleton registry it stays consistent across both.
 */

/** The flow's ONE horizontal inset for page-body content that pads itself (menus/rows own their own). */
private val PageInset = 20.dp

/** The flow's one loading look: list-shaped skeleton rows over a single shimmer, where a list will land. */
@Composable
private fun ModelListSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberSkeletonShimmer()
    Column(modifier.fillMaxWidth()) {
        repeat(3) { SkeletonListRow(shimmer, index = it, leading = SkeletonLeading.Glyph) }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Home — the user's models, searchable across everything. Unsearched it lists what is theirs (pinned first,
// then by what each model does); a query or a filter widens it to every model every connection offers.
// Built on the shared collection kit: search, facets, selection and actions come from one spec and one
// action list, so a row's long-press sheet and a multi-selection offer the same things.
// ---------------------------------------------------------------------------------------------------------

@Composable
fun ModelHomePage(filter: Pair<String, String>? = null) {
    val vm: ModelsViewModel = koinViewModel()
    val labelsVm: LabelsViewModel = koinViewModel()
    val connectionsVm: ConnectionsViewModel = koinViewModel()
    val nav = navigator()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val labels by labelsVm.labels.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val activeChatId by vm.activeModelId.collectAsStateWithLifecycle()
    val connections by connectionsVm.items.collectAsStateWithLifecycle()
    val editor = rememberLabelEditor(labelsVm)

    val library = remember(state, labels, activeChatId, sources) { buildLibrary(state, labels, activeChatId, vm::infoOf) }
    val spec = remember(labels, sources) { librarySpec(labels) { vm.infoOf(ProviderId(it)).name } }
    // Opened from an automatic tag, the page starts filtered by it; otherwise unfiltered.
    val browse = rememberBrowseState(
        filter?.let { (facet, option) -> BrowseQuery(filters = mapOf(facet to setOf(option))) } ?: BrowseQuery(),
    )
    // Unsearched: the user's own models. Searching or filtering: everything, so a filter like "Cloud"
    // answers "which cloud models can I use" rather than "which of mine are cloud".
    val pool = remember(library, browse.query.isActive) { if (browse.query.isActive) library else library.filter { it.mine } }
    val result = rememberBrowseResult(pool, spec, browse)
    val selection = rememberSelectionState()
    val sheets = rememberModelSheets()
    val runner = rememberActionRunner(
        libraryActions(
            vm = vm,
            editor = editor,
            labels = { labels },
            selection = selection,
            onOpenConnection = { nav.navigate(ModelRoute.Connection(it.provider.value)) },
            onSampler = sheets::openSampler,
        ),
    )
    val loaded = state.rows !is UiState.Loading && state.voiceRows !is UiState.Loading

    val hasConnections = connections.valueOrNull.orEmpty().isNotEmpty()
    val header = collectionHeader(
        selection, runner, result.items, key = { it.id },
        normal = collectionBar(
            title = "Models",
            browse = browse,
            placeholder = "Search models",
            facets = result.facets,
            countLabel = ::modelCount,
            select = selectHeaderAction(selection, enabled = result.items.size > 1),
        ),
    )
    // The page's actions as tiles above the models: every one visible, none behind a menu.
    val pageActions = listOfNotNull(
        AppMenuEntry(key = "add", title = "Add model", leadingIconRes = Res.drawable.ic_lc_plus, onClick = { nav.navigate(ModelRoute.AddPick) }),
        AppMenuEntry(key = "connect", title = "Connections", leadingIconRes = Res.drawable.ic_lc_plug, onClick = { nav.navigate(ModelRoute.Connections) }),
        AppMenuEntry(key = "tags", title = "Tags", leadingIconRes = Res.drawable.ic_lc_tag, onClick = { nav.navigate(ModelRoute.Tags) }),
        AppMenuEntry(key = "refresh", title = "Refresh", leadingIconRes = Res.drawable.ic_lc_rotate_cw, onClick = connectionsVm::refreshAll)
            .takeIf { hasConnections },
    )

    PageScaffold(
        title = header.title ?: "Models",
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        scroll = ScrollOwner.Content,
    ) { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            AutoDismissNotice(
                state.errorMessage,
                onDismiss = vm::dismissError,
                modifier = Modifier.padding(horizontal = PageInset, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (!selection.active && !browse.searching) {
                    item("page-actions") { AppMenu(items = pageActions, layout = AppMenuLayout.actions()) }
                }
                when {
                    !loaded -> item("loading") { ModelListSkeleton() }
                    library.none { it.mine } && !browse.query.isActive -> item("empty") {
                        NoModelsPlaceholder(onAddModel = { nav.navigate(ModelRoute.AddPick) }, modifier = Modifier.fillMaxWidth())
                    }
                    result.isEmpty -> item("none") { BrowseNoMatches(browse, result) }
                    else -> librarySections(result) { item ->
                        LibraryRow(
                            item = item,
                            selection = selection,
                            runner = runner,
                            loadingModelId = state.loadingModelId,
                            onOpen = sheets::open,
                        )
                    }
                }
            }
        }
    }
    ModelSheetHost(sheets, library, runner, editor, vm)
}

internal fun modelCount(n: Int): String = if (n == 1) "1 model" else "$n models"


// ---------------------------------------------------------------------------------------------------------
// Add a model — pick a modality, then ONE page for it: a tab for on-device, one per connection that serves
// it, and a last "Connect" tab listing the services that could. Connections are the tabs because they are
// what the user holds: two OpenRouter accounts are two tabs, and the services catalogue is listed once.
// ---------------------------------------------------------------------------------------------------------

@Composable
fun AddModelPickPage() {
    val nav = navigator()
    PageScaffold(title = "Add a model") { contentModifier ->
        Column(contentModifier) {
            ModalityStep(onPick = { nav.navigate(ModelRoute.AddModels(it.name)) })
        }
    }
}

/** A tab of the add-model page. */
private sealed interface ModelSource {
    val label: String

    data object OnDevice : ModelSource {
        override val label = "On-device"
    }

    data class Connected(val item: ConnectionItem) : ModelSource {
        override val label get() = item.name
    }

    data object Connect : ModelSource {
        override val label = "Connect"
    }
}

/** The modality a group's models are served under — what decides which connections get a tab. */
private fun ModalityGroup.modality(): Modality = pickModality() ?: Modality.Chat

@Composable
fun AddModelModelsPage(modality: ModalityGroup) {
    val vm: ModelsViewModel = koinViewModel()
    val connectionsVm: ConnectionsViewModel = koinViewModel()
    val labelsVm: LabelsViewModel = koinViewModel()
    val nav = navigator()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val connections by connectionsVm.items.collectAsStateWithLifecycle()
    val labels by labelsVm.labels.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val editor = rememberLabelEditor(labelsVm)

    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImportName by rememberSaveable { mutableStateOf("") }
    val pickModelFile = LocalPlatformAffordances.current.modelFilePicker?.rememberLauncher { uri, name ->
        pendingImportUri = uri
        pendingImportName = name
    }
    // Two independent halves, both required: `canImportModels` is whether anything can RECEIVE the file (a
    // bound ModelImporter), `pickModelFile` is whether the host can pick one. Either absent, no import action.
    val onImport = pickModelFile?.takeIf { modality == ModalityGroup.LANGUAGE && vm.canImportModels }

    val samplerOverrides by vm.samplerOverrides.collectAsStateWithLifecycle()

    val served = modality.modality()
    val sourceTabs = remember(modality, connections) {
        buildList {
            if (modality != ModalityGroup.IMAGE) add(ModelSource.OnDevice)
            connections.valueOrNull.orEmpty().filter { served in it.modalities }.mapTo(this) { ModelSource.Connected(it) }
            add(ModelSource.Connect)
        }
    }
    var tab by rememberSaveable(modality) { mutableStateOf(0) }
    val activeChatId by vm.activeModelId.collectAsStateWithLifecycle()
    val library = remember(state, labels, sources, activeChatId) { buildLibrary(state, labels, activeChatId, vm::infoOf) }
    val selection = rememberSelectionState()
    val sheets = rememberModelSheets()
    val runner = rememberActionRunner(
        libraryActions(vm, editor, { labels }, selection, onOpenConnection = { nav.navigate(ModelRoute.Connection(it.provider.value)) }, onSampler = sheets::openSampler),
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var testNotice by remember { mutableStateOf<String?>(null) }
    val connectionRunner = rememberActionRunner(
        listOf(
            com.sabreware.aide.core.designsystem.browse.CollectionAction<ConnectionItem>(
                id = "open",
                label = "Open",
                iconRes = Res.drawable.ic_lc_plug,
                scope = com.sabreware.aide.core.designsystem.browse.ActionScope.One,
            ) { nav.navigate(ModelRoute.Connection(it.single().id)) },
        ) + connectionActions(
            vm = connectionsVm,
            editor = editor,
            labels = { labels },
            onEdit = { nav.navigate(ModelRoute.Connect(it.service?.id.orEmpty(), it.id)) },
            onTest = { item -> scope.launch { testNotice = connectionsVm.test(item.id).noticeText(item.name) } },
        ),
    )

    // ONE search for the page: the text and filters carry across tabs, so a query can be tried on each source.
    val browse = rememberBrowseState()
    val current = sourceTabs[tab.coerceAtMost(sourceTabs.lastIndex)]
    val currentResult = rememberBrowseResult(
        remember(library, current, modality) { sourceItems(library, current, modality) },
        remember(labels, current) { sourceSpec(labels, current) },
        browse,
    )
    // The header follows the tab: Import on the device's tab, a connection's own actions on its tab, nothing
    // to search on the Connect tab.
    val tabActions = when (current) {
        ModelSource.OnDevice -> listOfNotNull(
            onImport?.let { HeaderAction(Res.drawable.ic_lc_folder_plus, "Import file", onClick = it) },
        )
        is ModelSource.Connected -> emptyList()
        ModelSource.Connect -> emptyList()
    }
    val header = collectionHeader(
        selection, runner, currentResult.items, key = { it.id },
        normal = collectionBar(
            title = "${modality.label} models",
            browse = browse,
            placeholder = "Search ${current.label}",
            facets = currentResult.facets,
            countLabel = ::modelCount,
            actions = tabActions,
            select = selectHeaderAction(selection, enabled = currentResult.items.size > 1).takeIf { current != ModelSource.Connect },
            searchable = current != ModelSource.Connect,
        ),
    )

    // Every tab is a lazy list (or its own scrolling connect catalogue).
    PageScaffold(
        title = header.title ?: "${modality.label} models",
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        scroll = ScrollOwner.Content,
    ) { contentModifier ->
        testNotice?.let { AutoDismissNotice(it, onDismiss = { testNotice = null }, severity = com.sabreware.aide.core.designsystem.NoticeSeverity.Info) }
        SwipeableTabbedContent(
            tabs = sourceTabs.map { it.label },
            selectedIndex = tab.coerceAtMost(sourceTabs.lastIndex),
            onSelectIndex = { tab = it },
            modifier = contentModifier.fillMaxSize(),
        ) { page ->
            when (val source = sourceTabs[page]) {
                ModelSource.Connect -> LazyColumn(Modifier.fillMaxSize()) {
                    availableServices(connectionsVm.servicesFor(served)) { nav.navigate(ModelRoute.Connect(it.id)) }
                }
                else -> SourceList(
                    connectionRunner = connectionRunner,
                    source = source,
                    modality = modality,
                    library = library,
                    labels = labels,
                    browse = browse,
                    state = state,
                    runner = runner,
                    selection = selection,
                    onOpen = sheets::open,
                )
            }
        }
    }

    ModelSheetHost(sheets, library, runner, editor, vm)

    pendingImportUri?.let { uri ->
        ImportModelSheet(
            defaultName = pendingImportName,
            importProgress = importProgress,
            onImport = { config -> vm.importModel(uri, config) { pendingImportUri = null } },
            onDismiss = { pendingImportUri = null },
        )
    }
}

@Composable
private fun ModalityStep(onPick: (ModalityGroup) -> Unit) {
    val modalities = buildList {
        add(ModalityGroup.LANGUAGE)
        add(ModalityGroup.SPEECH_TO_TEXT)
        add(ModalityGroup.TEXT_TO_SPEECH)
        if (IS_IMAGE_SUPPORTED) add(ModalityGroup.IMAGE)
    }
    // A grid of self-describing cards, not a list of bare labels: "Language" / "Voice typing" mean
    // nothing on their own, and this fork is the first thing a new user meets.
    AppMenu(
        layout = AppMenuLayout.Grid(),
        items = modalities.map { modality ->
            AppMenuEntry(
                key = modality.name,
                title = modality.label,
                subtitle = modality.blurb,
                leadingIconRes = modality.iconRes(),
                onClick = { onPick(modality) },
            )
        },
    )
}

/** A tab's models: everything on this device for the modality, or everything one connection serves for it. */
private fun sourceItems(library: List<LibraryItem>, source: ModelSource, modality: ModalityGroup): List<LibraryItem> =
    when (source) {
        ModelSource.OnDevice -> library.filter { it.kind == ConnectionKind.OnDevice && it.group == modality }
        is ModelSource.Connected -> library.filter { it.provider.value == source.item.id && it.group == modality }
        ModelSource.Connect -> emptyList()
    }

/** Every source tab sections the same way ([AvailabilityGrouping]): installed, built in, downloading, available. */
private fun sourceSpec(labels: com.sabreware.aide.core.domain.label.Labels, source: ModelSource) =
    librarySpec(labels) { source.label }.withGroup(AvailabilityGrouping)

/**
 * One source tab as a plain list, searched and filtered by the page header's [browse]. An installed model is
 * listed like any other, so it can always be opened and used from the modality's own page.
 */
@Composable
private fun SourceList(
    connectionRunner: ActionRunner<ConnectionItem>,
    source: ModelSource,
    modality: ModalityGroup,
    library: List<LibraryItem>,
    labels: com.sabreware.aide.core.domain.label.Labels,
    browse: com.sabreware.aide.core.designsystem.browse.BrowseState,
    state: ModelsUiState,
    runner: ActionRunner<LibraryItem>,
    selection: com.sabreware.aide.core.designsystem.browse.SelectionState,
    onOpen: (LibraryItem) -> Unit,
) {
    val items = remember(library, source, modality) { sourceItems(library, source, modality) }
    val spec = remember(labels, source) { sourceSpec(labels, source) }
    val result = rememberBrowseResult(items, spec, browse)
    val connection = (source as? ModelSource.Connected)?.item
    val loading = when {
        connection != null -> connection.status == com.sabreware.aide.ui.models.connections.ConnectionStatus.Refreshing || state.rows.isLoading
        modality.speechKind() != null -> state.voiceRows.isLoading
        else -> state.rows.isLoading
    }
    LazyColumn(Modifier.fillMaxSize()) {
        // A connection's own actions as tiles, above its models — the tab already names it.
        if (connection != null && !selection.active && !browse.searching) {
            item("connection") { ActionRail(connectionRunner, connection, except = setOf("open", "pin")) }
        }
        when {
            items.isEmpty() && loading -> item("loading") { ModelListSkeleton() }
            items.isEmpty() -> item("empty") {
                Placeholder(
                    modifier = Modifier.fillMaxWidth(),
                    subtitle = when {
                        connection == null -> "No on-device ${modality.label.lowercase()} models."
                        modality == ModalityGroup.LANGUAGE -> "No models yet. Refresh to fetch them."
                        else -> "${connection.name} serves no models here."
                    },
                )
            }
            result.isEmpty -> item("none") { BrowseNoMatches(browse, result) }
            else -> librarySections(result) { item ->
                LibraryRow(item, selection, runner, state.loadingModelId, onOpen)
            }
        }
    }
}

/** Pin as a header action: one tap, and the icon says which way it will go. */
internal fun pinHeaderAction(pinned: Boolean, onToggle: () -> Unit): HeaderAction =
    HeaderAction(if (pinned) Res.drawable.ic_lc_pin_off else Res.drawable.ic_lc_pin, if (pinned) "Unpin" else "Pin", onClick = onToggle)
