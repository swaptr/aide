package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.ChatRepository

/**
 * Deletes many chats in one transaction. Returns the id of the chat to fall back to (first remaining
 * non-archived), or "" for a draft — same contract as [DeleteChatWithFallbackUseCase], so the caller
 * can navigate away only if the open chat was among those deleted.
 */
class DeleteChatsWithFallbackUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatIds: List<String>): String {
        chats.deleteChats(chatIds)
        return chats.chatsSnapshot().filter { !it.isArchived }.firstOrNull()?.id.orEmpty()
    }
}

/** Archives (or unarchives) many chats in one transaction. */
class SetChatsArchivedUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatIds: List<String>, archived: Boolean) =
        chats.setArchivedForChats(chatIds, archived)
}
