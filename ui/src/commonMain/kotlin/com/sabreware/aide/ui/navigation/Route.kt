package com.sabreware.aide.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data class Chat(
        // Empty = draft: the DB row is created lazily on first send. Defaulted so `Chat()` IS the draft — which
        // also lets a host test decode the route from an empty SavedStateHandle (absent args fill from defaults and never touch a Bundle).
        val chatId: String = "",
        // When true the session is not persisted; leaving the route discards it.
        val incognito: Boolean = false,
        // A draft's identity. A draft keeps `chatId = ""` after its first send creates the row, so without this
        // every draft would be an EQUAL key: "New chat" over a draft would re-push the same entry and keep its
        // page and ViewModel, doing nothing. Mint drafts with [draft].
        val draftKey: String = "",
    ) : Route {
        @OptIn(ExperimentalUuidApi::class)
        companion object {
            /** A new draft: a key no other entry holds, so it always gets its own page and ViewModel. */
            fun draft(): Chat = Chat(draftKey = Uuid.random().toString())

            /** The chat [chatId], or a new draft when it is blank (no chat left to show). */
            fun of(chatId: String): Chat = if (chatId.isBlank()) draft() else Chat(chatId)
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
