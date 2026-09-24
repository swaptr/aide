package com.sabreware.aide.ui.app

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.designsystem.feature.FeatureRegistry
import com.sabreware.aide.ui.chat.ChatScreen
import com.sabreware.aide.ui.chats.ChatsScreen
import com.sabreware.aide.ui.custom.CustomInstructionScreen
import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.ui.settings.SettingsScreen
import kotlinx.coroutines.CoroutineScope

/**
 * The app's core navigation skeleton as a [NavGraphBuilder] extension. The always-present shell destinations
 * (chat, chats, search, custom instruction, settings) are declared here; every pluggable/gate-able **feature**
 * registers its own destination(s) from [features] — one uniform loop, no per-feature or per-platform
 * branching. Platform-only features (Keyboard, Digital Assistant, Tasks) are hosted only where their
 * application installed them, so desktop simply registers fewer.
 *
 * [nav]/[scope] are the surrounding [AppNav] state the callbacks drive. [onToggleDrawer]/[isDrawerOpen] abstract
 * over the two drawer presentations (modal overlay on compact, displacing sidebar on wide).
 */
internal fun NavGraphBuilder.appDestinations(
    nav: NavHostController,
    features: FeatureRegistry,
    scope: CoroutineScope,
    onToggleDrawer: () -> Unit,
    isDrawerOpen: () -> Boolean,
) {
    composable<Route.Chat> {
        ChatScreen(
            onOpenDrawer = onToggleDrawer,
            // Lets the composer re-acquire focus only once the drawer has fully closed.
            isDrawerOpen = isDrawerOpen(),
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
    composable<Route.Chats> {
        ChatsScreen(
            onOpenDrawer = onToggleDrawer,
            onOpenChat = { id ->
                nav.navigate(Route.Chat(id)) {
                    popUpTo<Route.Chat> { inclusive = true }
                    launchSingleTop = true
                }
            },
            onNewChat = {
                nav.navigate(Route.Chat("")) {
                    popUpTo<Route.Chat> { inclusive = true }
                    launchSingleTop = true
                }
            },
            // The chat sitting under this page (if we arrived from one) — lets the page hand off to a
            // replacement when its open chat is deleted/archived.
            currentChatId = runCatching { nav.previousBackStackEntry?.toRoute<Route.Chat>() }
                .getOrNull()?.chatId,
        )
    }
    composable<Route.CustomInstruction> {
        CustomInstructionScreen()
    }
    composable<Route.Settings> {
        SettingsScreen(
            onOpenDrawer = onToggleDrawer,
            onOpenFeature = { route -> nav.navigate(route) },
        )
    }
    // Every feature hosts its own destination(s) — shared or platform-only, all the same loop. Task/keyboard/
    // etc. destinations live entirely inside their feature objects (nested graphs), not here.
    features.features.forEach { it.register(this, nav) }
}
