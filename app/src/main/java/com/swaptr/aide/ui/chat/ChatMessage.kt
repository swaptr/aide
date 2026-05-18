package com.swaptr.aide.ui.chat

import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.data.chat.AidePart
import com.swaptr.aide.data.chat.AideRole
import com.swaptr.aide.data.chat.InMemoryChatTranscript
import com.swaptr.aide.data.chat.MessageEntity
import com.swaptr.aide.data.chat.toAideMessage

sealed interface ChatMessage {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        val imagePath: String?,
    ) : ChatMessage

    data class Assistant(
        override val id: Long,
        val text: String,
        val isStreaming: Boolean = false,
    ) : ChatMessage

    data class ToolInvocation(
        override val id: Long,
        val callId: String,
        val toolName: String,
        val argsJson: String,
        val resultJson: String?,
        val error: String?,
        val isRunning: Boolean,
    ) : ChatMessage

    data class Thinking(
        override val id: Long,
        val text: String,
        val durationMs: Long,
        val isStreaming: Boolean,
    ) : ChatMessage
}

enum class EngineState {
    Idle,
    Sending,
    Warming,
    Generating,
}

data class ToolConfirmPrompt(
    val opId: String,
    val toolName: String,
    val summary: String,
    val details: List<com.swaptr.aide.domain.llm.gates.WriteConfirmGate.KeyValue> = emptyList(),
    val severity: com.swaptr.aide.domain.llm.gates.WriteConfirmGate.Severity =
        com.swaptr.aide.domain.llm.gates.WriteConfirmGate.Severity.WARN,
)

data class ChatUiState(
    val chatId: String = "",
    val currentModelId: String = "",
    val modelDisplayName: String = "",
    val modelProvider: com.swaptr.aide.data.catalog.ProviderId? = null,
    val modelSupportsTools: Boolean = false,
    val modelSupportsVision: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val engineState: EngineState = EngineState.Idle,
    val errorMessage: String? = null,
    val noModelDownloaded: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val filesystemToolEnabled: Boolean = false,
    val pendingConfirm: ToolConfirmPrompt? = null,
    val pendingImagePath: String? = null,
    val isIncognito: Boolean = false,
    val title: String = "",
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    // Legacy flag; always false now (header binds to effectiveLastUsedModelIdFlow).
    val modelUnavailable: Boolean = false,
    // One-shot picker-open request; screen clears it via consumeOpenPicker after showing.
    val openPickerRequest: Boolean = false,
    val isDictating: Boolean = false,
) {
    val isGenerating: Boolean get() = engineState == EngineState.Generating
    val isWarming: Boolean get() = engineState == EngineState.Warming
    val composerBusy: Boolean get() = engineState != EngineState.Idle
}

internal fun List<AideMessage>.toUiList(idFor: (AideMessage, Int) -> Long): List<ChatMessage> {
    val responsesByCallId: Map<String, AidePart.ToolResponse> = buildMap {
        for (msg in this@toUiList) {
            if (msg.role != AideRole.Tool) continue
            for (part in msg.parts) {
                val resp = part as? AidePart.ToolResponse ?: continue
                val key = resp.callId ?: continue
                put(key, resp)
            }
        }
    }

    val out = mutableListOf<ChatMessage>()
    forEachIndexed { index, msg ->
        val baseId = idFor(msg, index)
        when (msg.role) {
            AideRole.User -> {
                val image = msg.parts.firstNotNullOfOrNull { (it as? AidePart.ImageFile)?.path }
                out += ChatMessage.User(
                    id = baseId,
                    text = msg.textContent,
                    imagePath = image,
                )
            }
            AideRole.Model -> {
                val text = msg.textContent
                val calls = msg.parts.filterIsInstance<AidePart.ToolCall>()
                val thinking = msg.parts.filterIsInstance<AidePart.Thinking>().firstOrNull()
                // Order matches model's emit sequence: thought → tool calls → answer.
                if (thinking != null) {
                    out += ChatMessage.Thinking(
                        // Negative offset distinguishes synthesized row from parent assistant id.
                        id = -baseId * 10L - 1L,
                        text = thinking.text,
                        durationMs = thinking.durationMs,
                        isStreaming = false,
                    )
                }
                calls.forEachIndexed { callIndex, call ->
                    val resp = responsesByCallId[call.callId]
                    out += ChatMessage.ToolInvocation(
                        // Derive a stable id distinct from the assistant row's; +callIndex
                        // ensures multiple chips on one turn each get their own key.
                        id = baseId * 1000L + (callIndex.toLong() + 1L),
                        callId = call.callId,
                        toolName = call.name,
                        argsJson = call.argsJson,
                        resultJson = resp?.json,
                        error = resp?.error,
                        isRunning = resp == null,
                    )
                }
                if (text.isNotEmpty() || (calls.isEmpty() && thinking == null)) {
                    out += ChatMessage.Assistant(id = baseId, text = text)
                }
            }
            AideRole.Tool, AideRole.System -> {
            }
        }
    }
    return out
}

internal fun List<MessageEntity>.toChatMessages(): List<ChatMessage> {
    val aide = map { it.toAideMessage() }
    return aide.toUiList { _, index -> this[index].id }
}

internal fun List<InMemoryChatTranscript.Entry>.toChatMessagesFromEntries(): List<ChatMessage> {
    val aide = map { it.message }
    return aide.toUiList { _, index -> this[index].id }
}
