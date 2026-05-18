package com.swaptr.aide.data.chat

import com.swaptr.aide.domain.llm.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import java.util.UUID

class ChatRepository(private val dao: ChatDao) {

    fun observeChats(): Flow<List<ChatEntity>> = dao.observeChats()

    suspend fun getChat(id: String): ChatEntity? = dao.getChat(id)

    fun observeMessages(chatId: String): Flow<List<MessageEntity>> = dao.observeMessages(chatId)

    suspend fun messagesSnapshot(chatId: String): List<MessageEntity> =
        dao.messagesSnapshot(chatId)

    suspend fun createChat(
        title: String = "New chat",
        surface: Surface = Surface.CHAT,
    ): ChatEntity {
        val now = System.currentTimeMillis()
        val chat = ChatEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            surface = surface.name,
        )
        dao.upsertChat(chat)
        return chat
    }

    suspend fun setTitle(chatId: String, title: String) {
        dao.updateTitle(chatId, title, System.currentTimeMillis())
    }

    fun observeChat(chatId: String): Flow<ChatEntity?> = dao.observeChat(chatId)

    suspend fun touch(chatId: String) {
        dao.touch(chatId, System.currentTimeMillis())
    }

    suspend fun appendMessage(chatId: String, role: String, text: String): Long =
        appendMessage(chatId, AideMessage(AideRole.fromWireString(role), listOf(AidePart.Text(text))))

    suspend fun appendMessage(chatId: String, message: AideMessage): Long {
        val now = System.currentTimeMillis()
        // partsJson canonical; `text` mirror for legacy callers that skip parts rehydration.
        val partsJson = MessageJson.encodeToString(message.parts)
        val id = dao.insertMessage(
            MessageEntity(
                chatId = chatId,
                role = message.role.wireString(),
                text = message.textContent,
                createdAt = now,
                partsJson = partsJson,
            ),
        )
        dao.touch(chatId, now)
        return id
    }

    suspend fun updateMessageText(id: Long, text: String) {
        dao.updateMessageText(id, text)
    }

    suspend fun updateMessage(id: Long, message: AideMessage) {
        dao.updateMessageBody(
            id = id,
            text = message.textContent,
            partsJson = MessageJson.encodeToString(message.parts),
        )
    }

    suspend fun deleteChat(chatId: String) {
        dao.deleteChat(chatId)
    }

    suspend fun setStarred(chatId: String, starred: Boolean) {
        dao.setStarred(chatId, starred, System.currentTimeMillis())
    }

    suspend fun setArchived(chatId: String, archived: Boolean) {
        dao.setArchived(chatId, archived, System.currentTimeMillis())
    }
}
