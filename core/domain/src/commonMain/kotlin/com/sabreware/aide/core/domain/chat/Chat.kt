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
