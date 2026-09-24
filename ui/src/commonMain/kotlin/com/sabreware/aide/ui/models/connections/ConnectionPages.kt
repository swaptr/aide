package com.sabreware.aide.ui.models.connections

import com.sabreware.aide.core.designsystem.browse.WhileBrowsing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sabreware.aide.core.designsystem.AidePill
import com.sabreware.aide.ui.models.modelCount
import com.sabreware.aide.core.designsystem.PlaceholderAction
import com.sabreware.aide.core.designsystem.AppDialogSize
import com.sabreware.aide.core.designsystem.AppDialog
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.AppMenu
import com.sabreware.aide.core.designsystem.AppMenuEntry
import com.sabreware.aide.core.designsystem.AppMenuGroupInset
import com.sabreware.aide.core.designsystem.AppMenuLayout
import com.sabreware.aide.core.designsystem.appMenuSection
import com.sabreware.aide.core.designsystem.browse.ActionRail
import com.sabreware.aide.core.designsystem.AppMenuSheetHeader
import com.sabreware.aide.core.designsystem.AutoDismissNotice
import com.sabreware.aide.core.designsystem.ErrorLine
import com.sabreware.aide.core.domain.error.UserError
import com.sabreware.aide.core.designsystem.NoticeSeverity
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.PageScaffold
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.ScrollOwner
import com.sabreware.aide.core.designsystem.SkeletonLeading
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.browse.ActionRunner
import com.sabreware.aide.core.designsystem.browse.ActionScope
import com.sabreware.aide.core.designsystem.browse.BrowseNoMatches
import com.sabreware.aide.core.designsystem.browse.CollectionAction
import com.sabreware.aide.core.designsystem.browse.collectionBar
import com.sabreware.aide.core.designsystem.browse.selectHeaderAction
import com.sabreware.aide.core.designsystem.browse.Confirmation
import com.sabreware.aide.core.designsystem.browse.SelectionState
import com.sabreware.aide.core.designsystem.browse.collectionHeader
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.designsystem.browse.rememberBrowseResult
import com.sabreware.aide.core.designsystem.browse.rememberBrowseState
import com.sabreware.aide.core.designsystem.browse.rememberSelectionState
import com.sabreware.aide.core.designsystem.navigation.navigator
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.valueOrNull
import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.Facet
import com.sabreware.aide.core.domain.browse.Grouping
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.connection.ServiceDescriptor
import kotlinx.coroutines.flow.flowOf
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.tagFacet
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.provider.ConnectionTestResult
import com.sabreware.aide.core.designsystem.relativeTimeLabel
import com.sabreware.aide.ui.labels.LabelEditor
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.labelActions
import com.sabreware.aide.ui.labels.rememberLabelEditor
import com.sabreware.aide.ui.models.ModelRoute
import com.sabreware.aide.ui.models.LibraryRow
import com.sabreware.aide.ui.models.ModelsViewModel
import com.sabreware.aide.ui.models.buildLibrary
import com.sabreware.aide.ui.models.iconRes
import com.sabreware.aide.ui.models.libraryActions
import com.sabreware.aide.ui.models.librarySections
import com.sabreware.aide.ui.models.librarySpec
import com.sabreware.aide.ui.models.ModelSheetHost
import com.sabreware.aide.ui.models.rememberModelSheets
import com.sabreware.aide.ui.models.pinHeaderAction
import com.sabreware.aide.ui.models.tagsEntry
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

// ---------------------------------------------------------------------------------------------------------
// Connections — the one place accounts and endpoints are managed. Built entirely on the shared collection
// kit: a spec says how to search, filter and group connections; the actions below serve a row's long-press
// sheet, a connection page's menu and a multi-selection alike.
// ---------------------------------------------------------------------------------------------------------

private fun connectionSpec(labels: Labels): BrowseSpec<ConnectionItem> = BrowseSpec(
    key = { it.id },
    text = { listOfNotNull(it.name, it.connection.label, it.service?.name, it.host) + it.tags },
    facets = listOf(
        Facet(
            id = "location",
            label = "Where it runs",
            valuesOf = { listOf(it.kind.name) },
            optionLabel = { ConnectionKind.valueOf(it).label },
            options = listOf(ConnectionKind.SelfHosted.name, ConnectionKind.Cloud.name),
        ),
        Facet(id = "service", label = "Service", valuesOf = { listOfNotNull(it.service?.name) }),
        Facet(
            id = "status",
            label = "Status",
            valuesOf = { listOf(it.status.name) },
            optionLabel = { ConnectionStatus.valueOf(it).label },
            options = ConnectionStatus.entries.map { it.name },
        ),
        labels.tagFacet { LabelSubject.connection(it.id) },
    ),
    order = compareBy { it.name.lowercase() },
    group = Grouping(of = { it.kind.label }, rank = { title -> ConnectionKind.entries.indexOfFirst { it.label == title } }),
)

/**
 * The actions on connections, for one row or a selection: Refresh, Rename, Tags, Pin, Edit, Test, Disconnect.
 * Disconnecting asks first — it takes the key, the cached models and every label on them.
 */
private fun connectionActions(
    vm: ConnectionsViewModel,
    editor: LabelEditor,
    labels: () -> Labels,
    onEdit: (ConnectionItem) -> Unit,
    onTest: (ConnectionItem) -> Unit,
    onRemoved: (List<ConnectionItem>) -> Unit = {},
): List<CollectionAction<ConnectionItem>> = listOf(
    CollectionAction(id = "refresh", label = "Refresh", iconRes = Res.drawable.ic_lc_rotate_cw) { vm.refresh(it.map(ConnectionItem::id)) },
) + labelActions(
    editor = editor,
    labels = labels,
    subject = { LabelSubject.connection(it.id) },
    name = { it.name },
    original = { it.connection.label },
) + listOf(
    CollectionAction(id = "edit", label = "Edit", iconRes = Res.drawable.ic_lc_settings, scope = ActionScope.One) { onEdit(it.single()) },
    CollectionAction(id = "test", label = "Test", iconRes = Res.drawable.ic_lc_plug, scope = ActionScope.One) { onTest(it.single()) },
    CollectionAction(
        id = "disconnect",
        label = "Remove",
        iconRes = Res.drawable.ic_lc_unlink,
        destructive = true,
        confirm = { targets ->
            Confirmation(
                title = if (targets.size == 1) "Remove ${targets.single().name}?" else "Remove ${targets.size} connections?",
                message = "Removes the key, its models and their names and tags. Chats keep their messages.",
                confirmLabel = "Remove",
            )
        },
    ) { targets ->
        vm.remove(targets.map(ConnectionItem::id))
        onRemoved(targets)
    },
)

/**
 * [connectionActions], wired the ONE way every surface needs them: Edit opens the connect form in the host's
 * flow, Test reports its result through [onNotice]. A page adds only what differs — what removal leaves behind.
 */
@Composable
internal fun rememberConnectionRunner(
    vm: ConnectionsViewModel,
    editor: LabelEditor,
    labels: () -> Labels,
    onNotice: (Pair<String, NoticeSeverity>) -> Unit,
    onRemoved: (List<ConnectionItem>) -> Unit = {},
): ActionRunner<ConnectionItem> {
    val nav = navigator()
    val scope = rememberCoroutineScope()
    return rememberActionRunner(
        connectionActions(
            vm = vm,
            editor = editor,
            labels = labels,
            onEdit = { nav.navigate(ModelRoute.Connect(it.service?.id.orEmpty(), it.id)) },
            onTest = { item -> scope.launch { onNotice(vm.test(item.id).notice(item.name)) } },
            onRemoved = onRemoved,
        ),
    )
}

/** The test result as a notice — the one-shot feedback a Test tap owes the user. */
private fun ConnectionTestResult.notice(name: String): Pair<String, NoticeSeverity> = when (this) {
    is ConnectionTestResult.Ok -> "$name answered. ${if (modelCount == 1) "1 model" else "$modelCount models"}." to NoticeSeverity.Info
    is ConnectionTestResult.Failed -> UserError.from(message, host = name).title to NoticeSeverity.Error
}

@Composable
fun ConnectionsPage() {
    val vm: ConnectionsViewModel = koinViewModel()
    val labelsVm: LabelsViewModel = koinViewModel()
    val nav = navigator()
    val items by vm.items.collectAsStateWithLifecycle()
    val labels by labelsVm.labels.collectAsStateWithLifecycle()
    val editor = rememberLabelEditor(labelsVm)
    var notice by remember { mutableStateOf<Pair<String, NoticeSeverity>?>(null) }
    val connect = rememberConnectAction()

    val all = items.valueOrNull.orEmpty()
    val browse = rememberBrowseState()
    val spec = remember(labels) { connectionSpec(labels) }
    val result = rememberBrowseResult(all, spec, browse)
    val selection = rememberSelectionState()
    val runner = rememberConnectionRunner(vm, editor, { labels }, onNotice = { notice = it }, onRemoved = { selection.exit() })
    val header = collectionHeader(
        selection, runner, result.items, key = { it.id },
        normal = collectionBar(
            title = "Connections",
            browse = browse,
            placeholder = "Search connections",
            facets = result.facets,
            countLabel = ::connectionCount,
            select = selectHeaderAction(selection, enabled = all.size > 1),
        ),
    )

    // The page's actions as tiles above the list: every one visible, none behind a menu.
    val pageActions = listOfNotNull(
        connect.entry,
        tagsEntry(nav),
        AppMenuEntry(key = "refresh", title = "Refresh", leadingIconRes = Res.drawable.ic_lc_rotate_cw, onClick = vm::refreshAll)
            .takeIf { all.isNotEmpty() },
    )

    PageScaffold(
        title = header.title ?: "Connections",
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        scroll = ScrollOwner.Content,
    ) { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            notice?.let { (text, severity) ->
                AutoDismissNotice(text, onDismiss = { notice = null }, severity = severity, modifier = Modifier.padding(horizontal = AppMenuGroupInset, vertical = 4.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item("page-actions") {
                    WhileBrowsing(browse, selection) { AppMenu(items = pageActions, layout = AppMenuLayout.actions()) }
                }
                when {
                    items is UiState.Loading -> item("loading") { ConnectionSkeleton() }
                    all.isEmpty() -> item("empty") {
                        Placeholder(
                            modifier = Modifier.fillMaxWidth(),
                            iconRes = Res.drawable.ic_lc_plug,
                            title = "Nothing connected",
                            subtitle = "Connect an account to use its models. Add as many as you like.",
                            actions = listOf(connect.placeholderAction),
                        )
                    }
                    result.isEmpty -> item("none") { BrowseNoMatches(browse, result) }
                    else -> result.sections.forEach { section ->
                        appMenuSection(section.items, key = { it.id }, title = section.title) { item ->
                            ConnectionRow(item, selection, runner, onOpen = { nav.navigate(ModelRoute.Connection(item.id)) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * **The** Connect action: one tile, one header button, one empty-state button, one sheet. Every surface that lets the user add a
 * connection (the Connections page, an add-model page) takes it from here, so they look and behave alike.
 * [open] shows the [ConnectSheet]; picking a service opens its connect form in the host's flow.
 */
@Stable
internal class ConnectAction(val open: () -> Unit) {
    val entry = AppMenuEntry(key = "connect", title = "Connect", leadingIconRes = Res.drawable.ic_lc_plus, onClick = open)
    val placeholderAction = PlaceholderAction(label = "Connect", iconRes = Res.drawable.ic_lc_plus, onClick = open)
    val headerAction = HeaderAction(Res.drawable.ic_lc_plus, "Connect", onClick = open)
}

/** A [ConnectAction] for this page, hosting its sheet while open. */
@Composable
internal fun rememberConnectAction(): ConnectAction {
    val vm: ConnectionsViewModel = koinViewModel()
    val nav = navigator()
    var open by rememberSaveable { mutableStateOf(false) }
    if (open) ConnectSheet(vm.services, onPick = { nav.navigate(ModelRoute.Connect(it.id)) }, onDismiss = { open = false })
    return remember { ConnectAction(open = { open = true }) }
}

/**
 * The Connect sheet: every service, once — the deduplicated catalogue of what can be connected, whichever
 * modality the user came for — searchable, each row saying what it serves. The ONE place a connection starts
 * from this page; the page itself lists only what is connected.
 */
@Composable
private fun ConnectSheet(services: List<ServiceDescriptor>, onPick: (ServiceDescriptor) -> Unit, onDismiss: () -> Unit) {
    val state = rememberBrowseState()
    val result = rememberBrowseResult(services, ServiceSpec, state)
    val header = collectionBar(
        title = "Connect",
        browse = state,
        placeholder = "Search services",
        facets = result.facets,
        countLabel = { if (it == 1) "1 service" else "$it services" },
    )
    AppDialog(
        onDismiss = onDismiss,
        title = header.title,
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
        size = AppDialogSize.Expandable,
    ) { controller ->
        if (result.isEmpty) {
            BrowseNoMatches(state, result)
        } else {
            AppMenu(
                items = result.items.map { service ->
                    AppMenuEntry(
                        key = service.id,
                        title = service.name,
                        subtitle = serviceSubtitle(service),
                        leadingIconRes = Res.drawable.ic_lc_plug,
                        onClick = { controller.close { onPick(service) } },
                    )
                },
            )
        }
    }
}

private val ServiceSpec: BrowseSpec<ServiceDescriptor> by lazy { serviceSpec() }

private fun serviceSpec() = BrowseSpec<ServiceDescriptor>(
    key = { it.id },
    text = { listOfNotNull(it.name, it.blurb) + it.modalities.map(::modalityLabel) },
    facets = listOf(
        Facet(
            id = "serves",
            label = "Serves",
            valuesOf = { s -> s.modalities.map(::modalityLabel) },
            options = MODALITY_ORDER.map(::modalityLabel),
        ),
    ),
)

/** What a service serves, then what it is: "Chat · Speech — Hundreds of models behind one key." */
private fun serviceSubtitle(service: ServiceDescriptor): String =
    service.modalities.sortedBy { MODALITY_ORDER.indexOf(it) }.joinToString(" · ") { modalityLabel(it) } +
        (service.blurb?.let { ". $it" } ?: "")

private fun connectionCount(n: Int): String = if (n == 1) "1 connection" else "$n connections"

private val MODALITY_ORDER = listOf(Modality.Chat, Modality.Asr, Modality.Tts, Modality.Image)

private fun modalityLabel(modality: Modality): String = when (modality) {
    Modality.Chat -> "Chat"
    Modality.Asr -> "Dictation"
    Modality.Tts -> "Speech"
    Modality.Image -> "Image"
    else -> modality.value
}

@Composable
private fun ConnectionSkeleton() {
    val shimmer = rememberSkeletonShimmer()
    Column { repeat(3) { SkeletonListRow(shimmer, index = it, leading = SkeletonLeading.Glyph) } }
}

/** One connection's line: name, then service · model count · status, with its tags. */
private fun ConnectionItem.summary(): String = listOfNotNull(
    service?.name,
    when (status) {
        ConnectionStatus.Ready -> if (modelCount == 1) "1 model" else "$modelCount models"
        // The kind of failure in a word ("Unreachable", "Key rejected"); the full story is on its page.
        ConnectionStatus.Failed -> error?.kind?.label ?: status.label
        else -> status.label
    },
    tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
).joinToString(" · ")

@Composable
private fun ConnectionRow(
    item: ConnectionItem,
    selection: SelectionState,
    runner: ActionRunner<ConnectionItem>,
    onOpen: () -> Unit,
) {
    val selecting = selection.active
    AppListItem(
        headline = item.name,
        supportingText = item.summary(),
        leadingIconRes = item.kind.iconRes(),
        trailing = when {
            selecting -> { { Checkbox(checked = item.id in selection, onCheckedChange = null) } }
            item.status == ConnectionStatus.Refreshing -> { { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
            else -> null
        },
        selected = selecting && item.id in selection,
        onClick = { if (selecting) selection.toggle(item.id) else onOpen() },
        contextActions = if (selecting) null else runner.menuFor(item),
        contextHeader = AppMenuSheetHeader(item.name),
    )
}

// ---------------------------------------------------------------------------------------------------------
// One connection.
// ---------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionPage(id: String) {
    val vm: ConnectionsViewModel = koinViewModel()
    val labelsVm: LabelsViewModel = koinViewModel()
    val nav = navigator()
    val items by vm.items.collectAsStateWithLifecycle()
    val labels by labelsVm.labels.collectAsStateWithLifecycle()
    val editor = rememberLabelEditor(labelsVm)
    var notice by remember { mutableStateOf<Pair<String, NoticeSeverity>?>(null) }
    val item = items.valueOrNull?.firstOrNull { it.id == id }

    val runner = rememberConnectionRunner(vm, editor, { labels }, onNotice = { notice = it }, onRemoved = { nav.goBack() })

    // Its models, grouped by what they do — the same rows, actions and search as the library.
    val modelsVm: ModelsViewModel = koinViewModel()
    val modelState by modelsVm.uiState.collectAsStateWithLifecycle()
    val sources by modelsVm.sources.collectAsStateWithLifecycle()
    val models = remember(modelState, labels, sources, id) {
        buildLibrary(modelState, labels, activeChatId = null, modelsVm::infoOf).filter { it.provider.value == id }
    }
    val modelSpec = remember(labels) { librarySpec(labels) { item?.name ?: it } }
    val browse = rememberBrowseState()
    val modelResult = rememberBrowseResult(models, modelSpec, browse)
    val modelSelection = rememberSelectionState()
    val sheets = rememberModelSheets()
    val modelRunner = rememberActionRunner(
        libraryActions(modelsVm, editor, { labels }, modelSelection, onOpenConnection = {}, onSampler = sheets::openSampler),
    )

    // The header: search and filter over its models, Pin and Select. The connection's actions are tiles in the body.
    val header = collectionHeader(
        modelSelection, modelRunner, modelResult.items, key = { it.id },
        normal = collectionBar(
            title = item?.name ?: "Connection",
            browse = browse,
            placeholder = "Search ${item?.name ?: "models"}",
            facets = modelResult.facets,
            countLabel = ::modelCount,
            actions = item?.let {
                val pinned = labels[LabelSubject.connection(it.id)].pinned
                listOf(pinHeaderAction(pinned) { labelsVm.setPinned(listOf(LabelSubject.connection(it.id)), !pinned) })
            }.orEmpty(),
            select = selectHeaderAction(modelSelection, enabled = models.size > 1),
            searchable = models.isNotEmpty(),
        ),
    )
    PageScaffold(
        title = header.title ?: item?.name ?: "Connection",
        scroll = ScrollOwner.Content,
        subtitle = item?.takeIf { it.name != it.connection.label }?.connection?.label,
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        titleContent = header.titleContent,
    ) { contentModifier ->
        if (item == null) {
            if (items !is UiState.Loading) Placeholder(modifier = contentModifier.fillMaxWidth(), title = "Connection removed")
            return@PageScaffold
        }
        LazyColumn(contentModifier.fillMaxSize()) {
            // Every action on this connection as tiles — the same list its long-press sheet shows, minus Pin
            // (it is in the header).
            item("actions") { WhileBrowsing(browse, modelSelection) { ActionRail(runner, item, except = setOf("pin")) } }
            item("summary") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = AppMenuGroupInset + 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        listOfNotNull(item.service?.name, item.kind.label, item.host).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val error = item.error
                    if (item.status == ConnectionStatus.Failed && error != null) {
                        ErrorLine(error, onRetry = { vm.refresh(listOf(item.id)) })
                    } else {
                        Text(
                            statusLine(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == ConnectionStatus.NoKey) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.tags.forEach { tag ->
                            AidePill(
                                onClick = { editor.editTags(listOf(LabelSubject.connection(item.id)), item.name) },
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            ) { Text("#$tag", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    notice?.let { (text, severity) -> AutoDismissNotice(text, onDismiss = { notice = null }, severity = severity) }
                }
            }
            when {
                models.isEmpty() -> item("no-models") {
                    Placeholder(
                        modifier = Modifier.fillMaxWidth(),
                        iconRes = Res.drawable.ic_lc_brain_circuit,
                        subtitle = if (item.status == ConnectionStatus.Refreshing) "Fetching its models." else "No models yet. Refresh to fetch them.",
                    )
                }
                modelResult.isEmpty -> item("none") { BrowseNoMatches(browse, modelResult) }
                else -> librarySections(modelResult) { model ->
                    LibraryRow(
                        item = model,
                        selection = modelSelection,
                        runner = modelRunner,
                        loadingModelId = modelState.loadingModelId,
                        onOpen = sheets::open,
                    )
                }
            }
        }
    }
    ModelSheetHost(sheets, models, modelRunner, editor, modelsVm)
}

private fun statusLine(item: ConnectionItem): String = when (item.status) {
    ConnectionStatus.Ready -> item.fetchedAt?.let { "Updated ${relativeTimeLabel(it)}" } ?: "Ready"
    ConnectionStatus.Refreshing -> "Refreshing models"
    ConnectionStatus.Failed -> item.error?.title ?: "Couldn't refresh"
    ConnectionStatus.NoKey -> "Add an API key to use it."
    ConnectionStatus.Waiting -> "Loading models"
}

// ---------------------------------------------------------------------------------------------------------
// Connect / edit.
// ---------------------------------------------------------------------------------------------------------

@Composable
fun ConnectPage(serviceId: String, editId: String?) {
    val vm: ConnectionsViewModel = koinViewModel()
    val nav = navigator()
    val items by vm.items.collectAsStateWithLifecycle()
    val editing = editId?.let { id -> items.valueOrNull?.firstOrNull { it.id == id } }
    // An edit follows the connection's own service (its endpoint may since match another preset).
    val service = editing?.service ?: vm.service(serviceId)
    val config by remember(editId) { editId?.let(vm::config) ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    PageScaffold(
        title = when {
            service == null -> "Connect"
            editing != null -> editing.name
            else -> "Connect ${service.name}"
        },
    ) { contentModifier ->
        if (service == null) {
            Placeholder(modifier = contentModifier.fillMaxWidth(), title = "Unknown service")
            return@PageScaffold
        }
        // An edit waits for the stored endpoint and key rather than opening on empty fields.
        if (editId != null && config == null) return@PageScaffold
        ConnectForm(
            service = service,
            initial = config?.let { ConnectFormInitial(it.baseUrl, it.apiKey) },
            notice = notice,
            onCancel = { nav.goBack() },
            onSubmit = { draft ->
                if (editId != null) {
                    vm.update(editId, draft) { nav.goBack() }
                } else {
                    vm.create(draft) { created ->
                        if (created.existing) {
                            notice = "Already connected as ${created.connection.label}."
                        } else {
                            nav.goBack()
                            nav.navigate(ModelRoute.Connection(created.connection.id))
                        }
                    }
                }
            },
            modifier = contentModifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}
