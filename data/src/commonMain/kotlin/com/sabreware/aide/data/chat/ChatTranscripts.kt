package com.sabreware.aide.data.chat

import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.ObservableChatTranscript
import com.sabreware.aide.core.domain.chat.StoredMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PersistentChatTranscript(
    private val chats: ChatRepository,
    private val chatId: String,
) : ChatTranscript {
    override suspend fun priorMessages(): List<AideMessage> =
        chats.messagesSnapshot(chatId).map { it.message }

    override suspend fun isEmpty(): Boolean = !chats.hasMessages(chatId)

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

    override suspend fun updateAssistantStats(id: Long, stats: MessageStats) {
        chats.updateMessageStats(id, stats)
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
class InMemoryChatTranscript : ObservableChatTranscript {

    private val _entries = MutableStateFlow<List<StoredMessage>>(emptyList())
    override val entries: StateFlow<List<StoredMessage>> = _entries.asStateFlow()
    private var nextId: Long = -1L

    override suspend fun priorMessages(): List<AideMessage> =
        _entries.value.map { it.message }

    override suspend fun isEmpty(): Boolean = _entries.value.isEmpty()

    override suspend fun appendUserMessage(message: AideMessage) {
        _entries.value = _entries.value + StoredMessage(nextId--, message)
    }

    override suspend fun appendAssistantPlaceholder(): Long {
        val id = nextId--
        _entries.value = _entries.value + StoredMessage(id, AideMessage.model(""))
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

    override suspend fun updateAssistantStats(id: Long, stats: MessageStats) {
        val list = _entries.value
        val idx = list.indexOfLast { it.id == id }
        if (idx < 0) return
        _entries.value = list.toMutableList().also { it[idx] = list[idx].copy(stats = stats) }
    }

    override suspend fun appendToolResponse(response: AidePart.ToolResponse): Long {
        val id = nextId--
        _entries.value = _entries.value + StoredMessage(
            id = id,
            message = AideMessage(AideRole.Tool, listOf(response)),
        )
        return id
    }

    override suspend fun truncateFrom(id: Long) {
        val list = _entries.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        _entries.value = list.subList(0, idx).toList()
    }
}
