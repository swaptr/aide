package com.sabreware.aide.core.domain.usecase

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.chat.MessageStats
import kotlinx.coroutines.channels.ProducerScope
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.applyingSampler
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelPrefs
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.SamplerOverridesStore
import com.sabreware.aide.core.domain.model.accepts
import com.sabreware.aide.core.domain.model.inputModalityOrNull
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResolver
import com.sabreware.aide.core.domain.tools.ToolBundleFactory
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolGate
import com.sabreware.aide.core.domain.tools.ToolPrefs
import com.sabreware.aide.core.domain.tools.enabledToolCategories
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import com.sabreware.aide.core.domain.util.AideLog
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

class SendChatMessageUseCase(
    private val chats: ChatRepository,
    private val transcriptFactory: ChatTranscriptFactory,
    private val engine: LlmEngineRepository,
    private val storage: ModelStorage,
    private val registry: ModelRegistryRepository,
    private val webSearchResolver: WebSearchResolver,
    private val writeGate: WriteConfirmGate,
    private val toolBundleFactory: ToolBundleFactory,
    private val contactPickGate: ContactPickGate,
    private val toolDispatcher: ToolDispatcher,
    private val userPrefs: PreferenceStore,
    private val samplerOverrides: SamplerOverridesStore,
    private val acquireModel: AcquireModelUseCase,
) {

    sealed interface Event {
        data object Warming : Event
        data class ToolCallStarted(
            val assistantMessageId: Long,
            val callId: String,
            val name: String,
            val argsJson: String,
        ) : Event
        data class ToolCallCompleted(
            val assistantMessageId: Long,
            val callId: String,
            val name: String,
            val resultJson: String,
            val error: String?,
        ) : Event
        data class ToolNeedsConfirm(
            val opId: String,
            val toolName: String,
            val summary: String,
            val details: List<WriteConfirmGate.KeyValue> = emptyList(),
            val severity: WriteConfirmGate.Severity = WriteConfirmGate.Severity.WARN,
        ) : Event
        data class ContactPickRequested(val opId: String) : Event
        // text is cumulative (for the chat streaming patch); delta is the real incremental token(s) for
        // transforming consumers (voice TTS) so they never reconstruct it by string-stripping (B6).
        /**
         * One chunk of the answer, as a **delta**.
         *
         * It used to carry the accumulated text as well, rebuilt with `builder.toString()` on every token:
         * a 4k-character reply copied several megabytes of String on the way out, and the field was
         * redundant because the consumer has to accumulate anyway. An empty [delta] is the "an assistant row
         * exists, start rendering into it" signal that opens a turn.
         */
        data class Streaming(val assistantMessageId: Long, val delta: String) : Event
        data class Thinking(
            val assistantMessageId: Long,
            val text: String,
            val durationMs: Long,
            val isStreaming: Boolean,
        ) : Event
        data class Done(
            val assistantMessageId: Long,
            val finalText: String,
        ) : Event
        data class Error(val message: String) : Event
    }

    interface SessionHolder {
        var session: ChatSession?
        var sessionModelId: String?
        var sessionEnabledGated: Set<ToolGate>
        var sessionWebSearchProviderId: WebSearchProviderId?
        // ConversationConfig.tools is captured at construction and can't be mutated —
        // rebind on category-toggle change.
        var sessionEnabledCategories: Set<ToolCategory>
    }

    fun resolveToolConfirm(opId: String, accepted: Boolean, remember: Boolean = false) {
        writeGate.resolve(opId, accepted, remember)
    }

    fun resolveContactPick(opId: String, result: ContactPickGate.ContactPickResult?) {
        contactPickGate.resolve(opId, result)
    }

    // Resolve any pending tool-confirm await so a suspended handler doesn't wait out the full
    // timeout holding refs to a closed session (the gate await suspends now — no thread is parked —
    // but it still needs unblocking on teardown).
    fun cancelAllToolConfirms() {
        writeGate.cancelAll()
        contactPickGate.cancelAll()
    }

    fun invoke(
        chatId: String,
        modelId: String,
        userParts: List<AidePart>,
        holder: SessionHolder,
        enabledGated: Set<ToolGate> = emptySet(),
        surface: Surface = Surface.CHAT,
        /** False for a rerouted turn: a stand-in model must never become the user's recorded choice. */
        recordUsage: Boolean = true,
    ): Flow<Event> = channelFlow {
        if (chats.getChat(chatId) == null) {
            send(Event.Error("Chat not found")); return@channelFlow
        }
        invoke(
            transcript = transcriptFactory.createPersistent(chatId),
            modelId = modelId,
            userParts = userParts,
            holder = holder,
            enabledGated = enabledGated,
            surface = surface,
            recordUsage = recordUsage,
        ).collect { send(it) }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun invoke(
        transcript: ChatTranscript,
        modelId: String,
        userParts: List<AidePart>,
        holder: SessionHolder,
        enabledGated: Set<ToolGate> = emptySet(),
        surface: Surface = Surface.CHAT,
        /** False for a rerouted turn: a stand-in model must never become the user's recorded choice. */
        recordUsage: Boolean = true,
    ): Flow<Event> {
        var llmHandle: ResidencyHandle? = null
        return channelFlow {
        val found = registry.findSpec(modelId) ?: run {
            send(Event.Error("Model '$modelId' not in catalog")); return@channelFlow
        }
        // The single narrowing from "a model" to "a model that can hold a conversation". Everything
        // downstream — capabilities, sampler defaults, the engine — is chat-typed, so the check happens
        // once, here, instead of each of them accepting a spec it cannot serve.
        val spec = found as? ChatModelSpec ?: run {
            send(Event.Error("Model '${found.displayName}' is a ${found.modality.value} model, not a chat model"))
            return@channelFlow
        }
        if (spec.requiresDownload && !storage.isDownloaded(spec)) {
            send(Event.Error("Model '${spec.displayName}' not downloaded. Open Models to download."))
            return@channelFlow
        }

        val priorMessages = transcript.priorMessages()
        // Belt-and-braces capability drop: programmatic callers can bypass the UI's attach gate. The
        // part→capability rule lives ONCE in [InputModality]. Filter preserves order (Gemma's template
        // expects media parts ahead of the Text part referring to them).
        val effectiveParts = userParts.filter { part ->
            val modality = part.inputModalityOrNull() ?: return@filter true
            spec.capabilities.accepts(modality).also {
                if (!it) AideLog.w("AideSend", "$modality dropped: ${spec.id} lacks the capability")
            }
        }
        val userText = effectiveParts.filterIsInstance<AidePart.Text>().joinToString(separator = "") { it.text }
        val userMessage = AideMessage(AideRole.User, effectiveParts)
        transcript.appendUserMessage(userMessage)
        if (priorMessages.isEmpty()) {
            transcript.onFirstUserTurn(userText)
        }

        // The user's master reasoning switch overrides the model capability: off → no thinking request.
        val reasoningEnabled = userPrefs.flow(ModelPrefs.ReasoningEnabled).first()
        val thinkingRequest = when {
            !reasoningEnabled -> ChatGenerationConfig.ThinkingRequest.Off
            spec.capabilities.thinking is ChatCapabilities.ThinkingMode.Toggle ->
                ChatGenerationConfig.ThinkingRequest.On
            spec.capabilities.thinking is ChatCapabilities.ThinkingMode.Levels ->
                ChatGenerationConfig.ThinkingRequest.Level("high")
            else -> ChatGenerationConfig.ThinkingRequest.Off
        }
        // One resolved sampler for every provider: hardcoded base ← model allowlist default ← user
        // per-model override. Local LiteRT reads this same resolved config now too.
        val samplerOverride = samplerOverrides.current().byModel[modelId]
        val turnConfig = ChatGenerationConfig(
            backend = spec.defaultBackend,
            thinking = thinkingRequest,
        ).applyingSampler(spec.defaultConfig).applyingSampler(samplerOverride)

        val sendStartMs = Clock.System.now().toEpochMilliseconds()
        AideLog.i(
            "AidePerf",
            "send.start model=${spec.id} provider=${spec.provider} " +
                "priorMsgs=${priorMessages.size} userChars=${userText.length} " +
                "parts=${effectiveParts.size} think=$thinkingRequest surface=$surface",
        )
        // Acquire the model for the whole turn (Phase 5) so it can't be unloaded mid-request; released
        // with a keepAlive in onCompletion. Skip the Warming toast when it's already resident.
        val warmStartMs = Clock.System.now().toEpochMilliseconds()
        if (engine.loadedModelId != spec.id) send(Event.Warming)
        llmHandle = acquireModel(spec, turnConfig)
        AideLog.i(
            "AidePerf",
            "send.warm model=${spec.id} ms=${Clock.System.now().toEpochMilliseconds() - warmStartMs}",
        )

        // Per-turn snapshot — consistent for the whole turn even if user toggles mid-stream.
        val enabledCats = userPrefs.enabledToolCategories().first()
        val askBeforeEach = userPrefs.flow(ToolPrefs.AskBeforeEachTool).first()

        val effectiveGated = if (spec.capabilities.toolsLocal) enabledGated else emptySet()
        AideLog.i(
            "AideSend",
            "send: model=${spec.id} toolsLocal=${spec.capabilities.toolsLocal} " +
                "userGated=$enabledGated effectiveGated=$effectiveGated " +
                "sessionGated=${holder.sessionEnabledGated}",
        )
        val resolvedWebSearch = if (ToolGate.WEB_SEARCH in effectiveGated) {
            webSearchResolver.resolve()
        } else null
        val resolvedWebSearchId = resolvedWebSearch?.id

        if (holder.sessionModelId != spec.id ||
            holder.session == null ||
            holder.sessionEnabledGated != effectiveGated ||
            holder.sessionWebSearchProviderId != resolvedWebSearchId ||
            holder.sessionEnabledCategories != enabledCats
        ) {
            runCatching { holder.session?.close() }
            // The web toolset resolves its own provider now, so the bundle needs only the gate.
            val bundle = toolBundleFactory.build(
                providerId = spec.provider,
                supportsTools = spec.capabilities.toolsLocal,
                enabledGated = effectiveGated,
                enabledCategories = enabledCats,
                surface = surface,
            )
            val rebindStartMs = Clock.System.now().toEpochMilliseconds()
            AideLog.i(
                "AideSend",
                "rebinding session, tools=${bundle.tools.size} " +
                    "webSearchProvider=${resolvedWebSearchId ?: "n/a"}",
            )
            holder.session = engine.newChatSession(
                spec = spec,
                initialMessages = priorMessages,
                tools = bundle.tools,
                systemInstruction = bundle.systemPrompt,
                config = turnConfig,
                dispatcher = toolDispatcher,
                activationState = bundle.activationState,
            )
            AideLog.i(
                "AidePerf",
                "send.rebind ms=${Clock.System.now().toEpochMilliseconds() - rebindStartMs} " +
                    "tools=${bundle.tools.size} sysPromptChars=${bundle.systemPrompt?.length ?: 0}",
            )
            holder.sessionModelId = spec.id
            holder.sessionEnabledGated = effectiveGated
            holder.sessionWebSearchProviderId = resolvedWebSearchId
            holder.sessionEnabledCategories = enabledCats
        }

        // The turn id is minted before the gate bindings so both register under it: a prompt raised by
        // THIS turn's tools then routes to this collector even when another surface bound more recently.
        val turnId = Uuid.random().toString()
        val writeGateBinding = writeGate.bindEmitter(ownerId = turnId) { prompt ->
            trySend(
                Event.ToolNeedsConfirm(
                    opId = prompt.opId,
                    toolName = prompt.toolName,
                    summary = prompt.summary,
                    details = prompt.details,
                    severity = prompt.severity,
                ),
            )
        }
        val contactPickBinding = contactPickGate.bindEmitter(ownerId = turnId) { opId ->
            trySend(Event.ContactPickRequested(opId))
        }

        val assistantId = transcript.appendAssistantPlaceholder()
        send(Event.Streaming(assistantId, ""))
        toolDispatcher.resetTurn(turnId)
        val dispatchContext = ToolDispatcher.Context(
            surface = surface,
            modelId = spec.id,
            turnId = turnId,
            askBeforeEachTool = askBeforeEach && surface == Surface.CHAT,
        )

        val builder = StringBuilder()
        val toolCallParts = mutableListOf<AidePart.ToolCall>()
        // Reasoning is persisted as one part PER BLOCK, in arrival order, never as one concatenation —
        // see [ReasoningAccumulator] for why a merged turn fails verification on the first replay.
        val reasoning = ReasoningAccumulator()
        suspend fun persistAssistant(
            currentText: String,
        ) {
            transcript.updateAssistantMessage(
                assistantId,
                AideMessage(
                    role = AideRole.Model,
                    parts = buildList {
                        addAll(reasoning.parts())
                        if (currentText.isNotEmpty()) add(AidePart.Text(currentText))
                        addAll(toolCallParts)
                    },
                ),
            )
        }
        var realError: Throwable? = null
        // Kept separate from [realError]: a cancelled turn is finalised the same way but must not be
        // reported as a failure, and the exception has to be rethrown once the writing is done.
        var cancellation: CancellationException? = null
        // The stream closed without the protocol's end marker (StopReason.Interrupted): nothing threw,
        // but the reply may be truncated — reported next to the kept partial text, like realError below.
        var interrupted = false
        var firstEventLogged = false
        var toolRoundCount = 0
        var firstTokenMs = 0L
        var turnUsage: ChatStreamEvent.Usage? = null
        try {
            holder.session!!.send(userMessage, dispatchContext).collect { event ->
                if (!firstEventLogged) {
                    AideLog.i(
                        "AidePerf",
                        "send.firstEvent ms=${Clock.System.now().toEpochMilliseconds() - sendStartMs} " +
                            "kind=${event::class.simpleName}",
                    )
                    firstEventLogged = true
                }
                when (event) {
                    is ChatStreamEvent.TextDelta -> {
                        if (firstTokenMs == 0L) firstTokenMs = Clock.System.now().toEpochMilliseconds()
                        // LiteRT-LM 0.11 and Ollama both emit incremental deltas, not cumulative.
                        if (reasoning.hasOpenBlock) {
                            reasoning.closeBlock()
                            send(
                                Event.Thinking(
                                    assistantMessageId = assistantId,
                                    text = reasoning.displayText(),
                                    durationMs = reasoning.durationMs,
                                    isStreaming = false,
                                ),
                            )
                            runCatching { persistAssistant(builder.toString()) }
                        }
                        builder.append(event.text)
                        send(Event.Streaming(assistantId, event.text))
                    }
                    is ChatStreamEvent.ToolCallStarted -> {
                        toolRoundCount++
                        val argsString = event.args.toString()
                        toolCallParts += AidePart.ToolCall(
                            callId = event.callId,
                            name = event.name,
                            argsJson = argsString,
                            // Gemini signs the call rather than the thought, and rejects a replayed call
                            // that arrives without its thought_signature.
                            providerMetadata = event.providerMetadata,
                        )
                        if (reasoning.hasOpenBlock) {
                            reasoning.closeBlock()
                            send(
                                Event.Thinking(
                                    assistantMessageId = assistantId,
                                    text = reasoning.displayText(),
                                    durationMs = reasoning.durationMs,
                                    isStreaming = false,
                                ),
                            )
                        }
                        // Persist so reopen mid-tool-call surfaces a running chip.
                        runCatching { persistAssistant(builder.toString()) }
                        send(
                            Event.ToolCallStarted(
                                assistantMessageId = assistantId,
                                callId = event.callId,
                                name = event.name,
                                argsJson = argsString,
                            ),
                        )
                    }
                    is ChatStreamEvent.ToolCallCompleted -> {
                        runCatching {
                            transcript.appendToolResponse(
                                AidePart.ToolResponse(
                                    name = event.name,
                                    json = event.resultJson,
                                    callId = event.callId,
                                    error = event.error,
                                ),
                            )
                        }
                        send(
                            Event.ToolCallCompleted(
                                assistantMessageId = assistantId,
                                callId = event.callId,
                                name = event.name,
                                resultJson = event.resultJson,
                                error = event.error,
                            ),
                        )
                    }
                    is ChatStreamEvent.ThinkingDelta -> {
                        if (firstTokenMs == 0L) firstTokenMs = Clock.System.now().toEpochMilliseconds()
                        reasoning.append(event.text)
                        event.providerMetadata?.let(reasoning::applyPayload)
                        send(
                            Event.Thinking(
                                assistantMessageId = assistantId,
                                text = reasoning.displayText(),
                                durationMs = reasoning.durationMs,
                                isStreaming = true,
                            ),
                        )
                    }
                    is ChatStreamEvent.Completed -> {
                        turnUsage = event.usage
                        interrupted = event.stopReason == ChatStreamEvent.StopReason.Interrupted
                        if (event.warnings.isNotEmpty()) {
                            AideLog.w(
                                "AideSend",
                                "model warnings model=${spec.id}: " +
                                    event.warnings.joinToString("; "),
                            )
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            // Cancellation is NOT an error, and turning it into one lost the reply. The blanket
            // `catch (t: Throwable)` below used to swallow it into `realError`, and the finalisation that
            // follows is a run of suspend calls that rethrow immediately on a cancelled job — so the
            // assistant row stayed the empty placeholder it was created as, and the chat reopened with a
            // blank bubble even though the user had watched most of an answer arrive.
            cancellation = ce
        } catch (t: Throwable) {
            realError = t
            AideLog.w(
                "AidePerf",
                "send.error model=${spec.id} " +
                    "ms=${Clock.System.now().toEpochMilliseconds() - sendStartMs} " +
                    "${t::class.simpleName}: ${t.message}",
                t,
            )
        } finally {
            // Detach THIS turn's emitters — not whatever happens to be bound. The use case is a process
            // singleton shared by chat, the voice loop and the assistant controller, so an unconditional
            // unbind here silenced a concurrent turn's dialog and left its tool waiting out the timeout.
            writeGateBinding.unbind()
            contactPickBinding.unbind()
            toolDispatcher.forgetTurn(turnId)
        }

        val finalText = when {
            realError != null && builder.isEmpty() ->
                "Error: ${realError.message ?: realError::class.simpleName}"
            else -> builder.toString()
        }
        // Everything below writes the turn's result. Under NonCancellable so it also runs for a cancelled
        // turn — see the CancellationException branch above.
        withContext(NonCancellable) {
        if (reasoning.hasOpenBlock) {
            reasoning.closeBlock()
            send(
                Event.Thinking(
                    assistantMessageId = assistantId,
                    text = reasoning.displayText(),
                    durationMs = reasoning.durationMs,
                    isStreaming = false,
                ),
            )
        }
        transcript.updateAssistantMessage(
            assistantId,
            AideMessage(
                role = AideRole.Model,
                parts = buildList {
                    addAll(reasoning.parts())
                    if (finalText.isNotEmpty()) add(AidePart.Text(finalText))
                    addAll(toolCallParts)
                },
            ),
        )
        if (recordUsage && realError == null && builder.isNotEmpty()) {
            runCatching { registry.recordUsed(spec) }
        }
        AideLog.i(
            "AidePerf",
            "send.done model=${spec.id} " +
                "totalMs=${Clock.System.now().toEpochMilliseconds() - sendStartMs} " +
                "answerChars=${builder.length} thinkingChars=${reasoning.displayText().length} " +
                "thinkingMs=${reasoning.durationMs} toolRounds=$toolRoundCount " +
                "error=${realError?.let { it::class.simpleName } ?: "none"}",
        )
        transcript.updateAssistantStats(assistantId, turnStats(sendStartMs, firstTokenMs, turnUsage))
        emitCompletion(assistantId, finalText, realError, interrupted, hadText = builder.isNotEmpty())
        }
        cancellation?.let { throw it }
        }.onCompletion { llmHandle?.release(ResidencyDurations.CHAT_KEEPALIVE_MS) }
    }

    private fun turnStats(
        sendStartMs: Long,
        firstTokenMs: Long,
        usage: ChatStreamEvent.Usage?,
    ): MessageStats {
        val totalMs = Clock.System.now().toEpochMilliseconds() - sendStartMs
        val ttftMs = if (firstTokenMs != 0L) firstTokenMs - sendStartMs else null
        val outTok = usage?.outputTokens
        // tok/s over the GENERATION window (first token → done), excluding prefill latency.
        val genMs = if (firstTokenMs != 0L) Clock.System.now().toEpochMilliseconds() - firstTokenMs else totalMs
        val tps = if (outTok != null && outTok > 0 && genMs > 0) {
            outTok.toDouble() * 1000.0 / genMs
        } else {
            null
        }
        return MessageStats(ttftMs, totalMs, tps, usage?.inputTokens, outTok)
    }

    /**
     * The turn's closing events: Done, then — when [hadText] — the failure that truncated it, if any.
     * A stream that DIED mid-answer is not a finished answer: the partial text is kept (the user watched
     * it arrive) and the failure is reported alongside it, whether it was a throw ([realError]) or a
     * no-throw drop ([interrupted] — the socket closed cleanly but the provider never marked the round
     * finished). Every send is runCatching because on a cancelled turn the channel is already closed, and
     * the transcript write that precedes this is the part that had to happen. (Connection-level retry
     * stays off for generation streams: a replay would restart the answer.)
     */
    private suspend fun ProducerScope<Event>.emitCompletion(
        assistantId: Long,
        finalText: String,
        realError: Throwable?,
        interrupted: Boolean,
        hadText: Boolean,
    ) {
        runCatching { send(Event.Done(assistantId, finalText)) }
        if (!hadText) return
        when {
            realError != null -> runCatching {
                send(Event.Error(realError.message ?: realError::class.simpleName ?: "The response was cut short"))
            }
            interrupted -> runCatching {
                send(Event.Error("The connection closed early. The reply may be incomplete."))
            }
        }
    }
}
