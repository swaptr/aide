package com.sabreware.aide.core.domain.chat

import com.sabreware.aide.core.domain.llm.Surface

/** A conversation. Domain model — the persistence shape lives in `data/chat/ChatEntity`. */
data class Chat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val surface: Surface = Surface.CHAT,
)

/**
 * Chat ids are minted on the client, when a chat is OPENED, not when its row is written. The id is the chat's
 * identity from its first frame: the route that shows it, the drawer's highlight and a restored back stack all
 * name the same chat before and after its first send. The row itself is written lazily
 * ([ChatRepository.createChat] on the first send), so an abandoned new chat never leaves an empty row behind.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
fun newChatId(): String = kotlin.uuid.Uuid.random().toString()
