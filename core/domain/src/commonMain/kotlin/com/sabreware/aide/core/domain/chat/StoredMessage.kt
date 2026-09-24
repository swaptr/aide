package com.sabreware.aide.core.domain.chat

/**
 * A persisted (or in-memory placeholder) message paired with its stable row id, so callers can
 * update a turn in place during streaming. Single shape for both the persistent and incognito
 * transcripts.
 */
data class StoredMessage(
    val id: Long,
    val message: AideMessage,
    // Generation metrics for assistant turns; null for user/tool turns and turns recorded before stats existed.
    val stats: MessageStats? = null,
)
