package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.ChatRepository

// Returns id of next chat to navigate to. Empty string = draft (no DB row materialised).
class DeleteChatWithFallbackUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String): String {
        chats.deleteChat(chatId)
        val remaining = chats.chatsSnapshot().filter { !it.isArchived }
        return remaining.firstOrNull()?.id.orEmpty()
    }
}

class ArchiveChatWithFallbackUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String): String {
        chats.setArchived(chatId, true)
        val remaining = chats.chatsSnapshot()
            .filter { it.id != chatId && !it.isArchived }
        return remaining.firstOrNull()?.id.orEmpty()
    }
}
