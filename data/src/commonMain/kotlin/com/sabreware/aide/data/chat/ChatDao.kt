package com.sabreware.aide.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChat(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: String): kotlinx.coroutines.flow.Flow<ChatEntity?>

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

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesSnapshot(chatId: String): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

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
