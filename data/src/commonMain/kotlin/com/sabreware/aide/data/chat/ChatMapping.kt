package com.sabreware.aide.data.chat

import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.llm.Surface

// Surface stored as its enum name; mapped back leniently so an unknown value falls back to CHAT.
fun ChatEntity.toDomain(): Chat = Chat(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isStarred = isStarred,
    isArchived = isArchived,
    surface = runCatching { Surface.valueOf(surface) }.getOrDefault(Surface.CHAT),
)

fun MessageEntity.toStoredMessage(): StoredMessage =
    StoredMessage(id = id, message = toAideMessage(), stats = toStats())
