package com.swaptr.aide.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data class Chat(
        val chatId: String,
        // When true the session is not persisted; leaving the route discards it.
        val incognito: Boolean = false,
    ) : Route

    @Serializable
    data object Models : Route

    @Serializable
    data object Tasks : Route

    @Serializable
    data class TaskDetail(val taskId: String) : Route

    @Serializable
    data class TaskEdit(
        val taskId: String? = null,
        val groupId: String? = null,
    ) : Route

    @Serializable
    data object CustomInstruction : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object WebSearchSettings : Route

    @Serializable
    data object ToolsSettings : Route

    @Serializable
    data object SpeechSettings : Route
}
