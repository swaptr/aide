package com.sabreware.aide.core.domain.chat

import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-facing chat store. Returns domain [Chat] / [StoredMessage] — Room entities never cross
 * this boundary. Impl ([data.chat.ChatRepositoryImpl]) owns the DAO + entity↔domain mapping.
 */
interface ChatRepository {
    /**
     * The chat list, cached app-wide: null until the session's first query, then the latest list — same
     * shape as [com.sabreware.aide.core.domain.model.ModelRegistryRepository.models], so the drawer, the
     * Chats screen and search all ride ONE live Room subscription and re-open seeded from its cache.
     */
    val chats: StateFlow<List<Chat>?>

    /** A FRESH query, bypassing the cache — for pick-a-fallback decisions right after a mutation, where
     *  the replay cache may not have caught up with the write yet. */
    suspend fun chatsSnapshot(): List<Chat>

    suspend fun getChat(id: String): Chat?
    fun observeChat(chatId: String): Flow<Chat?>

    /**
     * A live window over [chatId]'s messages: the newest [limit] with an id at or below [upToId] (null = the
     * live tail), oldest first. Keyset-paged on the message id, so its cost is the window's, never the chat's:
     * a chat with thousands of turns re-reads only these rows when any message is written.
     */
    fun observeMessageWindow(chatId: String, upToId: Long?, limit: Int): Flow<MessageWindow>

    /** Ids of up to [limit] messages after [afterId], ascending: how a window scrolled away from the tail steps
     *  back toward it. */
    suspend fun messageIdsAfter(chatId: String, afterId: Long, limit: Int): List<Long>

    /** Every message, oldest first — for seeding a model session, never for the UI (see [observeMessageWindow]). */
    suspend fun messagesSnapshot(chatId: String): List<StoredMessage>

    suspend fun hasMessages(chatId: String): Boolean

    /**
     * Write the chat's row under [id] — minted when the chat was opened ([newChatId]) — unless it already
     * exists, and return it. Idempotent: a second call never replaces the row (a replace would cascade-delete
     * its messages).
     */
    suspend fun createChat(
        id: String = newChatId(),
        title: String = "New chat",
        surface: Surface = Surface.CHAT,
    ): Chat
    suspend fun setTitle(chatId: String, title: String)
    suspend fun touch(chatId: String)

    suspend fun appendMessage(chatId: String, role: String, text: String): Long
    suspend fun appendMessage(chatId: String, message: AideMessage): Long

    /** Delete the message with [fromId] and every message after it in the chat (edit-a-turn restart). */
    suspend fun deleteMessagesFrom(chatId: String, fromId: Long)
    suspend fun updateMessageText(id: Long, text: String)
    suspend fun updateMessage(id: Long, message: AideMessage)
    suspend fun updateMessageStats(id: Long, stats: MessageStats)

    suspend fun deleteChat(chatId: String)
    suspend fun setStarred(chatId: String, starred: Boolean)
    suspend fun setArchived(chatId: String, archived: Boolean)

    suspend fun deleteChats(chatIds: List<String>)
    suspend fun setArchivedForChats(chatIds: List<String>, archived: Boolean)
}
