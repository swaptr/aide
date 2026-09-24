package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository

class CreateChatUseCase(
    private val chats: ChatRepository,
) {
    /** Write the row for the chat opened as [id], if it is not written yet. */
    suspend operator fun invoke(id: String): Chat = chats.createChat(id = id)
}
