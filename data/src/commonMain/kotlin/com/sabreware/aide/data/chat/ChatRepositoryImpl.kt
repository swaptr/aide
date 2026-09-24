package com.sabreware.aide.data.chat

import com.sabreware.aide.core.domain.cache.snapshotCache
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.llm.Surface
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalUuidApi::class)
class ChatRepositoryImpl(
    private val dao: ChatDao,
    appScope: CoroutineScope,
) : ChatRepository {

    // The app root collects this for the drawer session-long, so the Chats screen and search open seeded
    // from the same live Room subscription instead of re-running the query.
    override val chats: StateFlow<List<Chat>?> =
        dao.observeChats().map { it.map(ChatEntity::toDomain) }
            .snapshotCache(appScope, "chats")

    // Fresh query, not the replay cache — callers pick fallbacks right after a delete/archive, and the
    // cache may not have caught up with the write yet.
    override suspend fun chatsSnapshot(): List<Chat> =
        dao.observeChats().first().map(ChatEntity::toDomain)

    override suspend fun getChat(id: String): Chat? = dao.getChat(id)?.toDomain()

    override fun observeChat(chatId: String): Flow<Chat?> =
        dao.observeChat(chatId).map { it?.toDomain() }

    override fun observeMessages(chatId: String): Flow<List<StoredMessage>> =
        dao.observeMessages(chatId).map { it.map(MessageEntity::toStoredMessage) }

    override suspend fun messagesSnapshot(chatId: String): List<StoredMessage> =
        dao.messagesSnapshot(chatId).map { it.toStoredMessage() }

    override suspend fun createChat(title: String, surface: Surface): Chat {
        val now = Clock.System.now().toEpochMilliseconds()
        val chat = ChatEntity(
            id = Uuid.random().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            surface = surface.name,
        )
        dao.upsertChat(chat)
        return chat.toDomain()
    }

    override suspend fun setTitle(chatId: String, title: String) {
        dao.updateTitle(chatId, title, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun touch(chatId: String) {
        dao.touch(chatId, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun appendMessage(chatId: String, role: String, text: String): Long =
        appendMessage(chatId, AideMessage(AideRole.fromWireString(role), listOf(AidePart.Text(text))))

    override suspend fun appendMessage(chatId: String, message: AideMessage): Long {
        val now = Clock.System.now().toEpochMilliseconds()
        // partsJson canonical; `text` is the denormalized flat copy for cheap reads/streaming updates.
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

    override suspend fun updateMessageText(id: Long, text: String) {
        dao.updateMessageText(id, text)
    }

    override suspend fun updateMessage(id: Long, message: AideMessage) {
        dao.updateMessageBody(
            id = id,
            text = message.textContent,
            partsJson = MessageJson.encodeToString(message.parts),
        )
    }

    override suspend fun updateMessageStats(id: Long, stats: MessageStats) {
        dao.updateMessageStats(
            id = id,
            ttftMs = stats.ttftMs,
            totalMs = stats.totalMs,
            tokensPerSec = stats.tokensPerSec,
            inputTokens = stats.inputTokens,
            outputTokens = stats.outputTokens,
        )
    }

    override suspend fun deleteMessagesFrom(chatId: String, fromId: Long) {
        dao.deleteMessagesFrom(chatId, fromId)
    }

    override suspend fun deleteChat(chatId: String) {
        dao.deleteChat(chatId)
    }

    override suspend fun setStarred(chatId: String, starred: Boolean) {
        dao.setStarred(chatId, starred, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun setArchived(chatId: String, archived: Boolean) {
        dao.setArchived(chatId, archived, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun deleteChats(chatIds: List<String>) {
        dao.deleteChats(chatIds)
    }

    override suspend fun setArchivedForChats(chatIds: List<String>, archived: Boolean) {
        dao.setArchivedForChats(chatIds, archived, Clock.System.now().toEpochMilliseconds())
    }
}
