package com.sabreware.aide.ui.app

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.feature.FeatureRegistry
import com.sabreware.aide.ui.chat.ChatScreen
import com.sabreware.aide.ui.chats.ChatsScreen
import com.sabreware.aide.ui.custom.CustomInstructionScreen
import com.sabreware.aide.ui.navigation.AppNavigator
import com.sabreware.aide.ui.navigation.Route
import com.sabreware.aide.ui.settings.SettingsScreen

/**
 * The app's pages, registered once. The always-present shell pages (chat, chats, custom instruction,
 * settings) are declared here; every pluggable/gate-able **feature** registers its own from [features] — one
 * uniform loop, no per-feature or per-platform branching, so desktop simply registers fewer. Any of these
 * can be pushed as a screen or opened in a modal flow ([AppNavigator.openModal]) without another registration.
 *
 * [onToggleDrawer]/[isDrawerOpen] abstract over the two drawer presentations (modal overlay on compact,
 * displacing sidebar on wide).
 */
internal fun EntryProviderScope<NavKey>.appEntries(
    nav: AppNavigator,
    features: FeatureRegistry,
    onToggleDrawer: () -> Unit,
    isDrawerOpen: () -> Boolean,
) {
    entry<Route.Chat> { route ->
        ChatScreen(
            route = route,
            onOpenDrawer = onToggleDrawer,
            // Lets the composer re-acquire focus only once the drawer has fully closed.
            isDrawerOpen = isDrawerOpen(),
            onNavigateToChat = { replacementId -> nav.resetTo(Route.Chat.of(replacementId)) },
            onNewChat = { nav.openChat(Route.Chat.new()) },
        )
    }
    entry<Route.Chats> {
        ChatsScreen(
            onOpenDrawer = onToggleDrawer,
            onOpenChat = { id -> nav.openChat(Route.Chat(id)) },
            onNewChat = { nav.openChat(Route.Chat.new()) },
            // The chat sitting under this page (if we arrived from one) — lets the page hand off to a
            // replacement when its open chat is deleted/archived.
            currentChatId = (nav.previous as? Route.Chat)?.chatId,
        )
    }
    entry<Route.CustomInstruction> { CustomInstructionScreen() }
    entry<Route.Settings> {
        SettingsScreen(
            onOpenDrawer = onToggleDrawer,
            onOpenFeature = { route -> nav.navigate(route) },
        )
    }
    // Every feature registers its own pages — shared or platform-only, all the same loop.
    features.features.forEach { with(it) { entries() } }
}
