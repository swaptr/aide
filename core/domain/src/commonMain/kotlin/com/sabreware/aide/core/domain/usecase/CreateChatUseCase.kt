package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository

class CreateChatUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(): Chat = chats.createChat()
}
