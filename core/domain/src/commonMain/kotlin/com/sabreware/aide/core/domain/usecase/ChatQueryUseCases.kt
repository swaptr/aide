package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.MessageWindow
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

    /** Whether [chatId]'s row exists per the in-memory chat list; null while that list is unread. PAINTING only. */
    fun peekSaved(chatId: String): Boolean? = chats.chats.value?.any { it.id == chatId }
}

class ObserveChatMessagesUseCase(
    private val chats: ChatRepository,
) {
    /** See [ChatRepository.observeMessageWindow]. */
    operator fun invoke(chatId: String, upToId: Long?, limit: Int): Flow<MessageWindow> =
        chats.observeMessageWindow(chatId, upToId, limit)

    /** See [ChatRepository.messageIdsAfter]. */
    suspend fun idsAfter(chatId: String, afterId: Long, limit: Int): List<Long> =
        chats.messageIdsAfter(chatId, afterId, limit)
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
