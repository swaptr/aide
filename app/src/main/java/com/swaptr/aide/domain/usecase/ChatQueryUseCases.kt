package com.swaptr.aide.domain.usecase

import com.swaptr.aide.data.chat.ChatEntity
import com.swaptr.aide.data.chat.ChatRepository
import com.swaptr.aide.data.chat.MessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveChatsUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    operator fun invoke(): Flow<List<ChatEntity>> = chats.observeChats()
}

class ObserveChatUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    operator fun invoke(chatId: String): Flow<ChatEntity?> = chats.observeChat(chatId)
}

class ObserveChatMessagesUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    operator fun invoke(chatId: String): Flow<List<MessageEntity>> =
        chats.observeMessages(chatId)
}

class SetChatStarredUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, starred: Boolean) =
        chats.setStarred(chatId, starred)
}

class SetChatArchivedUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, archived: Boolean) =
        chats.setArchived(chatId, archived)
}

class RenameChatUseCase @Inject constructor(
    private val chats: ChatRepository,
) {
    suspend operator fun invoke(chatId: String, newTitle: String) =
        chats.setTitle(chatId, newTitle.trim())
}
