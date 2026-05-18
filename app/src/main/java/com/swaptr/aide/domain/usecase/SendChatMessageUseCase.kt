package com.swaptr.aide.domain.usecase

import android.util.Log
import com.swaptr.aide.data.chat.AideMessage
import com.swaptr.aide.data.chat.AidePart
import com.swaptr.aide.data.chat.AideRole
import com.swaptr.aide.data.chat.ChatRepository
import com.swaptr.aide.data.chat.ChatTranscript
import com.swaptr.aide.data.chat.PersistentChatTranscript
import com.swaptr.aide.data.model.LlmEngineRepository
import com.swaptr.aide.data.model.ModelDownloadRepository
import com.swaptr.aide.data.model.ModelRegistryRepository
import com.swaptr.aide.data.prefs.ToolCategory
import com.swaptr.aide.data.prefs.UserPreferencesRepository
import com.swaptr.aide.data.search.DuckDuckGoSearchClient
import com.swaptr.aide.domain.llm.ChatSession
import com.swaptr.aide.domain.llm.ChatStreamEvent
import com.swaptr.aide.domain.llm.GenerationConfig
import com.swaptr.aide.domain.llm.Surface
import com.swaptr.aide.domain.llm.dispatch.ToolDispatcher
import com.swaptr.aide.domain.search.ProviderChain
import com.swaptr.aide.domain.search.WebSearchProviderId
import com.swaptr.aide.domain.search.WebSearchResolver
import com.swaptr.aide.domain.tools.AideToolRegistry
import com.swaptr.aide.domain.tools.ClockToolset
import com.swaptr.aide.domain.tools.PhoneToolset
import com.swaptr.aide.domain.tools.WebFetchToolset
import com.swaptr.aide.domain.tools.WebSearchToolset
import com.swaptr.aide.domain.tools.calendar.CalendarToolset
import com.swaptr.aide.domain.tools.clipboard.ClipboardToolset
import com.swaptr.aide.domain.tools.contacts.ContactsToolset
import com.swaptr.aide.domain.llm.gates.WriteConfirmGate
import com.swaptr.aide.domain.tools.fs.FileSystemRoots
import com.swaptr.aide.domain.tools.fs.FileSystemToolset
import com.swaptr.aide.domain.tools.phone.ContactPickGate
import com.swaptr.aide.intent.IntentDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SendChatMessageUseCase @Inject constructor(
    private val chats: ChatRepository,
    private val engine: LlmEngineRepository,
    private val downloads: ModelDownloadRepository,
    private val registry: ModelRegistryRepository,
    private val search: DuckDuckGoSearchClient,
    private val webSearchResolver: WebSearchResolver,
    private val providerChain: ProviderChain,
    private val fsRoots: FileSystemRoots,
    private val writeGate: WriteConfirmGate,
    private val clockToolset: ClockToolset,
    private val phoneToolset: PhoneToolset,
    private val calendarToolset: CalendarToolset,
    private val contactsToolset: ContactsToolset,
    private val clipboardToolset: ClipboardToolset,
    private val contactPickGate: ContactPickGate,
    private val intentDispatchers: IntentDispatchers,
    private val toolDispatcher: ToolDispatcher,
    private val userPrefs: UserPreferencesRepository,
) {

    sealed interface Event {
        data object Warming : Event
        data class ToolRunning(val name: String, val arg: String) : Event
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
        data class Streaming(val assistantMessageId: Long, val text: String) : Event
        data class Thinking(
            val assistantMessageId: Long,
            val text: String,
            val durationMs: Long,
            val isStreaming: Boolean,
        ) : Event
        data class Done(val assistantMessageId: Long, val finalText: String) : Event
        data class Error(val message: String) : Event
    }

    interface SessionHolder {
        var session: ChatSession?
        var sessionModelId: String?
        var sessionEnabledGated: Set<AideToolRegistry.Gated>
        var sessionWebSearchProviderId: WebSearchProviderId?
        var sessionWebSearchToolset: WebSearchToolset?
        var sessionWebFetchToolset: WebFetchToolset?
        var sessionFileSystemToolset: FileSystemToolset?
        // ConversationConfig.tools is captured at construction and can't be mutated —
        // rebind on category-toggle change.
        var sessionEnabledCategories: Set<ToolCategory>
    }

    fun resolveToolConfirm(opId: String, accepted: Boolean) {
        writeGate.resolve(opId, accepted)
    }

    fun resolveContactPick(opId: String, result: ContactPickGate.ContactPickResult?) {
        contactPickGate.resolve(opId, result)
    }

    private fun summarizeArgs(args: kotlinx.serialization.json.JsonObject): String {
        if (args.isEmpty()) return ""
        for ((k, v) in args) {
            val prim = v as? kotlinx.serialization.json.JsonPrimitive ?: continue
            if (prim.isString) return prim.content.take(120)
            return "$k=${prim.content}".take(120)
        }
        return args.keys.first()
    }

    // Release blocked tool handlers so the LiteRT worker doesn't sit in runBlocking
    // for the full timeout holding refs to a closed session.
    fun cancelAllToolConfirms() {
        writeGate.cancelAll()
        contactPickGate.cancelAll()
    }

    fun invoke(
        chatId: String,
        modelId: String,
        userText: String,
        imagePath: String?,
        holder: SessionHolder,
        enabledGated: Set<AideToolRegistry.Gated> = emptySet(),
        surface: Surface = Surface.CHAT,
    ): Flow<Event> = channelFlow {
        if (chats.getChat(chatId) == null) {
            send(Event.Error("Chat not found")); return@channelFlow
        }
        invoke(
            transcript = PersistentChatTranscript(chats, chatId),
            modelId = modelId,
            userText = userText,
            imagePath = imagePath,
            holder = holder,
            enabledGated = enabledGated,
            surface = surface,
        ).collect { send(it) }
    }

    fun invoke(
        transcript: ChatTranscript,
        modelId: String,
        userText: String,
        imagePath: String?,
        holder: SessionHolder,
        enabledGated: Set<AideToolRegistry.Gated> = emptySet(),
        surface: Surface = Surface.CHAT,
    ): Flow<Event> = channelFlow {
        val spec = registry.findSpec(modelId) ?: run {
            send(Event.Error("Model '$modelId' not in catalog")); return@channelFlow
        }
        if (spec.requiresDownload && !downloads.isDownloaded(spec)) {
            send(Event.Error("Model '${spec.displayName}' not downloaded. Open Models to download."))
            return@channelFlow
        }

        val priorMessages = transcript.priorMessages()
        // Belt-and-braces drop: programmatic callers can bypass the UI's attach gate.
        val effectiveImagePath = if (imagePath != null && !spec.capabilities.visionIn) {
            Log.w("AideSend", "image attached but ${spec.id} visionIn=false; dropping")
            null
        } else imagePath
        // Gemma chat template expects ImageFile parts ahead of the Text part referring to them.
        val userMessage = AideMessage.user(userText, effectiveImagePath)
        transcript.appendUserMessage(userMessage)
        if (priorMessages.isEmpty()) {
            transcript.onFirstUserTurn(userText)
        }

        val thinkingRequest = when (spec.capabilities.thinking) {
            is com.swaptr.aide.data.catalog.CapabilitySet.ThinkingMode.None ->
                GenerationConfig.ThinkingRequest.Off
            is com.swaptr.aide.data.catalog.CapabilitySet.ThinkingMode.Toggle ->
                GenerationConfig.ThinkingRequest.On
            is com.swaptr.aide.data.catalog.CapabilitySet.ThinkingMode.Levels ->
                GenerationConfig.ThinkingRequest.Level("high")
        }
        val turnConfig = GenerationConfig(
            backend = spec.defaultBackend,
            thinking = thinkingRequest,
        )

        val sendStartNs = System.nanoTime()
        Log.i(
            "AidePerf",
            "send.start model=${spec.id} provider=${spec.provider} " +
                "priorMsgs=${priorMessages.size} userChars=${userText.length} " +
                "image=${effectiveImagePath != null} think=$thinkingRequest surface=$surface",
        )
        // Skip Warming when ensureLoaded() would no-op so UI doesn't toast on every send.
        if (engine.loadedModelId != spec.id) {
            send(Event.Warming)
            val warmStartNs = System.nanoTime()
            engine.ensureLoaded(spec, turnConfig)
            Log.i(
                "AidePerf",
                "send.warm model=${spec.id} ms=${(System.nanoTime() - warmStartNs) / 1_000_000}",
            )
        }

        // Per-turn snapshot — consistent for the whole turn even if user toggles mid-stream.
        val enabledCats = userPrefs.enabledToolCategoriesFlow.first()
        val askBeforeEach = userPrefs.askBeforeEachToolFlow.first()

        val effectiveGated = if (spec.capabilities.toolsLocal) enabledGated else emptySet()
        Log.i(
            "AideSend",
            "send: model=${spec.id} toolsLocal=${spec.capabilities.toolsLocal} " +
                "userGated=$enabledGated effectiveGated=$effectiveGated " +
                "sessionGated=${holder.sessionEnabledGated}",
        )
        val resolvedWebSearch = if (AideToolRegistry.Gated.WEB_SEARCH in effectiveGated) {
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
            val bundle = AideToolRegistry.build(
                providerId = spec.provider,
                supportsTools = spec.capabilities.toolsLocal,
                enabledGated = effectiveGated,
                enabledCategories = enabledCats,
                webSearch = resolvedWebSearch ?: webSearchResolver.resolve(),
                webSearchChain = providerChain,
                webSearchDisplayName = (resolvedWebSearch ?: webSearchResolver.resolve()).displayName,
                fetchClient = search,
                roots = fsRoots,
                confirmGate = writeGate,
                clockToolset = clockToolset,
                phoneToolset = phoneToolset,
                calendarToolset = calendarToolset,
                contactsToolset = contactsToolset,
                clipboardToolset = clipboardToolset,
                intentDispatchers = intentDispatchers,
                surface = surface,
            )
            val rebindStartNs = System.nanoTime()
            Log.i(
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
            Log.i(
                "AidePerf",
                "send.rebind ms=${(System.nanoTime() - rebindStartNs) / 1_000_000} " +
                    "tools=${bundle.tools.size} sysPromptChars=${bundle.systemPrompt?.length ?: 0}",
            )
            holder.sessionModelId = spec.id
            holder.sessionEnabledGated = effectiveGated
            holder.sessionWebSearchProviderId = resolvedWebSearchId
            holder.sessionWebSearchToolset = bundle.webSearchToolset
            holder.sessionWebFetchToolset = bundle.webFetchToolset
            holder.sessionFileSystemToolset = bundle.fileSystemToolset
            holder.sessionEnabledCategories = enabledCats
        }

        // Reassign per-send so a reused session emits into the current outbound flow.
        holder.sessionWebSearchToolset?.onSearchStarted = { q ->
            trySend(Event.ToolRunning("WebSearch", q))
        }
        holder.sessionWebFetchToolset?.onFetchStarted = { u ->
            trySend(Event.ToolRunning("WebFetch", u))
        }
        holder.sessionFileSystemToolset?.onToolStarted = { name, arg ->
            trySend(Event.ToolRunning(name, arg))
        }
        writeGate.bindEmitter { prompt ->
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
        contactPickGate.bindEmitter { opId ->
            trySend(Event.ContactPickRequested(opId))
        }

        val assistantId = transcript.appendAssistantPlaceholder()
        send(Event.Streaming(assistantId, ""))

        val turnId = java.util.UUID.randomUUID().toString()
        toolDispatcher.resetTurn(turnId)
        val dispatchContext = ToolDispatcher.Context(
            surface = surface,
            modelId = spec.id,
            turnId = turnId,
            askBeforeEachTool = askBeforeEach && surface == Surface.CHAT,
        )

        val builder = StringBuilder()
        val toolCallParts = mutableListOf<AidePart.ToolCall>()
        // Multi-segment reasoning (think→tool→think→answer); duration = closed segments + live tick.
        val thinkingBuilder = StringBuilder()
        var thinkingTotalMs: Long = 0L
        var currentSegmentStartedAt: Long = 0L
        var thinkingDurationMs: Long = 0L
        suspend fun persistAssistant(
            currentText: String,
        ) {
            transcript.updateAssistantMessage(
                assistantId,
                AideMessage(
                    role = AideRole.Model,
                    parts = buildList {
                        if (thinkingBuilder.isNotEmpty()) {
                            add(
                                AidePart.Thinking(
                                    text = thinkingBuilder.toString(),
                                    durationMs = thinkingDurationMs,
                                ),
                            )
                        }
                        if (currentText.isNotEmpty()) add(AidePart.Text(currentText))
                        addAll(toolCallParts)
                    },
                ),
            )
        }
        fun closeThinkingSegment() {
            if (currentSegmentStartedAt == 0L) return
            thinkingTotalMs += System.currentTimeMillis() - currentSegmentStartedAt
            currentSegmentStartedAt = 0L
            thinkingDurationMs = thinkingTotalMs
        }
        var realError: Throwable? = null
        var firstEventLogged = false
        var toolRoundCount = 0
        try {
            holder.session!!.send(userMessage, dispatchContext).collect { event ->
                if (!firstEventLogged) {
                    Log.i(
                        "AidePerf",
                        "send.firstEvent ms=${(System.nanoTime() - sendStartNs) / 1_000_000} " +
                            "kind=${event.javaClass.simpleName}",
                    )
                    firstEventLogged = true
                }
                when (event) {
                    is ChatStreamEvent.TextDelta -> {
                        // LiteRT-LM 0.11 and Ollama both emit incremental deltas, not cumulative.
                        if (currentSegmentStartedAt != 0L) {
                            closeThinkingSegment()
                            send(
                                Event.Thinking(
                                    assistantMessageId = assistantId,
                                    text = thinkingBuilder.toString(),
                                    durationMs = thinkingDurationMs,
                                    isStreaming = false,
                                ),
                            )
                            runCatching { persistAssistant(builder.toString()) }
                        }
                        builder.append(event.text)
                        send(Event.Streaming(assistantId, builder.toString()))
                    }
                    is ChatStreamEvent.ToolCallStarted -> {
                        toolRoundCount++
                        val argsString = event.args.toString()
                        toolCallParts += AidePart.ToolCall(
                            callId = event.callId,
                            name = event.name,
                            argsJson = argsString,
                        )
                        if (currentSegmentStartedAt != 0L) {
                            closeThinkingSegment()
                            send(
                                Event.Thinking(
                                    assistantMessageId = assistantId,
                                    text = thinkingBuilder.toString(),
                                    durationMs = thinkingDurationMs,
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
                        send(Event.ToolRunning(event.name, summarizeArgs(event.args)))
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
                        if (currentSegmentStartedAt == 0L) {
                            currentSegmentStartedAt = System.currentTimeMillis()
                        }
                        thinkingBuilder.append(event.text)
                        thinkingDurationMs = thinkingTotalMs +
                            (System.currentTimeMillis() - currentSegmentStartedAt)
                        send(
                            Event.Thinking(
                                assistantMessageId = assistantId,
                                text = thinkingBuilder.toString(),
                                durationMs = thinkingDurationMs,
                                isStreaming = true,
                            ),
                        )
                    }
                    is ChatStreamEvent.Completed -> {
                    }
                }
            }
        } catch (t: Throwable) {
            realError = t
            Log.w(
                "AidePerf",
                "send.error model=${spec.id} " +
                    "ms=${(System.nanoTime() - sendStartNs) / 1_000_000} " +
                    "${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        } finally {
            // Detach sinks to prevent emit-after-close if the session is reused.
            holder.sessionWebSearchToolset?.onSearchStarted = {}
            holder.sessionWebFetchToolset?.onFetchStarted = {}
            holder.sessionFileSystemToolset?.onToolStarted = { _, _ -> }
            writeGate.unbindEmitter()
            toolDispatcher.forgetTurn(turnId)
        }

        val finalText = when {
            realError != null && builder.isEmpty() ->
                "Error: ${realError.message ?: realError::class.java.simpleName}"
            else -> builder.toString()
        }
        if (currentSegmentStartedAt != 0L) {
            closeThinkingSegment()
            send(
                Event.Thinking(
                    assistantMessageId = assistantId,
                    text = thinkingBuilder.toString(),
                    durationMs = thinkingDurationMs,
                    isStreaming = false,
                ),
            )
        }
        transcript.updateAssistantMessage(
            assistantId,
            AideMessage(
                role = AideRole.Model,
                parts = buildList {
                    if (thinkingBuilder.isNotEmpty()) {
                        add(
                            AidePart.Thinking(
                                text = thinkingBuilder.toString(),
                                durationMs = thinkingDurationMs,
                            ),
                        )
                    }
                    if (finalText.isNotEmpty()) add(AidePart.Text(finalText))
                    addAll(toolCallParts)
                },
            ),
        )
        if (realError == null && builder.isNotEmpty()) {
            runCatching { registry.recordUsed(spec) }
        }
        Log.i(
            "AidePerf",
            "send.done model=${spec.id} " +
                "totalMs=${(System.nanoTime() - sendStartNs) / 1_000_000} " +
                "answerChars=${builder.length} thinkingChars=${thinkingBuilder.length} " +
                "thinkingMs=$thinkingDurationMs toolRounds=$toolRoundCount " +
                "error=${realError?.javaClass?.simpleName ?: "none"}",
        )
        send(Event.Done(assistantId, finalText))
    }
}
