package com.swaptr.aide.ui.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import com.swaptr.aide.MainActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.swaptr.aide.R
import com.swaptr.aide.data.chat.ChatEntity
import com.swaptr.aide.navigation.Route
import com.swaptr.aide.ui.chat.ChatScreen
import com.swaptr.aide.ui.common.AppMenuAction
import com.swaptr.aide.ui.common.AppMenuEntry
import com.swaptr.aide.ui.common.AppMenuList
import com.swaptr.aide.ui.common.RenameChatDialog
import com.swaptr.aide.ui.models.ModelsScreen
import com.swaptr.aide.ui.custom.CustomInstructionScreen
import com.swaptr.aide.ui.settings.SettingsScreen
import com.swaptr.aide.ui.settings.tools.ToolsSettingsScreen
import com.swaptr.aide.ui.settings.websearch.WebSearchSettingsScreen
import com.swaptr.aide.ui.tasks.TaskDetailScreen
import com.swaptr.aide.ui.tasks.TaskEditScreen
import com.swaptr.aide.ui.tasks.TaskListScreen
import kotlinx.coroutines.launch

private const val NAV_ANIM_MS = 260

@Composable
fun AppShell(
    viewModel: AppViewModel = hiltViewModel(),
    deepLinkDestination: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val initialChatId by viewModel.initialChatId.collectAsStateWithLifecycle()
    val resolved = initialChatId
    if (resolved == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        AppNav(
            viewModel = viewModel,
            initialChatId = resolved,
            deepLinkDestination = deepLinkDestination,
            onDeepLinkConsumed = onDeepLinkConsumed,
        )
    }
}

@Composable
private fun AppNav(
    viewModel: AppViewModel,
    initialChatId: String,
    deepLinkDestination: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val currentChatId: String? = backStackEntry?.toRouteOrNull<Route.Chat>()?.chatId

    var pendingDelete by remember { mutableStateOf<ChatEntity?>(null) }
    var pendingRename by remember { mutableStateOf<ChatEntity?>(null) }

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
            ModalDrawerSheet(windowInsets = WindowInsets(0, 0, 0, 0)) {
                val starred = chats.filter { it.isStarred && !it.isArchived }
                val recents = chats.filter { !it.isStarred && !it.isArchived }

                fun chatEntry(chat: ChatEntity): AppMenuEntry = AppMenuEntry(
                    key = chat.id,
                    title = chat.title.ifBlank { "New chat" },
                    selected = chat.id == currentChatId,
                    onClick = {
                        scope.launch { drawerState.close() }
                        if (chat.id != currentChatId) {
                            nav.navigate(Route.Chat(chat.id)) {
                                popUpTo<Route.Chat> { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    contextActions = listOf(
                        AppMenuAction(
                            label = "Rename",
                            iconRes = R.drawable.ic_lc_pencil,
                        ) { pendingRename = chat },
                        AppMenuAction(
                            label = if (chat.isStarred) "Unstar" else "Star",
                            iconRes = R.drawable.ic_lc_star,
                        ) { viewModel.setStarred(chat.id, !chat.isStarred) },
                        AppMenuAction(
                            label = if (chat.isArchived) "Unarchive" else "Archive",
                            iconRes = R.drawable.ic_lc_folder,
                        ) {
                            if (!chat.isArchived && chat.id == currentChatId) {
                                viewModel.setArchived(chat.id, true) { replacementId ->
                                    nav.navigate(Route.Chat(replacementId)) {
                                        popUpTo(nav.graph.id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            } else {
                                viewModel.setArchived(chat.id, !chat.isArchived)
                            }
                        },
                        AppMenuAction(
                            label = "Delete",
                            iconRes = R.drawable.ic_lc_trash,
                            destructive = true,
                        ) { pendingDelete = chat },
                    ),
                )

                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DrawerHeader(modifier = Modifier.statusBarsPadding())
                    Spacer(Modifier.height(4.dp))

                    AppMenuList(
                        items = listOf(
                            AppMenuEntry(
                                title = "New chat",
                                leadingIconRes = R.drawable.ic_lc_plus,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    // Pop current Chat; else launchSingleTop collapses Chat("")
                                    // onto existing Chat(id) and stale chat stays visible.
                                    nav.navigate(Route.Chat("")) {
                                        popUpTo<Route.Chat> { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            ),
                            AppMenuEntry(
                                title = "Models",
                                leadingIconRes = R.drawable.ic_lc_database,
                                selected = currentDestination?.hasRoute(Route.Models::class) == true,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    nav.navigate(Route.Models) { launchSingleTop = true }
                                },
                            ),
                            AppMenuEntry(
                                title = "Keyboard Tasks",
                                leadingIconRes = R.drawable.ic_lc_sparkles,
                                selected = currentDestination?.hasRoute(Route.Tasks::class) == true,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    nav.navigate(Route.Tasks) { launchSingleTop = true }
                                },
                            ),
                            AppMenuEntry(
                                title = "Settings",
                                leadingIconRes = R.drawable.ic_lc_folder_plus,
                                selected = currentDestination?.hasRoute(Route.Settings::class) == true,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    nav.navigate(Route.Settings) { launchSingleTop = true }
                                },
                            ),
                        ),
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    if (starred.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Starred",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppMenuList(items = starred.map(::chatEntry))
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Recents",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    AppMenuList(
                        modifier = Modifier.padding(
                            bottom = WindowInsets.navigationBars
                                .asPaddingValues()
                                .calculateBottomPadding(),
                        ),
                        items = recents.map(::chatEntry),
                    )
                }
            }
        },
    ) {
        NavHost(
            navController = nav,
            startDestination = Route.Chat(initialChatId),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(NAV_ANIM_MS),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(NAV_ANIM_MS),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(NAV_ANIM_MS),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(NAV_ANIM_MS),
                )
            },
        ) {
            composable<Route.Chat> {
                ChatScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenModels = { nav.navigate(Route.Models) { launchSingleTop = true } },
                    onNavigateToChat = { replacementId ->
                        nav.navigate(Route.Chat(replacementId)) {
                            popUpTo(nav.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNewChat = {
                        nav.navigate(Route.Chat("")) {
                            popUpTo<Route.Chat> { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable<Route.Models> {
                ModelsScreen(onOpenDrawer = { scope.launch { drawerState.open() } })
            }
            composable<Route.Tasks> {
                TaskListScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onAddNew = { gid ->
                        nav.navigate(Route.TaskEdit(taskId = null, groupId = gid))
                    },
                    onOpenTask = { id -> nav.navigate(Route.TaskDetail(taskId = id)) },
                )
            }
            composable<Route.TaskDetail> { entry ->
                val args = entry.toRoute<Route.TaskDetail>()
                TaskDetailScreen(
                    onClose = { nav.popBackStack() },
                    onEdit = { id ->
                        nav.navigate(Route.TaskEdit(taskId = id))
                    },
                    onCloneAndEdit = { newId ->
                        nav.navigate(Route.TaskEdit(taskId = newId)) {
                            popUpTo(Route.TaskDetail(args.taskId)) { inclusive = true }
                        }
                    },
                )
            }
            composable<Route.TaskEdit> { entry ->
                val args = entry.toRoute<Route.TaskEdit>()
                TaskEditScreen(
                    taskId = args.taskId,
                    groupId = args.groupId,
                    onClose = { nav.popBackStack() },
                )
            }
            composable<Route.CustomInstruction> {
                CustomInstructionScreen(onClose = { nav.popBackStack() })
            }
            composable<Route.Settings> {
                SettingsScreen(
                    onClose = { nav.popBackStack() },
                    onOpenWebSearch = { nav.navigate(Route.WebSearchSettings) },
                    onOpenTools = { nav.navigate(Route.ToolsSettings) },
                    onOpenSpeech = { nav.navigate(Route.SpeechSettings) },
                )
            }
            composable<Route.WebSearchSettings> {
                WebSearchSettingsScreen(onClose = { nav.popBackStack() })
            }
            composable<Route.ToolsSettings> {
                ToolsSettingsScreen(onClose = { nav.popBackStack() })
            }
            composable<Route.SpeechSettings> {
                com.swaptr.aide.ui.settings.speech.SpeechSettingsScreen(
                    onClose = { nav.popBackStack() },
                )
            }
        }
    }

    LaunchedEffect(deepLinkDestination) {
        val dest = deepLinkDestination ?: return@LaunchedEffect
        when (dest) {
            MainActivity.DEST_TASKS ->
                nav.navigate(Route.Tasks) { launchSingleTop = true }
            MainActivity.DEST_TASK_EDIT_NEW ->
                nav.navigate(Route.TaskEdit(taskId = null)) { launchSingleTop = true }
            MainActivity.DEST_CUSTOM_INSTRUCTION ->
                nav.navigate(Route.CustomInstruction) { launchSingleTop = true }
            MainActivity.DEST_MODELS ->
                nav.navigate(Route.Models) { launchSingleTop = true }
        }
        onDeepLinkConsumed()
    }

    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${chat.title}\" and its messages will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    val wasCurrent = chat.id == currentChatId
                    viewModel.deleteChat(chat.id) { replacementId ->
                        if (wasCurrent) {
                            nav.navigate(Route.Chat(replacementId)) {
                                popUpTo(nav.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    pendingRename?.let { chat ->
        RenameChatDialog(
            initialTitle = chat.title,
            onConfirm = { newTitle ->
                viewModel.renameChat(chat.id, newTitle)
                pendingRename = null
            },
            onDismiss = { pendingRename = null },
        )
    }
}

@Composable
private fun DrawerHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lc_sparkles),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Aide",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private inline fun <reified T : Any> NavBackStackEntry.toRouteOrNull(): T? =
    runCatching { this.toRoute<T>() }.getOrNull()
