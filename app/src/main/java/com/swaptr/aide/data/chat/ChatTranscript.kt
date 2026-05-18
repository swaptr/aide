package com.swaptr.aide.data.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// In-memory variant exists so incognito sessions never hit disk.
interface ChatTranscript {
    /** Snapshot of all prior turns. Used to seed a fresh KV cache on session rebind. */
    suspend fun priorMessages(): List<AideMessage>

    suspend fun appendUserMessage(message: AideMessage)

    /** Hook fired on the first user turn — persistent impls use it to derive a title. */
    suspend fun onFirstUserTurn(userText: String) {}

    /** Append an empty assistant turn and return its id for in-place updates. */
    suspend fun appendAssistantPlaceholder(): Long

    /** Replace the text of an assistant turn previously created via [appendAssistantPlaceholder]. */
    suspend fun updateAssistantText(id: Long, text: String)

    // Used to land ToolCall annotations so reopened chats can paint a chip per call.
    suspend fun updateAssistantMessage(id: Long, message: AideMessage)

    // Returns the new row id so callers can correlate with the assistant turn's ToolCall.callId.
    suspend fun appendToolResponse(response: AidePart.ToolResponse): Long
}

class PersistentChatTranscript(
    private val chats: ChatRepository,
    private val chatId: String,
) : ChatTranscript {
    override suspend fun priorMessages(): List<AideMessage> =
        chats.messagesSnapshot(chatId).map { it.toAideMessage() }

    override suspend fun appendUserMessage(message: AideMessage) {
        chats.appendMessage(chatId, message)
    }

    override suspend fun onFirstUserTurn(userText: String) {
        chats.setTitle(chatId, deriveTitle(userText))
    }

    override suspend fun appendAssistantPlaceholder(): Long =
        chats.appendMessage(chatId, role = "model", text = "")

    override suspend fun updateAssistantText(id: Long, text: String) {
        chats.updateMessageText(id, text)
    }

    override suspend fun updateAssistantMessage(id: Long, message: AideMessage) {
        chats.updateMessage(id, message)
    }

    override suspend fun appendToolResponse(response: AidePart.ToolResponse): Long {
        return chats.appendMessage(
            chatId,
            AideMessage(role = AideRole.Tool, parts = listOf(response)),
        )
    }

    companion object {
        private const val TITLE_MAX_CHARS = 60
        private fun deriveTitle(firstPrompt: String): String {
            val flat = firstPrompt.replace("\n", " ").trim()
            return if (flat.length <= TITLE_MAX_CHARS) flat
            else flat.take(TITLE_MAX_CHARS - 1) + "…"
        }
    }
}

// Placeholder ids are negative to avoid colliding with Room's autoincrement positives.
class InMemoryChatTranscript : ChatTranscript {

    data class Entry(val id: Long, val message: AideMessage)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var nextId: Long = -1L

    override suspend fun priorMessages(): List<AideMessage> =
        _entries.value.map { it.message }

    override suspend fun appendUserMessage(message: AideMessage) {
        _entries.value = _entries.value + Entry(nextId--, message)
    }

    override suspend fun appendAssistantPlaceholder(): Long {
        val id = nextId--
        _entries.value = _entries.value + Entry(id, AideMessage.model(""))
        return id
    }

    override suspend fun updateAssistantText(id: Long, text: String) {
        val list = _entries.value
        val idx = list.indexOfLast { it.id == id }
        if (idx < 0) return
        // Preserve previously-stamped tool-call parts (added incrementally during the
        // stream by [updateAssistantMessage]) — only swap the text.
        val previousParts = list[idx].message.parts
        val nonText = previousParts.filterNot { it is AidePart.Text }
        val newParts = buildList<AidePart> {
            add(AidePart.Text(text))
            addAll(nonText)
        }
        val replaced = list[idx].copy(
            message = AideMessage(AideRole.Model, newParts),
        )
        _entries.value = list.toMutableList().also { it[idx] = replaced }
    }

    override suspend fun updateAssistantMessage(id: Long, message: AideMessage) {
        val list = _entries.value
        val idx = list.indexOfLast { it.id == id }
        if (idx < 0) return
        val replaced = list[idx].copy(message = message)
        _entries.value = list.toMutableList().also { it[idx] = replaced }
    }

    override suspend fun appendToolResponse(response: AidePart.ToolResponse): Long {
        val id = nextId--
        _entries.value = _entries.value + Entry(
            id = id,
            message = AideMessage(AideRole.Tool, listOf(response)),
        )
        return id
    }
}
