package com.sabreware.aide.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChat(id: String): ChatEntity?

    // IGNORE, never REPLACE: a replace deletes the old row first, and the delete cascades to its messages.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatIfAbsent(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: String): Flow<ChatEntity?>

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE chats SET isStarred = :starred, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean, updatedAt: Long)

    @Query("UPDATE chats SET isArchived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: String)

    @Query("DELETE FROM chats WHERE id IN (:ids)")
    suspend fun deleteChats(ids: List<String>)

    @Query("UPDATE chats SET isArchived = :archived, updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun setArchivedForChats(ids: List<String>, archived: Boolean, updatedAt: Long)

    // Message order is the id: autoincrement, so monotonic with insertion (createdAt can tie within a ms).
    // `id` is the INTEGER PRIMARY KEY, i.e. the rowid, which the chatId index carries — so these keyset reads
    // walk (chatId, id) in the index and touch only the rows they return, however long the chat.

    /** The newest [limit] messages at or below [upToId], NEWEST first. */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id <= :upToId ORDER BY id DESC LIMIT :limit")
    fun observeMessagesDescending(chatId: String, upToId: Long, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT id FROM messages WHERE chatId = :chatId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun messageIdsAfter(chatId: String, afterId: Long, limit: Int): List<Long>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun messagesSnapshot(chatId: String): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE chatId = :chatId)")
    suspend fun hasMessages(chatId: String): Boolean

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    /** Append [message] and bump its chat's recency as ONE write, so observers re-read once, not twice. */
    @Transaction
    suspend fun insertMessageAndTouch(message: MessageEntity): Long {
        val id = insertMessage(message)
        touch(message.chatId, message.createdAt)
        return id
    }

    // Edit-a-turn restart: drop the edited message and everything after it. id is autoincrement, so
    // within a chat it's monotonic with insertion order — `id >= fromId` is exactly "this turn onward".
    @Query("DELETE FROM messages WHERE chatId = :chatId AND id >= :fromId")
    suspend fun deleteMessagesFrom(chatId: String, fromId: Long)

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateMessageText(id: Long, text: String)

    @Query("UPDATE messages SET text = :text, partsJson = :partsJson WHERE id = :id")
    suspend fun updateMessageBody(id: Long, text: String, partsJson: String)

    @Query(
        "UPDATE messages SET statsTtftMs = :ttftMs, statsTotalMs = :totalMs, " +
            "statsTokensPerSec = :tokensPerSec, statsInputTokens = :inputTokens, " +
            "statsOutputTokens = :outputTokens WHERE id = :id",
    )
    suspend fun updateMessageStats(
        id: Long,
        ttftMs: Long?,
        totalMs: Long,
        tokensPerSec: Double?,
        inputTokens: Int?,
        outputTokens: Int?,
    )
}
