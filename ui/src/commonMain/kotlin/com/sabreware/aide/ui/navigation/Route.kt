package com.sabreware.aide.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.sabreware.aide.core.domain.chat.newChatId
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    /**
     * One chat, named by its id from the first frame. A new chat's id is minted when it is OPENED ([new]); its
     * row is written on the first send, under the same id — so the route never changes as the chat goes from
     * new to saved, a restored back stack reopens it, and every new chat is its own key (its own page and
     * ViewModel). An id with no row is a new, empty chat.
     */
    @Serializable
    data class Chat(
        val chatId: String,
        // Opens in incognito: the session is never persisted; leaving the route discards it.
        val incognito: Boolean = false,
    ) : Route {
        companion object {
            /** A new chat under a fresh id. Nothing is written until its first send. */
            fun new(): Chat = Chat(newChatId())

            /** The chat [chatId], or a new chat when it is blank (no chat left to show). */
            fun of(chatId: String): Chat = if (chatId.isBlank()) new() else Chat(chatId)
        }
    }

    @Serializable
    data object Chats : Route

    @Serializable
    data object CustomInstruction : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object ToolsSettings : Route

    @Serializable
    data object SpeechSettings : Route

    @Serializable
    data object Licenses : Route

    @Serializable
    data class LicenseDetail(val libraryId: String) : Route
}
