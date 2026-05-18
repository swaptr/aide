package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.chat.ChatRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

// Returns id of next chat to navigate to. Empty string = draft (no DB row materialised).
class DeleteChatWithFallbackUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String): String {
        chats.deleteChat(chatId)
        val remaining = chats.observeChats().first().filter { !it.isArchived }
        return remaining.firstOrNull()?.id.orEmpty()
    }
}

class ArchiveChatWithFallbackUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String): String {
        chats.setArchived(chatId, true)
        val remaining = chats.observeChats().first()
            .filter { it.id != chatId && !it.isArchived }
        return remaining.firstOrNull()?.id.orEmpty()
    }
}
