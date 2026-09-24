package com.sabreware.aide.ui.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.designsystem.navigation.Navigator

/**
 * The app's [Navigator] over its ONE back stack (Navigation 3). `navigate` pushes a full screen, `goBack` pops,
 * and the few shell-only moves (open a chat, reset to a chat) are explicit edits of
 * the stack — there is no NavController and no option DSL anywhere.
 */
@Stable
class AppNavigator(private val backStack: MutableList<NavKey>) : Navigator {
    override val canGoBack: Boolean get() = backStack.size > 1

    /** Push [route] unless it is already on top (single-top). */
    override fun navigate(route: Any) {
        val key = route as NavKey
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    override fun goBack(): Boolean =
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            true
        } else {
            false
        }

    override fun replace(route: Any) {
        backStack[backStack.lastIndex] = route as NavKey
    }

    /** The key under the top — the page this one was opened from. */
    val previous: NavKey? get() = backStack.getOrNull(backStack.lastIndex - 1)

    /** Open [chat] in place of the chat the user is in: everything from the last chat up is replaced. */
    fun openChat(chat: Route.Chat) {
        val from = backStack.indexOfLast { it is Route.Chat }
        if (from >= 0) repeat(backStack.size - from) { backStack.removeAt(backStack.lastIndex) }
        backStack.add(chat)
    }

    /** Replace the WHOLE stack with [chat] (the open chat was archived or deleted). */
    fun resetTo(chat: Route.Chat) {
        backStack.clear()
        backStack.add(chat)
    }
}
