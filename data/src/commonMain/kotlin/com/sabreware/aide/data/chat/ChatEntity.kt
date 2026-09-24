package com.sabreware.aide.data.chat

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    // Surface persisted as its enum name; mapped back leniently (unknown -> CHAT).
    val surface: String = "CHAT",
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,
    // Denormalized flat text: the cheap streaming-update target and flat read; `partsJson` is canonical.
    val text: String,
    val createdAt: Long,
    val partsJson: String? = null,
    // Per-turn generation metrics (assistant turns only); all null otherwise. See [MessageStats].
    val statsTtftMs: Long? = null,
    val statsTotalMs: Long? = null,
    val statsTokensPerSec: Double? = null,
    val statsInputTokens: Int? = null,
    val statsOutputTokens: Int? = null,
)
