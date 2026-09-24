package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class ObserveChatsUseCase(
    private val chats: ChatRepository,
) {
    /** The app-wide cached list — null until the session's first query (see [ChatRepository.chats]). */
    operator fun invoke(): StateFlow<List<Chat>?> = chats.chats
}

class ObserveChatUseCase(
    private val chats: ChatRepository,
) {
    operator fun invoke(chatId: String): Flow<Chat?> = chats.observeChat(chatId)
}

class ObserveChatMessagesUseCase(
    private val chats: ChatRepository,
) {
    operator fun invoke(chatId: String): Flow<List<StoredMessage>> =
        chats.observeMessages(chatId)
}

class SetChatStarredUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, starred: Boolean) =
        chats.setStarred(chatId, starred)
}

class SetChatArchivedUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, archived: Boolean) =
        chats.setArchived(chatId, archived)
}

class RenameChatUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, newTitle: String) =
        chats.setTitle(chatId, newTitle.trim())
}

/** Truncate a chat at an edited turn: removes that message and everything after it. */
class DeleteMessagesFromUseCase(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, fromId: Long) =
        chats.deleteMessagesFrom(chatId, fromId)
}
