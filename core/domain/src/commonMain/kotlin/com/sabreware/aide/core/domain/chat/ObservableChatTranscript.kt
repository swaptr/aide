package com.sabreware.aide.core.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * A [ChatTranscript] whose contents can be observed live. Used for incognito/in-memory sessions,
 * where the UI renders directly from the transcript instead of from persisted Room rows. Persistent
 * transcripts deliberately do NOT implement this — their UI observes the database via the chat repo.
 */
interface ObservableChatTranscript : ChatTranscript {
    /** All turns so far, emitted on every append/update. */
    val entries: Flow<List<StoredMessage>>

    /** Drop the message with [id] and every turn after it — backs editing a turn (restart from here). */
    suspend fun truncateFrom(id: Long)
}
