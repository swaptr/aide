package com.sabreware.aide.core.domain.chat

// In-memory variant exists so incognito sessions never hit disk.
// Pure contract; concrete impls (persistent / in-memory) live in the data layer.
interface ChatTranscript {
    /** Snapshot of all prior turns. Used to seed a fresh KV cache on session rebind. */
    suspend fun priorMessages(): List<AideMessage>

    /** Whether no turn has been recorded yet — cheaper than [priorMessages] for a transcript on disk. */
    suspend fun isEmpty(): Boolean = priorMessages().isEmpty()

    suspend fun appendUserMessage(message: AideMessage)

    /** Hook fired on the first user turn — persistent impls use it to derive a title. */
    suspend fun onFirstUserTurn(userText: String) {}

    /** Append an empty assistant turn and return its id for in-place updates. */
    suspend fun appendAssistantPlaceholder(): Long

    /** Replace the text of an assistant turn previously created via [appendAssistantPlaceholder]. */
    suspend fun updateAssistantText(id: Long, text: String)

    // Used to land ToolCall annotations so reopened chats can paint a chip per call.
    suspend fun updateAssistantMessage(id: Long, message: AideMessage)

    /** Persist generation metrics for a completed assistant turn so they survive reload. */
    suspend fun updateAssistantStats(id: Long, stats: MessageStats)

    // Returns the new row id so callers can correlate with the assistant turn's ToolCall.callId.
    suspend fun appendToolResponse(response: AidePart.ToolResponse): Long
}
