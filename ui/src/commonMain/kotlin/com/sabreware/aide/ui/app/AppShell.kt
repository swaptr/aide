package com.sabreware.aide.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sabreware.aide.core.common.startup.DeferredBootstraps
import com.sabreware.aide.core.designsystem.AppListItem
import com.sabreware.aide.core.designsystem.AppMenuAction
import com.sabreware.aide.core.designsystem.AppPullToRefreshBox
import com.sabreware.aide.core.designsystem.LabeledDivider
import com.sabreware.aide.core.designsystem.LabeledDividerSide
import com.sabreware.aide.core.designsystem.LocalModalPresentation
import com.sabreware.aide.core.designsystem.LocalWindowSizeClass
import com.sabreware.aide.core.designsystem.MarqueeHost
import com.sabreware.aide.core.designsystem.NavMotion
import com.sabreware.aide.core.designsystem.Placeholder
import com.sabreware.aide.core.designsystem.SkeletonListRow
import com.sabreware.aide.core.designsystem.isCompact
import com.sabreware.aide.core.designsystem.isExpanded
import com.sabreware.aide.core.designsystem.rememberAppWindowSizeClass
import com.sabreware.aide.core.designsystem.rememberModalPresentation
import com.sabreware.aide.core.designsystem.rememberSkeletonShimmer
import com.sabreware.aide.core.designsystem.resources.*
import com.sabreware.aide.core.designsystem.state.PaneFailed
import com.sabreware.aide.core.designsystem.state.UiState
import com.sabreware.aide.core.designsystem.state.isLoading
import com.sabreware.aide.core.designsystem.state.valueOrNull
import com.sabreware.aide.core.designsystem.theme.DrawerListItemStyle
import com.sabreware.aide.core.designsystem.theme.LocalAppListItemStyle
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.designsystem.feature.FeatureRegistry
import com.sabreware.aide.core.domain.navigation.DeepLinkDest
import com.sabreware.aide.core.designsystem.navigation.LocalNavigator
import com.sabreware.aide.core.designsystem.RenameChatDialog
import com.sabreware.aide.core.designsystem.browse.rememberActionRunner
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.ui.chats.chatActions
import com.sabreware.aide.ui.chats.chatName
import com.sabreware.aide.ui.chats.chatSheetHeader
import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.labels.rememberLabelEditor
import com.sabreware.aide.ui.models.ModelRoute
import com.sabreware.aide.ui.navigation.AppNavigator
import com.sabreware.aide.ui.navigation.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// Sidebar width on medium/expanded windows. Narrow enough to leave a usable content pane at ~600dp.
private val PermanentDrawerWidth = 300.dp

// Sidebar open/close duration. Short: it's a pointer-driven panel, not a gesture-tracked drawer.
private const val SidebarMotionMs = 220

@Composable
fun AppShell(
    viewModel: AppViewModel = koinViewModel(),
    deepLinkDestination: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    // Deferred shared bootstraps (MCP reconnect, connector-directory refresh): fired AFTER the first frame
    // so startup only mounts the shell — their network/DataStore work must never race it. Each start() is
    // idempotent across activity recreation.
    val koin = getKoin()
    LaunchedEffect(Unit) { koin.get<DeferredBootstraps>().startAll() }
    // Compute the window size class and the modal presentation once at the root and provide them to the
    // whole tree — every responsive branch (this shell, content max-width) reads LocalWindowSizeClass, and
    // AppDialog's sheet↔dialog switch reads LocalModalPresentation (the host's ModalPolicy, resolved against
    // the live window).
    CompositionLocalProvider(
        LocalWindowSizeClass provides rememberAppWindowSizeClass(),
        LocalModalPresentation provides rememberModalPresentation(),
    ) {
        // Where cut-off names may walk: the shell's scrolls tell every MarqueeText when the page has settled.
        MarqueeHost {
            AppNav(
                viewModel = viewModel,
                deepLinkDestination = deepLinkDestination,
                onDeepLinkConsumed = onDeepLinkConsumed,
            )
        }
    }
}

@Composable
private fun AppNav(
    viewModel: AppViewModel,
    deepLinkDestination: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    // The features this application installed (see `featureModules`): the shell hosts their destinations and
    // offers them each deep-link first, without knowing which platform contributed which.
    val features = koinInject<FeatureRegistry>()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val windowSize = LocalWindowSizeClass.current

    val currentChatRoute = backStackEntry?.toRouteOrNull<Route.Chat>()
    val currentChatId: String? = currentChatRoute?.chatId

    // App-level chats state: collected once here for the session, so the drawer sheet (which Material
    // keeps composed offscreen) always holds current data and opening it never queries or mounts anything.
    val chatsState by viewModel.chats.collectAsStateWithLifecycle()

    // Id, saveable: a rename dialog opened from the drawer survives rotation; the chat is looked up live.
    var pendingRenameId by rememberSaveable { mutableStateOf<String?>(null) }
    // The drawer's chat actions: the same list the Chats page and the chat header use, hosted here (not in
    // the drawer body) so their sheets outlive a switch between the modal drawer and the sidebar.
    val labelsViewModel: LabelsViewModel = koinViewModel()
    val labels by labelsViewModel.labels.collectAsStateWithLifecycle()
    val labelEditor = rememberLabelEditor(labelsViewModel)
    // Archiving or deleting the OPEN chat moves to a replacement, replacing the whole stack.
    val openReplacement: (String) -> Unit = { replacementId ->
        nav.navigate(Route.Chat(replacementId)) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    val chatRunner = rememberActionRunner(
        chatActions(
            editor = labelEditor,
            labels = { labels },
            onRename = { pendingRenameId = it.id },
            setPinned = { targets, pinned -> targets.forEach { viewModel.setStarred(it.id, pinned) } },
            setArchived = { targets, archived ->
                targets.forEach { chat ->
                    if (archived && chat.id == currentChatId) viewModel.setArchived(chat.id, true, openReplacement)
                    else viewModel.setArchived(chat.id, archived)
                }
            },
            onDelete = { targets ->
                targets.forEach { chat ->
                    val wasCurrent = chat.id == currentChatId
                    viewModel.deleteChat(chat.id) { replacementId -> if (wasCurrent) openReplacement(replacementId) }
                }
                labelsViewModel.forget(targets.map { LabelSubject.chat(it.id) })
            },
        ),
    )
    // Wide-window sidebar visibility (the compact overlay uses [drawerState] instead). Persisted, so it
    // survives a restart — `rememberSaveable` never did on desktop.
    val sidebarOpenState = viewModel.sidebarOpen.collectAsStateWithLifecycle()
    val sidebarOpen by sidebarOpenState

    // The drawer body — the same content whether it's a modal overlay (compact) or a pinned sidebar (wide).
    // closeDrawer is a no-op for the pinned sidebar (it never dismisses); the modal closes on navigation.
    val drawerBody: @Composable (closeDrawer: () -> Unit) -> Unit = { closeDrawer ->
        DrawerBody(
            nav = nav,
            viewModel = viewModel,
            chatsState = chatsState,
            currentChatId = currentChatId,
            closeDrawer = closeDrawer,
            chatMenu = chatRunner::menuFor,
        )
    }

    // Width default: only a genuinely wide window has room for sidebar AND content. Applied when the window
    // CROSSES a breakpoint, never on first composition — else it would overwrite the restored choice.
    var lastBreakpoint by remember { mutableStateOf(windowSize) }
    LaunchedEffect(windowSize) {
        if (windowSize != lastBreakpoint) {
            lastBreakpoint = windowSize
            viewModel.setSidebarOpen(windowSize.isExpanded)
        }
        drawerState.close()
    }

    // One toggle for both presentations: the compact modal overlay is driven by Material's DrawerState, the
    // wide displacing sidebar by a plain flag (a DrawerState detached from a drawer composable has no
    // anchors, so animating it there silently does nothing).
    val toggleDrawer: () -> Unit = {
        if (windowSize.isCompact) {
            scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
        } else {
            viewModel.setSidebarOpen(!sidebarOpen)
        }
    }

    // The app back stack, wrapped so `navigator()` resolves to it for any screen; a dialog re-provides its
    // own navigator for its subtree (nearest wins).
    val appNavigator = remember(nav) { AppNavigator(nav) }
    // Read at the destination, not when the graph is built: NavHost rebuilds its graph whenever its builder
    // lambda changes, so a captured Boolean either went stale or re-created the graph on every drawer settle —
    // which lands mid-transition, since a drawer tap navigates while the drawer is still closing.
    val isDrawerOpen: () -> Boolean = remember(windowSize, drawerState, sidebarOpenState) {
        { if (windowSize.isCompact) drawerState.isOpen else sidebarOpenState.value }
    }
    // The NavHost is MOVABLE content: the compact drawer and the wide Row are different parents, and a plain
    // lambda called from either composes a NEW NavHost at a new position on every breakpoint crossing. That
    // loses its in-process state, and — since `rememberSaveable` keys by composition position — makes a
    // recreated activity (rotation) restore the saveable state saved at THAT layout's last visit: the open
    // sheet, its page, the scroll and the drafts of a screen came back "one or two steps back". Movable
    // content carries its state across parents and restores by its own key, whichever layout it lands in.
    // Remembered once, so what it captures is read through [rememberUpdatedState].
    val latestToggleDrawer by rememberUpdatedState(toggleDrawer)
    val latestIsDrawerOpen by rememberUpdatedState(isDrawerOpen)
    val content: @Composable () -> Unit = remember(nav, appNavigator, features, scope) {
        movableContentOf {
            NavPane(
                nav = nav,
                appNavigator = appNavigator,
                features = features,
                scope = scope,
                onToggleDrawer = { latestToggleDrawer() },
                isDrawerOpen = { latestIsDrawerOpen() },
            )
        }
    }

    val drawerLayout: @Composable () -> Unit = {
        if (windowSize.isCompact) {
            // currentValue alone flips too late mid-gesture; targetValue alone too early on commit.
            val gesturesEnabled by remember(drawerState) {
                derivedStateOf {
                    drawerState.currentValue != DrawerValue.Closed ||
                        drawerState.targetValue != DrawerValue.Closed
                }
            }
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = gesturesEnabled,
                drawerContent = {
                    // Always composed (Material parks it offscreen): the open slide is pure translation,
                    // never composition — the app-level chats state means it's already up to date.
                    ModalDrawerSheet(windowInsets = WindowInsets(0, 0, 0, 0)) {
                        drawerBody { scope.launch { drawerState.close() } }
                    }
                },
                content = content,
            )
        } else {
            // Wide: the sidebar DISPLACES the content — the content pane genuinely reflows into the remaining
            // width (a Row), it is not translated sideways. Material's DismissibleNavigationDrawer offsets the
            // content instead, which keeps it full-window-width and pushes its right edge off-screen (content
            // ends up visibly off-centre and clipped). No icon-rail middle state: it's open or gone.
            //
            // RowScope.AnimatedVisibility + expandHorizontally is the idiomatic way to do this: it measures the
            // sheet ONCE at its full width and animates the CLIP, so the contents never reflow mid-animation.
            // (Animating a wrapper's width instead — animateDpAsState + Modifier.width — passes the shrinking
            // width down as a max constraint, so every row inside squeezes as it opens. That looked awful.)
            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = sidebarOpen,
                    // Anchor the sheet's trailing edge so it wipes in from the leading edge like a drawer,
                    // rather than growing out of the corner.
                    enter = expandHorizontally(
                        animationSpec = tween(SidebarMotionMs, easing = FastOutSlowInEasing),
                        expandFrom = Alignment.End,
                    ),
                    exit = shrinkHorizontally(
                        animationSpec = tween(SidebarMotionMs, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.End,
                    ),
                ) {
                    PermanentDrawerSheet(
                        modifier = Modifier.width(PermanentDrawerWidth).fillMaxHeight(),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        drawerBody { }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }

    drawerLayout()

    LaunchedEffect(deepLinkDestination) {
        val dest = deepLinkDestination ?: return@LaunchedEffect
        // Feature-owned deep-links first (e.g. Tasks, on Android only) — the feature that ships the
        // destination resolves it; on platforms without that feature nothing claims it. Then the shared
        // shell destinations.
        val handled = features.features.any { it.handleDeepLink(dest, nav) }
        if (!handled) when (dest) {
            DeepLinkDest.DEST_CUSTOM_INSTRUCTION ->
                nav.navigate(Route.CustomInstruction) { launchSingleTop = true }
            DeepLinkDest.DEST_MODELS ->
                nav.navigate(ModelRoute.Home) { launchSingleTop = true }
        }
        onDeepLinkConsumed()
    }

    pendingRenameId?.let { id -> chatsState.valueOrNull?.firstOrNull { it.id == id } }?.let { chat ->
        RenameChatDialog(
            initialTitle = chat.title,
            onConfirm = { newTitle -> viewModel.renameChat(chat.id, newTitle) },
            onDismiss = { pendingRenameId = null },
        )
    }
}

/** The app's one NavHost — hosted by [AppNav] as movable content, so a layout switch never re-creates it. */
@Composable
private fun NavPane(
    nav: NavHostController,
    appNavigator: AppNavigator,
    features: FeatureRegistry,
    scope: CoroutineScope,
    onToggleDrawer: () -> Unit,
    isDrawerOpen: () -> Boolean,
) {
    CompositionLocalProvider(LocalNavigator provides appNavigator) {
        NavHost(
            navController = nav,
            startDestination = Route.Chat(""),
            // Transitions stay inside the content pane. NavHost's AnimatedContent clips only when given a
            // SizeTransform, so a sliding page otherwise draws past its bounds — over the pinned sidebar,
            // which sits before it in the Row. AppDialog pages get the same bound from the sheet surface's
            // clip, so both hosts slide within their own frame.
            modifier = Modifier.clipToBounds(),
            // The one app-wide horizontal nav slide — shared with AppDialog page swaps.
            enterTransition = { NavMotion.enter(forward = true) },
            exitTransition = { NavMotion.exit(forward = true) },
            popEnterTransition = { NavMotion.enter(forward = false) },
            popExitTransition = { NavMotion.exit(forward = false) },
        ) {
            appDestinations(
                nav = nav,
                features = features,
                scope = scope,
                onToggleDrawer = onToggleDrawer,
                isDrawerOpen = isDrawerOpen,
            )
        }
    }
}

/**
 * The drawer contents — header, fixed nav items, then the pinned/recent chat lists. Rendered inside a
 * [ModalDrawerSheet] (compact overlay) or a [PermanentDrawerSheet] (pinned sidebar). [closeDrawer] dismisses
 * the modal overlay on navigation (a no-op for the pinned sidebar). [chatsState] is the app-level chats
 * state collected in AppNav — this body renders whatever it's handed, no data wiring of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerBody(
    nav: NavHostController,
    viewModel: AppViewModel,
    chatsState: UiState<List<Chat>>,
    currentChatId: String?,
    closeDrawer: () -> Unit,
    chatMenu: (Chat) -> List<AppMenuAction>,
) {
    val chats = chatsState.valueOrNull.orEmpty()
    val failed = chatsState as? UiState.Failed
    // Derived once per chats emission, not per recomposition — this body stays composed offscreen in the
    // modal drawer, so it recomposes with the shell and would otherwise re-filter the list every time.
    val pinned = remember(chats) { chats.filter { it.isStarred && !it.isArchived } }
    val recents = remember(chats) { chats.filter { !it.isStarred && !it.isArchived } }
    val shimmer = if (chatsState.isLoading) rememberSkeletonShimmer() else null

    fun openChat(chat: Chat) {
        closeDrawer()
        if (chat.id != currentChatId) {
            nav.navigate(Route.Chat(chat.id)) {
                popUpTo<Route.Chat> { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Chat rows align under the nav-item icons via the wider drawer inset — provided implicitly so no row
    // passes a padding arg.
    CompositionLocalProvider(LocalAppListItemStyle provides DrawerListItemStyle) {
        Column(modifier = Modifier.fillMaxSize()) {
            DrawerHeader(modifier = Modifier.statusBarsPadding())

            val refreshing by viewModel.chatsRefreshing.collectAsStateWithLifecycle()
            AppPullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = viewModel::refreshChats,
                modifier = Modifier.weight(1f),
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars
                        .asPaddingValues()
                        .calculateBottomPadding(),
                ),
            ) {
                item { Spacer(Modifier.height(4.dp)) }

                item { DrawerNavItems(nav, closeDrawer) }

                item { Spacer(Modifier.height(16.dp)) }

                if (pinned.isNotEmpty()) {
                    item { DrawerSectionHeader(title = "Pinned") }
                    items(pinned, key = { it.id }) { chat ->
                        AppListItem(
                            headline = chatName(chat),
                            selected = chat.id == currentChatId,
                            onClick = { openChat(chat) },
                            contextActions = chatMenu(chat),
                            contextHeader = chatSheetHeader(chat),
                        )
                    }
                }

                item { DrawerSectionHeader(title = "Recents") }
                when {
                    shimmer != null -> items(3, key = { "chat-skeleton-$it" }) { index ->
                        SkeletonListRow(shimmer, index = index)
                    }
                    failed != null -> item(key = "recents-failed") {
                        PaneFailed(failed.message)
                    }
                    recents.isEmpty() -> item(key = "recents-empty") {
                        Placeholder(
                            modifier = Modifier.fillMaxWidth(),
                            subtitle = "No recent chats yet.",
                        )
                    }
                    else -> items(recents, key = { it.id }) { chat ->
                        AppListItem(
                            headline = chatName(chat),
                            selected = chat.id == currentChatId,
                            onClick = { openChat(chat) },
                            contextActions = chatMenu(chat),
                            contextHeader = chatSheetHeader(chat),
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * A drawer section: an optional titled header plus content, with its own [contentPadding]. Lets the
 * menu items, Pinned, and Recents each style independently (e.g. inset padding vs section titles).
 */
@Composable
private fun DrawerSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            DrawerSectionHeader(title = title, modifier = Modifier.padding(contentPadding))
        }
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
private fun DrawerSectionHeader(title: String, modifier: Modifier = Modifier) {
    // Shared labeled divider; the fixed leading line offsets the title past the row content inset so
    // headers don't line up with the chat titles below them.
    LabeledDivider(
        text = title,
        modifier = modifier,
        side = LabeledDividerSide.Start,
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        stubWidth = DrawerListItemStyle.contentInset - 8.dp,
    )
}

@Composable
private fun DrawerHeader(modifier: Modifier = Modifier) {
    Text(
        text = "Aide",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** The fixed nav entries. Material [NavigationDrawerItem]s — the standard for drawer navigation.
 *  [closeDrawer] dismisses the modal overlay after navigating (a no-op for the pinned sidebar). */
@Composable
private fun DrawerNavItems(
    nav: NavHostController,
    closeDrawer: () -> Unit,
) {
    DrawerSection(contentPadding = PaddingValues(horizontal = 4.dp)) {
        NavigationDrawerItem(
            label = { Text("New chat") },
            selected = false,
            icon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_lc_message_circle_plus),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            // Accent (primary) tint marks the primary "New chat" action.
            colors = NavigationDrawerItemDefaults.colors(
                unselectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.primary,
            ),
            onClick = {
                closeDrawer()
                // Pop current Chat; else launchSingleTop collapses Chat("")
                // onto existing Chat(id) and stale chat stays visible.
                nav.navigate(Route.Chat("")) {
                    popUpTo<Route.Chat> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
        NavigationDrawerItem(
            label = { Text("Chats") },
            selected = false,
            icon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_lc_messages_square),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            onClick = {
                closeDrawer()
                nav.navigate(Route.Chats) { launchSingleTop = true }
            },
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            icon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_lc_settings),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            onClick = {
                closeDrawer()
                nav.navigate(Route.Settings) { launchSingleTop = true }
            },
        )
    }
}

private inline fun <reified T : Any> NavBackStackEntry.toRouteOrNull(): T? =
    runCatching { this.toRoute<T>() }.getOrNull()
