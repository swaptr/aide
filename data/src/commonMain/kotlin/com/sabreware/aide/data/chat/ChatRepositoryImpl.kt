package com.sabreware.aide.data.chat

import com.sabreware.aide.core.domain.cache.snapshotCache
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.MessageWindow
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString

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

    override fun observeMessageWindow(chatId: String, upToId: Long?, limit: Int): Flow<MessageWindow> = flow {
        // Room re-runs the query on EVERY write to the messages table, and a turn writes several times. Decoding
        // a row's parts JSON is the expensive step, so this collector keeps each row's decoded form and reuses
        // it while the row is unchanged: an emission decodes only the rows that were written.
        val decoded = HashMap<Long, Pair<MessageEntity, StoredMessage>>()
        // One extra row answers "is there anything older?" without a COUNT.
        emitAll(
            dao.observeMessagesDescending(chatId, upToId ?: Long.MAX_VALUE, limit + 1).map { rows ->
                val kept = rows.take(limit)
                val messages = kept.asReversed().map { row ->
                    decoded[row.id]?.takeIf { it.first == row }?.second
                        ?: row.toStoredMessage().also { decoded[row.id] = row to it }
                }
                if (decoded.size > kept.size) {
                    val live = kept.mapTo(HashSet(kept.size)) { it.id }
                    decoded.keys.retainAll(live)
                }
                MessageWindow(messages = messages, hasOlder = rows.size > limit)
            },
        )
    }.flowOn(Dispatchers.Default)

    override suspend fun messageIdsAfter(chatId: String, afterId: Long, limit: Int): List<Long> =
        dao.messageIdsAfter(chatId, afterId, limit)

    override suspend fun messagesSnapshot(chatId: String): List<StoredMessage> =
        dao.messagesSnapshot(chatId).map { it.toStoredMessage() }

    override suspend fun hasMessages(chatId: String): Boolean = dao.hasMessages(chatId)

    override suspend fun createChat(id: String, title: String, surface: Surface): Chat {
        val now = Clock.System.now().toEpochMilliseconds()
        dao.insertChatIfAbsent(
            ChatEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                surface = surface.name,
            ),
        )
        return checkNotNull(dao.getChat(id)) { "chat $id was not written" }.toDomain()
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
        return dao.insertMessageAndTouch(
            MessageEntity(
                chatId = chatId,
                role = message.role.wireString(),
                text = message.textContent,
                createdAt = now,
                partsJson = partsJson,
            ),
        )
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
