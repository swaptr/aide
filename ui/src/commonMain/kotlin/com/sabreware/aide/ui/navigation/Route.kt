package com.sabreware.aide.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data class Chat(
        // Empty = draft: the DB row is created lazily on first send, and every "+ New chat" call site
        // already passes "". Defaulted so `Chat()` IS the draft — which also lets a host test decode the
        // route from an empty SavedStateHandle (absent args fill from defaults and never touch a Bundle).
        val chatId: String = "",
        // When true the session is not persisted; leaving the route discards it.
        val incognito: Boolean = false,
    ) : Route

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
