package com.sabreware.aide.ui.chat

import com.sabreware.aide.core.common.media.PendingFileAttachment
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.InputModality
import com.sabreware.aide.core.domain.model.ProviderId

sealed interface ChatMessage {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        val imagePath: String?,
        val audioPath: String? = null,
        /** Filename of an attached native document (PDF) — rendered as a chip above the text. */
        val documentName: String? = null,
    ) : ChatMessage

    data class Assistant(
        override val id: Long,
        val text: String,
        val isStreaming: Boolean = false,
        val stats: MessageStats? = null,
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
    val details: List<com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate.KeyValue> = emptyList(),
    val severity: com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate.Severity =
        com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate.Severity.WARN,
)

/**
 * How much the chat knows about its model — the states the header actually has, where it used to have a
 * boolean.
 *
 * A boolean could only say "the registry emitted something", and the moment it flipped, every consumer
 * committed: the pill stopped shimmering AND the empty state offered "Set up a model to begin". So a
 * snapshot that arrived before the providers had answered did not read as early, it read as an answer —
 * which is how a configured cloud model got a call-to-action drawn over it on every cold start.
 *
 * Splitting [Cached] out is what lets the two decisions differ. Drawing a name is safe from the user's
 * recorded choice ([com.sabreware.aide.core.domain.model.ModelCard]); ACTING on one is not, because the
 * spec behind it may be gone. So [Cached] paints the pill and still holds the composer, and only [Resolved]
 * — the settled registry snapshot — enables sending.
 *
 * [NoneSelected] is the other half of reading the choice document: "the user has not picked a model" is a
 * settled fact the moment the document is read (nothing ever picks on the user's behalf), so it may offer
 * the call to action on the first frame instead of waiting seconds for providers it has nothing to do with.
 */
enum class ModelResolution {
    /** The choice document has not been read yet (a few ms at process start): skeleton, no call to action. */
    Unresolved,

    /** The user's chosen model, by name. Paint it; do not act on it until [Resolved]. */
    Cached,

    /** The user has not chosen a model. Settled — "No model" and the call to action, from the first frame. */
    NoneSelected,

    /** The registry has settled on the chosen model (or found it unusable — see `unavailableModel`). */
    Resolved,
}

data class ChatUiState(
    val chatId: String = "",
    /** Whether the chat's row is written — false for a new chat until its first send (and always in incognito). */
    val isSaved: Boolean = false,
    val currentModelId: String = "",
    val modelDisplayName: String = "",
    val modelProvider: com.sabreware.aide.core.domain.model.ProviderId? = null,
    val modelSupportsTools: Boolean = false,
    val modelSupportsVision: Boolean = false,
    /** The chosen model can think before answering — the Thinking switch is offered only then. */
    val modelSupportsThinking: Boolean = false,
    /** Whether the PLATFORM has a camera — distinct from whether the MODEL accepts images. */
    val cameraAvailable: Boolean = false,
    val modelSupportsAudio: Boolean = false,
    val modelSupportsDocuments: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    /**
     * Whether [messages] is an answer yet. An existing chat's rows land a query later, and an empty list
     * before then is not "an empty chat" — drawing the greeting for it flashed the hero over every open.
     */
    val messagesLoaded: Boolean = true,
    /** Older turns exist above the loaded window — the list loads them when scrolled to its top. */
    val hasOlder: Boolean = false,
    /** The window was scrolled away from the live tail — newer turns exist below it. */
    val hasNewer: Boolean = false,
    val engineState: EngineState = EngineState.Idle,
    val errorMessage: String? = null,
    /**
     * The name of the model the user chose when the settled registry cannot use it (weights deleted,
     * provider deconfigured or offline). Said out loud rather than silently replaced by another model.
     */
    val unavailableModel: String? = null,
    /**
     * Set while [currentModelId] is a stand-in: the chosen model (this name) is unavailable and the user's
     * reroute setting allowed another. Drawn as a standing notice — never silent.
     */
    val reroutedFrom: String? = null,
    /** The chosen model id the header resolved for; the send guard compares the live choice against it. */
    val resolvedChoiceId: String = "",
    /** See [ModelResolution]: skeleton → the recorded choice → the settled registry answer. */
    val modelResolution: ModelResolution = ModelResolution.Unresolved,
    val webSearchEnabled: Boolean = false,
    val pendingConfirm: ToolConfirmPrompt? = null,
    val pendingImagePath: String? = null,
    val pendingAudioPath: String? = null,
    /** A picked file staged for the next turn (PDF, or a text file inlined at send). */
    val pendingFile: PendingFileAttachment? = null,
    val isIncognito: Boolean = false,
    val title: String = "",
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    // One-shot picker-open request; screen clears it via consumeOpenPicker after showing.
    val openPickerRequest: Boolean = false,
    val isDictating: Boolean = false,
    // Non-null while the user is editing a prior user turn (its row id). Sending restarts the
    // conversation from that turn; the composer shows an editing pill and the chat hides later turns.
    val editingMessageId: Long? = null,
) {
    val isGenerating: Boolean get() = engineState == EngineState.Generating
    val isWarming: Boolean get() = engineState == EngineState.Warming
    val composerBusy: Boolean get() = engineState != EngineState.Idle

    /** A usable model is selected. Composer actions (attach, dictate, clip, send) are dead without
     *  one — there's nothing to send to. Distinct from the per-capability flags, which gate features
     *  on a model that IS loaded.
     *
     *  Gated on [ModelResolution.Resolved] because a cached NAME is not a resolvable spec: sending against
     *  one before the registry settles fails at `findSpec`, so the composer waits the extra beat the pill
     *  no longer has to. */
    val hasModel: Boolean get() = modelResolution == ModelResolution.Resolved &&
        currentModelId.isNotBlank()

    /** Something to put in the pill — a settled model, or the recorded choice's name. */
    val hasModelName: Boolean get() = modelDisplayName.isNotBlank()

    /** The pill has a definite thing to say — a name, or a settled "No model". Otherwise it shimmers. */
    val headerKnown: Boolean get() =
        modelResolution == ModelResolution.Resolved || modelResolution == ModelResolution.NoneSelected ||
            hasModelName

    /** Offer "Choose a model": nothing is chosen, or the chosen model cannot be used. Never while unknown. */
    val promptsForModel: Boolean get() = when (modelResolution) {
        ModelResolution.NoneSelected -> true
        ModelResolution.Resolved -> !hasModel
        ModelResolution.Unresolved, ModelResolution.Cached -> false
    }

    /**
     * Paints the user's recorded choice without claiming it is resolved — see [ModelResolution].
     *
     * A no-op once [ModelResolution.Resolved]: the settled snapshot outranks the document, including when it
     * says the model is gone, so a late emission can never resurrect a name the registry just ruled out.
     */
    fun seededWith(selection: DocState<ModelSelection>): ChatUiState {
        if (modelResolution == ModelResolution.Resolved) return this
        val chosen = (selection as? DocState.Ready)?.value ?: return this
        val card = chosen.lastUsedCard
        return when {
            chosen.lastUsedModelId == null -> copy(
                modelResolution = ModelResolution.NoneSelected,
                modelDisplayName = "",
                modelProvider = null,
            )
            card != null -> copy(
                modelResolution = ModelResolution.Cached,
                modelDisplayName = card.displayName,
                modelProvider = card.providerId,
            )
            // Chosen, but never drawn by this build (no card): wait for the registry rather than guess a name.
            else -> this
        }
    }

    /** The [InputModality] gate over this state's flattened capability booleans (mirrors
     *  [com.sabreware.aide.core.domain.model.accepts]; the booleans are copied from ChatCapabilities in one
     *  place — the header sync). */
    fun accepts(modality: com.sabreware.aide.core.domain.model.InputModality): Boolean = when (modality) {
        com.sabreware.aide.core.domain.model.InputModality.Image -> modelSupportsVision
        com.sabreware.aide.core.domain.model.InputModality.Audio -> modelSupportsAudio
        com.sabreware.aide.core.domain.model.InputModality.Document -> modelSupportsDocuments
    }
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
                val audio = msg.parts.firstNotNullOfOrNull { (it as? AidePart.AudioFile)?.path }
                val document = msg.parts.firstNotNullOfOrNull { (it as? AidePart.DocumentFile)?.name }
                out += ChatMessage.User(
                    id = baseId,
                    text = msg.textContent,
                    imagePath = image,
                    audioPath = audio,
                    documentName = document,
                )
            }
            AideRole.Model -> {
                val text = msg.textContent
                val calls = msg.parts.filterIsInstance<AidePart.ToolCall>()
                // A turn may carry several thinking blocks (signature-closed segments); merge for display.
                // RedactedThinking has no displayable text and is replay-only — ignored here.
                val thinkingAll = msg.parts.filterIsInstance<AidePart.Thinking>().filter { it.text.isNotEmpty() }
                // Order matches model's emit sequence: thought → tool calls → answer.
                if (thinkingAll.isNotEmpty()) {
                    out += ChatMessage.Thinking(
                        // Negative offset distinguishes synthesized row from parent assistant id.
                        id = -baseId * 10L - 1L,
                        text = thinkingAll.joinToString("\n\n") { it.text },
                        durationMs = thinkingAll.sumOf { it.durationMs },
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
                if (text.isNotEmpty() || (calls.isEmpty() && thinkingAll.isEmpty())) {
                    out += ChatMessage.Assistant(id = baseId, text = text)
                }
            }
            AideRole.Tool, AideRole.System -> {
            }
        }
    }
    return out
}

/** The row's kind, for the list's `contentType`: a scrolled-off row's composition is reused only by its own kind. */
internal val ChatMessage.contentType: Int
    get() = when (this) {
        is ChatMessage.User -> 0
        is ChatMessage.Assistant -> 1
        is ChatMessage.ToolInvocation -> 2
        is ChatMessage.Thinking -> 3
    }

internal fun List<StoredMessage>.toChatMessages(): List<ChatMessage> {
    val statsById = associate { it.id to it.stats }
    val aide = map { it.message }
    return aide.toUiList { _, index -> this[index].id }.map { msg ->
        if (msg is ChatMessage.Assistant) statsById[msg.id]?.let { msg.copy(stats = it) } ?: msg
        else msg
    }
}
