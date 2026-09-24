package com.sabreware.aide.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import org.jetbrains.compose.resources.painterResource
import com.sabreware.aide.core.designsystem.AppPullToRefreshBox
import com.sabreware.aide.core.designsystem.RenameChatDialog
import com.sabreware.aide.core.designsystem.AppScaffold
import com.sabreware.aide.core.designsystem.ConstrainedContent
import com.sabreware.aide.core.designsystem.HeaderAction
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.state.StatePane
import com.sabreware.aide.core.designsystem.state.valueOrNull
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.theme.DrawerListItemStyle
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle
import com.sabreware.aide.core.domain.chat.Chat
import androidx.compose.runtime.LaunchedEffect
import com.sabreware.aide.core.designsystem.browse.selectHeaderAction
import com.sabreware.aide.core.designsystem.browse.BrowseNoMatches
import com.sabreware.aide.core.designsystem.browse.collectionBar
import com.sabreware.aide.core.designsystem.browse.collectionHeader
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.designsystem.browse.rememberBrowseResult
import com.sabreware.aide.core.designsystem.browse.rememberBrowseState
import com.sabreware.aide.core.designsystem.browse.rememberSelectionState
import com.sabreware.aide.core.domain.browse.BrowseSpec
import com.sabreware.aide.core.domain.browse.Facet
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.label.tagFacet
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.rememberLabelEditor
import org.koin.compose.viewmodel.koinViewModel

/**
 * The chat spec for the shared collection kit: searchable by title and tags; a Show view (All, Pinned,
 * Archived — exclusive, defaulting to All, which hides the archived), a Started-from view (where the chat began)
 * and the user's tags. The predicates stay in [ChatFilter] / [SurfaceFilter], the single source of truth.
 */
private fun chatSpec(labels: Labels): BrowseSpec<Chat> = BrowseSpec(
    key = { it.id },
    text = { chat -> listOf(chat.title) + labels[LabelSubject.chat(chat.id)].tags },
    facets = listOf(
        Facet(
            id = SHOW,
            label = "Show",
            valuesOf = { chat -> ChatFilter.entries.filter { it.matches(chat) }.map { it.name } },
            optionLabel = { ChatFilter.valueOf(it).label },
            options = ChatFilter.entries.map { it.name },
            hideEmpty = false,
            exclusive = true,
            default = ChatFilter.All.name,
        ),
        Facet(
            id = "surface",
            label = "Started from",
            valuesOf = { chat -> SurfaceFilter.entries.filter { it != SurfaceFilter.All && it.matches(chat) }.map { it.name } },
            optionLabel = { SurfaceFilter.valueOf(it).label },
            options = SurfaceFilter.entries.filter { it != SurfaceFilter.All }.map { it.name },
            exclusive = true,
        ),
        labels.tagFacet { LabelSubject.chat(it.id) },
    ),
)

private const val SHOW = "show"

/**
 * Full-screen list of saved chats, on the shared collection kit: Search, Filter (Show, Started from, Tag) and
 * Select in the header ([collectionBar]), the chosen filters as pills, a multi-select mode with bulk pin /
 * archive / tag / delete, the same [chatActions] on a row's long-press sheet, and New chat as the FAB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onOpenDrawer: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    currentChatId: String?,
    viewModel: ChatsViewModel = koinViewModel(),
    labelsViewModel: LabelsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val labels by labelsViewModel.labels.collectAsStateWithLifecycle()
    val chats = uiState.chats
    val editor = rememberLabelEditor(labelsViewModel)

    var pendingRename by remember { mutableStateOf<Chat?>(null) }

    val browse = rememberBrowseState()
    val spec = remember(labels) { chatSpec(labels) }
    val result = rememberBrowseResult(chats.valueOrNull.orEmpty(), spec, browse)
    val visible = result.items
    val selection = rememberSelectionState()
    // A selection never carries across views: what is selected must be what is on screen.
    LaunchedEffect(browse.query.filters) { selection.retain(visible.map(Chat::id)) }
    val showing = browse.query.filters[SHOW]?.firstOrNull()?.let(ChatFilter::valueOf) ?: ChatFilter.All

    val actions = chatActions(
        editor = editor,
        labels = { labels },
        onRename = { pendingRename = it },
        setPinned = { targets, pinned -> viewModel.setStarred(targets.map(Chat::id), pinned) },
        setArchived = { targets, archived ->
            val open = targets.singleOrNull()?.takeIf { archived && it.id == currentChatId }
            // Archiving the OPEN chat moves the user to a replacement; anything else is a plain write.
            if (open != null) viewModel.setArchived(open.id, true) { replacement -> onOpenChat(replacement) }
            else viewModel.setArchived(targets.map(Chat::id), archived)
            selection.exit()
        },
        onDelete = { targets ->
            viewModel.deleteChats(targets.map(Chat::id)) { deleted, replacement ->
                if (currentChatId != null && currentChatId in deleted) onOpenChat(replacement)
            }
            labelsViewModel.forget(targets.map { LabelSubject.chat(it.id) })
            selection.exit()
        },
        onSelect = { selection.enter(it.id) },
    )
    val runner = rememberActionRunner(actions)
    val header = collectionHeader(
        selection, runner, visible, key = Chat::id,
        // Search, Filter and Select in the header; the filter stays even on an empty view so you can switch back.
        normal = collectionBar(
            title = "Chats",
            browse = browse,
            placeholder = "Search chats",
            facets = result.facets,
            countLabel = { if (it == 1) "1 chat" else "$it chats" },
            leadingAction = HeaderAction.drawer(onOpenDrawer),
            select = selectHeaderAction(selection, enabled = visible.size > 1),
        ),
    )

    AppScaffold(
        title = header.title.orEmpty(),
        titleContent = header.titleContent,
        leadingAction = header.leadingAction,
        trailingActions = header.trailingActions,
        floatingActionButton = {
            if (!selection.active && !browse.searching) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    icon = { Icon(painterResource(Res.drawable.ic_lc_plus), contentDescription = null) },
                    text = { Text("New chat") },
                )
            }
        },
    ) { scaffoldModifier ->
      ConstrainedContent(scaffoldModifier) { contentModifier ->
        // Provider wraps the Column so list rows pick up the same 20.dp inset token the drawer uses,
        // matching the screen edge; the list / placeholder stay direct ColumnScope children so
        // weight(1f) resolves.
        CompositionLocalProvider(
            LocalAppListItemStyle provides DrawerListItemStyle,
        ) {
            Column(modifier = contentModifier) {
                val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
                AppPullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = viewModel::refreshChats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                StatePane(
                    state = chats,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        // A skeleton, not the empty state. Seeding an empty list painted "No chats yet"
                        // before the first database emission — the drawer over the same data already did
                        // this right.
                        val shimmer = rememberSkeletonShimmer()
                        Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
                            repeat(SKELETON_ROWS) { index -> SkeletonListRow(shimmer, index = index) }
                        }
                    },
                ) { _ ->
                    if (visible.isEmpty()) {
                        // Scroll host so the pull gesture works on the empty state too.
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (result.chosen.any { it.first.id != SHOW } || browse.query.tokens.isNotEmpty()) {
                                BrowseNoMatches(browse, result)
                            } else {
                                Placeholder(
                                    modifier = Modifier.fillMaxWidth(),
                                    iconRes = Res.drawable.ic_lc_messages_square,
                                    title = emptyTitle(showing),
                                    subtitle = emptySubtitle(showing),
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp, bottom = FabClearance),
                        ) {
                            items(visible, key = { it.id }) { chat ->
                                ChatRow(
                                    chat = chat,
                                    tags = labels[LabelSubject.chat(chat.id)].tags,
                                    selectionMode = selection.active,
                                    selected = chat.id in selection,
                                    onClick = {
                                        if (selection.active) selection.toggle(chat.id) else onOpenChat(chat.id)
                                    },
                                    contextActions = if (selection.active) null else runner.menuFor(chat),
                                )
                            }
                        }
                    }
                }
                }
            }
        }
      }
    }

    pendingRename?.let { chat ->
        RenameChatDialog(
            initialTitle = chat.title,
            onConfirm = { newTitle -> viewModel.renameChat(chat.id, newTitle) },
            onDismiss = { pendingRename = null },
        )
    }
}

/**
 * Bottom list padding so the last row scrolls clear of the New-chat FAB: the M3 extended FAB's 56.dp
 * container + the scaffold's 16.dp FAB margin + a 24.dp gap so the row isn't kissing the button.
 */
private val FabClearance = 96.dp

/** Enough rows to fill a phone viewport — the point is that the list looks like a list while it loads. */
private const val SKELETON_ROWS = 8

private fun emptyTitle(filter: ChatFilter): String = when (filter) {
    ChatFilter.All -> "No chats yet"
    ChatFilter.Pinned -> "No pinned chats"
    ChatFilter.Archived -> "No archived chats"
}

private fun emptySubtitle(filter: ChatFilter): String = when (filter) {
    ChatFilter.All -> "Start a conversation and it'll show up here."
    ChatFilter.Pinned -> "Pin a chat to keep it close at hand."
    ChatFilter.Archived -> "Chats you archive will appear here."
}
