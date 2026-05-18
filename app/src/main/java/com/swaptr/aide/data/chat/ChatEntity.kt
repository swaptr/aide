package com.swaptr.aide.data.chat

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
    // Surface name as string (not enum) so new values don't crash destructive migrations.
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
    val text: String,
    val createdAt: Long,
    // Canonical source when non-null; `text` mirrors flat textContent for legacy callers only.
    val partsJson: String? = null,
)
