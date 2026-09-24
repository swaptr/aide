package com.sabreware.aide.data.chat

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.MessageStats
import kotlinx.serialization.json.Json

// ignoreUnknownKeys: tolerate part variants written by a newer app version when decoding a stored blob.
internal val MessageJson: Json = Json { ignoreUnknownKeys = true }

// Stats present iff a totalMs was recorded (only completed assistant turns carry metrics).
fun MessageEntity.toStats(): MessageStats? {
    val total = statsTotalMs ?: return null
    return MessageStats(
        ttftMs = statsTtftMs,
        totalMs = total,
        tokensPerSec = statsTokensPerSec,
        inputTokens = statsInputTokens,
        outputTokens = statsOutputTokens,
    )
}

fun MessageEntity.toAideMessage(): AideMessage {
    val parts = partsJson?.let { json ->
        runCatching { MessageJson.decodeFromString<List<AidePart>>(json) }.getOrNull()
    } ?: listOf(AidePart.Text(text))
    return AideMessage(
        role = AideRole.fromWireString(role),
        parts = parts,
        createdAt = createdAt,
    )
}
