package com.sabreware.aide.core.domain.usecase

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import com.sabreware.aide.core.domain.presence.SurfacePresence
import com.sabreware.aide.core.domain.presence.HiddenWorkPolicy
import com.sabreware.aide.core.domain.tools.enabledToolCategories
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.AideRole
import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.MessageWindow
import com.sabreware.aide.core.domain.chat.ProviderPayloadKeys
import com.sabreware.aide.core.domain.chat.providerPayloadOf
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.chat.ObservableChatTranscript
import com.sabreware.aide.core.domain.fakes.FakeModelRegistryRepository
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.fakes.FakeSamplerOverridesStore
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.dispatch.IdempotencyCache
import com.sabreware.aide.core.domain.llm.dispatch.RateLimiter
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.dispatch.Tracer
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.model.RemoteLlmModel
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResolver
import com.sabreware.aide.core.domain.tools.ToolBundle
import com.sabreware.aide.core.domain.tools.ToolBundleFactory
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolGate
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chat turn, end to end, through the one use case that owns it.
 *
 * This was the largest untested thing in the app — 534 lines that persist the user's message, stream the
 * reply, run tool rounds and finalise the assistant row — and the two bugs that lived in it were both about
 * what happens when a turn does NOT end normally:
 *
 *  - a stream that died mid-answer emitted `Done` with the truncated reply and no error, so the composer
 *    returned to Idle and the answer just stopped mid-sentence;
 *  - a cancelled turn had its `CancellationException` swallowed into the error path, and the finalisation
 *    that follows is a run of suspend calls that rethrow on a cancelled job — so the assistant row stayed
 *    the empty placeholder and the chat reopened with a blank bubble.
 *
 * Both are asserted below. The happy path and the tool round are here too, because a regression test for an
 * edge case is worth much less when nothing pins the shape it is an edge of.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendChatMessageUseCaseTest {

    // ── Fixture ─────────────────────────────────────────────────────────────────────────────────────────

    private val spec = RemoteLlmModel(
        id = "test-model",
        displayName = "Test Model",
        family = "test",
        params = "1B",
        quantization = "none",
        remoteName = "test",
        cloud = true,
        minRamGb = 1,
        recommendedRamGb = 1,
        capabilities = ChatCapabilities(maxContext = 8192, maxOutput = 2048),
        defaultBackend = ModelBackend.CPU,
        licenseName = "n/a",
        licenseUrl = "n/a",
        sourceUrl = "n/a",
        provider = ProviderId("openai-test01"),
    )

    /**
     * Records everything the turn persists, which is what the assertions are actually about.
     *
     * **Every write suspends**, via `yield()`. That is not decoration: a suspend function with no
     * suspension point never observes cancellation, so a fake that stores into a list synchronously would
     * happily "persist" on a cancelled coroutine and the cancellation test would pass against the bug it
     * exists to catch. A real transcript is Room-backed and suspends; the fake has to as well.
     */
    private class RecordingTranscript : ChatTranscript {
        val prior = mutableListOf<AideMessage>()
        val userMessages = mutableListOf<AideMessage>()
        val toolResponses = mutableListOf<AidePart.ToolResponse>()
        var assistant: AideMessage? = null
        var assistantText: String? = null
        var stats: MessageStats? = null
        private var nextId = 1L

        var priorReads = 0
            private set

        // Like the real transcripts: every turn recorded so far, including user turns appended by this one.
        override suspend fun priorMessages(): List<AideMessage> {
            yield()
            priorReads++
            return prior + userMessages
        }

        override suspend fun isEmpty(): Boolean = prior.isEmpty() && userMessages.isEmpty()

        override suspend fun appendUserMessage(message: AideMessage) {
            yield()
            userMessages += message
        }

        override suspend fun appendAssistantPlaceholder(): Long {
            yield()
            return nextId++
        }

        override suspend fun updateAssistantText(id: Long, text: String) {
            yield()
            assistantText = text
        }

        override suspend fun updateAssistantMessage(id: Long, message: AideMessage) {
            yield()
            assistant = message
        }

        override suspend fun updateAssistantStats(id: Long, stats: MessageStats) {
            yield()
            this.stats = stats
        }

        override suspend fun appendToolResponse(response: AidePart.ToolResponse): Long {
            yield()
            toolResponses += response
            return nextId++
        }

        /** The assistant turn's final text, as it would be read back from storage. */
        val persistedAnswer: String?
            get() = assistant?.parts?.filterIsInstance<AidePart.Text>()?.joinToString("") { it.text }
    }

    /** A session that replays a scripted event list, so a turn's shape is decided by the test. */
    private class ScriptedSession(
        private val events: List<ChatStreamEvent>,
        override val reusable: Boolean = true,
    ) : ChatSession {
        var cancelled = false
            private set

        override fun send(
            userMessage: AideMessage,
            dispatchContext: ToolDispatcher.Context,
        ): Flow<ChatStreamEvent> = flow {
            events.forEach { event ->
                // A suspension point per event, so a cancellation between two of them is expressible.
                yield()
                emit(event)
            }
        }

        override fun reset() = Unit
        override fun cancel() {
            cancelled = true
        }
        override fun close() = Unit
    }

    private class Holder : SendChatMessageUseCase.SessionHolder {
        override var session: ChatSession? = null
        override var sessionModelId: String? = null
        override var sessionEnabledGated: Set<ToolGate> = emptySet()
        override var sessionWebSearchProviderId: WebSearchProviderId? = null
        override var sessionEnabledCategories: Set<ToolCategory> = emptySet()
    }

    private fun useCase(
        session: ChatSession,
        transcript: RecordingTranscript,
        tools: List<AideTool> = emptyList(),
        // Each session build's seed history, in order.
        seeds: MutableList<List<AideMessage>> = mutableListOf(),
        presence: SurfacePresence = SurfacePresence(HiddenWorkPolicy.KeepRunning),
    ): SendChatMessageUseCase {
        val engine = object : LlmEngineRepository {
            override val loadedModelIdFlow: StateFlow<String?> = MutableStateFlow(null)
            override val loadedModelId: String? = null
            override val loadedAcceleratorFlow: StateFlow<Accelerator?> = MutableStateFlow(null)
            override suspend fun hydrateSpec(spec: ChatModelSpec) = Unit
            override suspend fun <T> withLifecycleLock(block: suspend () -> T): T = block()
            override suspend fun ensureLoaded(spec: ChatModelSpec, config: ChatGenerationConfig?) = Unit
            override suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig?) = Unit
            override suspend fun unload(modelId: String) = Unit
            override fun isLoaded(spec: ChatModelSpec): Boolean = true
            override fun newChatSession(
                spec: ChatModelSpec,
                initialMessages: List<AideMessage>,
                tools: List<AideTool>,
                systemInstruction: String?,
                config: ChatGenerationConfig,
                dispatcher: ToolDispatcher,
                activationState: ToolActivationState?,
            ): ChatSession = session.also { seeds += initialMessages }
            override fun engineGenerate(prompt: String, config: ChatGenerationConfig): Flow<String> =
                flow { }
        }
        val residency = object : ResidencyManager {
            override suspend fun acquire(model: ResidentModel, owner: Surface?): ResidencyHandle =
                object : ResidencyHandle {
                    override suspend fun release(keepAliveMs: Long) = Unit
                }
            override fun residents(): List<ResidencyManager.Resident> = emptyList()
            override fun onTrimMemory(level: Int) = Unit
        }
        return SendChatMessageUseCase(
            // Unused: every test drives the `invoke(transcript, …)` overload, which never touches the store.
            chats = UnusedChatRepository,
            transcriptFactory = object : ChatTranscriptFactory {
                override fun createPersistent(chatId: String): ChatTranscript = transcript
                override fun createInMemory(): ObservableChatTranscript =
                    throw UnsupportedOperationException("not used in this test")
            },
            engine = engine,
            storage = object : ModelStorage by UnusedModelStorage {
                override suspend fun isDownloaded(spec: ModelSpec): Boolean = true
            },
            registry = RecordingRegistry(mapOf(spec.id to spec)),
            webSearchResolver = object : WebSearchResolver {
                override suspend fun resolve(): WebSearchProvider =
                    throw UnsupportedOperationException("web search is off in this test")
                override fun activeProviderNameFlow(): Flow<String> = flow { }
                override fun allProviders(): List<WebSearchProvider> = emptyList()
            },
            writeGate = WriteConfirmGate(),
            toolBundleFactory = object : ToolBundleFactory {
                override fun build(
                    providerId: com.sabreware.aide.core.domain.model.ProviderId,
                    supportsTools: Boolean,
                    enabledGated: Set<ToolGate>,
                    enabledCategories: Set<ToolCategory>,
                    surface: com.sabreware.aide.core.domain.llm.Surface,
                ): ToolBundle = ToolBundle(tools, null, ToolActivationState())
            },
            contactPickGate = ContactPickGate(),
            toolDispatcher = ToolDispatcher(
                IdempotencyCache(),
                RateLimiter(),
                Tracer(),
                WriteConfirmGate(),
                FakePreferenceStore(),
                Dispatchers.Unconfined,
            ),
            userPrefs = FakePreferenceStore(),
            samplerOverrides = FakeSamplerOverridesStore(),
            acquireModel = AcquireModelUseCase(residency, engine),
            presence = presence,
        )
    }

    private fun SendChatMessageUseCase.run(transcript: RecordingTranscript, holder: Holder = Holder()) =
        invoke(
            transcript = transcript,
            modelId = spec.id,
            userParts = listOf(AidePart.Text("hello")),
            holder = holder,
        )

    // ── History: read only to seed a session that is being built ──────────────────────────────────────────

    private val answer = listOf(
        ChatStreamEvent.TextDelta("ok"),
        ChatStreamEvent.Completed(ChatStreamEvent.StopReason.EndTurn),
    )

    /** A holder already bound to [session] on the test model with the default toolset — the steady state. */
    private suspend fun boundHolder(session: ChatSession) = Holder().apply {
        this.session = session
        sessionModelId = spec.id
        sessionEnabledCategories = FakePreferenceStore().enabledToolCategories().first()
    }

    @Test
    fun `a new session is seeded with the history before this turn`() = runTest {
        val transcript = RecordingTranscript().apply { prior += AideMessage.user("earlier") }
        val seeds = mutableListOf<List<AideMessage>>()
        val session = ScriptedSession(answer)

        useCase(session, transcript, seeds = seeds).run(transcript).toList()

        assertEquals(listOf(listOf("earlier")), seeds.texts(), "the new turn goes through send(), not the seed")
    }

    @Test
    fun `a bound session never re-reads the history`() = runTest {
        val transcript = RecordingTranscript().apply { prior += AideMessage.user("earlier") }
        val seeds = mutableListOf<List<AideMessage>>()
        val session = ScriptedSession(answer)

        useCase(session, transcript, seeds = seeds).run(transcript, boundHolder(session)).toList()

        assertEquals(0, transcript.priorReads, "a long chat's every turn is not read per send")
        assertTrue(seeds.isEmpty(), "and no session is built")
    }

    @Test
    fun `a rebuild found late drops the just-appended turn from the seed`() = runTest {
        val transcript = RecordingTranscript().apply { prior += AideMessage.user("earlier") }
        val seeds = mutableListOf<List<AideMessage>>()
        val session = ScriptedSession(answer)
        // Bound to this model, but on a toolset the prefs no longer match: the rebuild is decided after append.
        val holder = boundHolder(session).apply { sessionEnabledCategories = setOf(ToolCategory.Other) }

        useCase(session, transcript, seeds = seeds).run(transcript, holder).toList()

        assertEquals(listOf(listOf("earlier")), seeds.texts())
    }

    @Test
    fun `a session a cancelled turn left unusable is rebuilt from the transcript`() = runTest {
        // The last reply was cut off when the app hid; its partial text is already in the transcript.
        val transcript = RecordingTranscript().apply {
            prior += AideMessage.user("earlier")
            prior += AideMessage(role = AideRole.Model, parts = listOf(AidePart.Text("half an ans")))
        }
        val seeds = mutableListOf<List<AideMessage>>()
        val poisoned = ScriptedSession(answer, reusable = false)
        val fresh = ScriptedSession(answer)

        useCase(fresh, transcript, seeds = seeds).run(transcript, boundHolder(poisoned)).toList()

        assertEquals(
            listOf(listOf("earlier", "half an ans")),
            seeds.texts(),
            "a cancelled native session is never sent into again; the new one replays what was kept",
        )
    }

    @Test
    fun `leaving the surface ends the turn and keeps the partial reply as its answer`() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree).apply { shown(Surface.CHAT) }
        val transcript = RecordingTranscript()
        val stream = Channel<ChatStreamEvent>(Channel.UNLIMITED)
        var streamCancelled = false
        val session = object : ChatSession {
            override fun send(userMessage: AideMessage, dispatchContext: ToolDispatcher.Context) =
                flow { for (e in stream) emit(e) }.onCompletion { if (it is CancellationException) streamCancelled = true }
            override fun reset() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val events = mutableListOf<SendChatMessageUseCase.Event>()
        val turn = launch { useCase(session, transcript, presence = presence).run(transcript).toList(events) }
        advanceUntilIdle()
        stream.trySend(ChatStreamEvent.TextDelta("half an ans"))
        advanceUntilIdle()

        presence.hidden(Surface.CHAT)
        advanceUntilIdle()

        assertTrue(turn.isCompleted, "the turn ends when its surface hides")
        assertTrue(streamCancelled, "the session's stream is cancelled, which is what stops a native engine")
        assertEquals("half an ans", transcript.assistant?.textContent, "the partial reply is persisted")
        assertTrue(events.any { it is SendChatMessageUseCase.Event.Done }, "and ends as a Done")
        assertTrue(events.none { it is SendChatMessageUseCase.Event.Error }, "not as a failure")
    }

    private fun List<List<AideMessage>>.texts() = map { seed -> seed.map { it.textContent } }

    // ── The happy path ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a turn persists the user message, streams deltas and finalises the assistant row`() = runTest {
        val transcript = RecordingTranscript()
        val session = ScriptedSession(
            listOf(
                ChatStreamEvent.TextDelta("Hel"),
                ChatStreamEvent.TextDelta("lo."),
                ChatStreamEvent.Completed(ChatStreamEvent.StopReason.EndTurn),
            ),
        )

        val events = useCase(session, transcript).run(transcript).toList()

        assertEquals(1, transcript.userMessages.size, "the user turn is persisted exactly once")
        assertEquals(AideRole.User, transcript.userMessages.single().role)

        val deltas = events.filterIsInstance<SendChatMessageUseCase.Event.Streaming>()
        // The first Streaming opens the turn (empty delta); the rest are the answer, delta by delta.
        assertEquals(listOf("", "Hel", "lo."), deltas.map { it.delta })

        val done = events.filterIsInstance<SendChatMessageUseCase.Event.Done>().single()
        assertEquals("Hello.", done.finalText)
        assertEquals("Hello.", transcript.persistedAnswer, "what is stored is what was streamed")
        assertTrue(events.none { it is SendChatMessageUseCase.Event.Error }, "a clean turn reports no error")
    }

    @Test
    fun `a tool round dispatches the tool, persists its response and continues the answer`() = runTest {
        val transcript = RecordingTranscript()
        var toolCalls = 0
        val tool = AideTool.Function(
            name = "lookup",
            description = "test tool",
            parametersSchema = buildJsonObject { },
            handler = {
                toolCalls++
                ToolEnvelope.success { put("answer", 42) }
            },
        )
        val session = ScriptedSession(
            listOf(
                ChatStreamEvent.ToolCallStarted("call-1", "lookup", buildJsonObject { put("q", "x") }),
                ChatStreamEvent.ToolCallCompleted("call-1", "lookup", """{"ok":"true"}""", null),
                ChatStreamEvent.TextDelta("It is 42."),
                ChatStreamEvent.Completed(ChatStreamEvent.StopReason.EndTurn),
            ),
        )

        val events = useCase(session, transcript, tools = listOf(tool)).run(transcript).toList()

        assertEquals(
            1,
            events.filterIsInstance<SendChatMessageUseCase.Event.ToolCallStarted>().size,
            "the tool call surfaces to the UI as it starts",
        )
        assertEquals(1, transcript.toolResponses.size, "the tool's response is persisted for reopen")
        assertEquals("It is 42.", transcript.persistedAnswer)
        assertTrue(
            transcript.assistant?.parts?.filterIsInstance<AidePart.ToolCall>()?.isNotEmpty() == true,
            "the assistant turn records the call so a reopened chat can paint its chip",
        )
    }

    // ── P0.1: the signature has to reach storage, per block ─────────────────────────────────────────────

    @Test
    fun `each reasoning block is persisted with the payload that signs it`() = runTest {
        val transcript = RecordingTranscript()
        val first = providerPayloadOf("anthropic", ProviderPayloadKeys.SIGNATURE to "sig-first")
        val second = providerPayloadOf("anthropic", ProviderPayloadKeys.SIGNATURE to "sig-second")
        val redacted = providerPayloadOf("anthropic", ProviderPayloadKeys.REDACTED to "opaque")
        val onCall = providerPayloadOf("google", ProviderPayloadKeys.THOUGHT_SIGNATURE to "sig-call")
        val tool = AideTool.Function(
            name = "lookup",
            description = "test tool",
            parametersSchema = buildJsonObject { },
            handler = { ToolEnvelope.success { put("answer", 42) } },
        )
        val session = ScriptedSession(
            listOf(
                ChatStreamEvent.ThinkingDelta("weighing "),
                ChatStreamEvent.ThinkingDelta("it up"),
                ChatStreamEvent.ThinkingDelta("", first),
                ChatStreamEvent.ThinkingDelta("and again"),
                ChatStreamEvent.ThinkingDelta("", second),
                ChatStreamEvent.ThinkingDelta("", redacted),
                ChatStreamEvent.ToolCallStarted("call-1", "lookup", buildJsonObject { }, onCall),
                ChatStreamEvent.ToolCallCompleted("call-1", "lookup", """{"ok":true}""", null),
                ChatStreamEvent.TextDelta("42."),
                ChatStreamEvent.Completed(ChatStreamEvent.StopReason.EndTurn),
            ),
        )

        useCase(session, transcript, tools = listOf(tool)).run(transcript).toList()

        val thinking = transcript.assistant?.parts?.filterIsInstance<AidePart.Thinking>().orEmpty()
        assertEquals(
            listOf("weighing it up" to first, "and again" to second, "" to redacted),
            thinking.map { it.text to it.providerMetadata },
            "one part per block, in arrival order, each carrying its own payload — a merged block " +
                "signed by the last payload fails verification the moment the history is replayed",
        )
        assertEquals(
            onCall,
            transcript.assistant?.parts?.filterIsInstance<AidePart.ToolCall>()?.single()?.providerMetadata,
            "Gemini signs the call, not the thought, and 400s on a replayed call without it",
        )
    }

    // ── S2: a dropped stream is not a finished answer ───────────────────────────────────────────────────

    @Test
    fun `a stream that fails after partial text keeps the text AND reports the failure`() = runTest {
        val transcript = RecordingTranscript()
        val session = object : ChatSession {
            override fun send(
                userMessage: AideMessage,
                dispatchContext: ToolDispatcher.Context,
            ): Flow<ChatStreamEvent> = flow {
                emit(ChatStreamEvent.TextDelta("Half an ans"))
                throw IllegalStateException("connection reset")
            }
            override fun reset() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }

        val events = useCase(session, transcript).run(transcript).toList()

        assertEquals(
            "Half an ans",
            transcript.persistedAnswer,
            "the partial reply is real text the user watched arrive; it is kept",
        )
        val error = events.filterIsInstance<SendChatMessageUseCase.Event.Error>().singleOrNull()
        assertTrue(
            error != null,
            "a stream that DIED mid-answer must say so — it used to emit only Done with the truncated " +
                "text, so the composer returned to Idle and the answer simply stopped mid-sentence",
        )
        assertTrue(error.message.contains("connection reset"), "the failure names its cause")
    }

    @Test
    fun `a failure before any text becomes the assistant turn's error text`() = runTest {
        val transcript = RecordingTranscript()
        val session = object : ChatSession {
            override fun send(
                userMessage: AideMessage,
                dispatchContext: ToolDispatcher.Context,
            ): Flow<ChatStreamEvent> = flow { throw IllegalStateException("no route to host") }
            override fun reset() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }

        useCase(session, transcript).run(transcript).toList()

        assertEquals(
            "Error: no route to host",
            transcript.persistedAnswer,
            "with nothing streamed there is no reply to keep, so the row carries the error",
        )
    }

    // ── S3: cancellation keeps the partial reply ────────────────────────────────────────────────────────

    @Test
    fun `a cancelled turn still persists what had already streamed`() = runTest {
        val transcript = RecordingTranscript()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = object : ChatSession {
            override fun send(
                userMessage: AideMessage,
                dispatchContext: ToolDispatcher.Context,
            ): Flow<ChatStreamEvent> = flow {
                emit(ChatStreamEvent.TextDelta("Most of the "))
                emit(ChatStreamEvent.TextDelta("answer"))
                gate.complete(Unit)
                // Never completes: the turn is ended by cancellation, which is the case under test.
                kotlinx.coroutines.awaitCancellation()
            }
            override fun reset() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }

        val collecting = async { useCase(session, transcript).run(transcript).toList() }
        gate.await()
        collecting.cancel()
        runCatching { collecting.await() }

        assertEquals(
            "Most of the answer",
            transcript.persistedAnswer,
            "cancellation used to be swallowed into the error path, and the finalisation that follows is a " +
                "run of suspend calls that rethrow on a cancelled job — so the row stayed the empty " +
                "placeholder and the chat reopened blank",
        )
    }

    @Test
    fun `an unknown model is refused before anything is persisted`() = runTest {
        val transcript = RecordingTranscript()
        val useCase = useCase(ScriptedSession(emptyList()), transcript)

        val events = useCase.invoke(
            transcript = transcript,
            modelId = "not-in-the-catalog",
            userParts = listOf(AidePart.Text("hello")),
            holder = Holder(),
        ).toList()

        assertTrue(events.single() is SendChatMessageUseCase.Event.Error)
        assertTrue(transcript.userMessages.isEmpty(), "nothing is written for a turn that cannot run")
        assertNull(transcript.assistant)
    }

    /** [FakeModelRegistryRepository] refuses everything by default; a completed turn records its model. */
    private class RecordingRegistry(specs: Map<String, ModelSpec>) : FakeModelRegistryRepository(specs) {
        // A completed turn records its model; the base fake refuses everything it has no reason to answer.
        override suspend fun recordUsed(spec: ModelSpec): Unit = Unit
    }

    /** Unused: every test drives the transcript overload, which never reaches the chat store. */
    private object UnusedChatRepository : ChatRepository {
        override val chats: StateFlow<List<Chat>?> get() = unsupported()
        override suspend fun chatsSnapshot(): List<Chat> = unsupported()
        override suspend fun getChat(id: String): Chat? = unsupported()
        override fun observeChat(chatId: String): Flow<Chat?> = unsupported()
        override fun observeMessageWindow(chatId: String, upToId: Long?, limit: Int): Flow<MessageWindow> =
            unsupported()
        override suspend fun messageIdsAfter(chatId: String, afterId: Long, limit: Int): List<Long> = unsupported()
        override suspend fun messagesSnapshot(chatId: String): List<StoredMessage> = unsupported()
        override suspend fun hasMessages(chatId: String): Boolean = unsupported()
        override suspend fun createChat(id: String, title: String, surface: Surface): Chat = unsupported()
        override suspend fun setTitle(chatId: String, title: String) = unsupported()
        override suspend fun touch(chatId: String) = unsupported()
        override suspend fun appendMessage(chatId: String, role: String, text: String): Long = unsupported()
        override suspend fun appendMessage(chatId: String, message: AideMessage): Long = unsupported()
        override suspend fun deleteMessagesFrom(chatId: String, fromId: Long) = unsupported()
        override suspend fun updateMessageText(id: Long, text: String) = unsupported()
        override suspend fun updateMessage(id: Long, message: AideMessage) = unsupported()
        override suspend fun updateMessageStats(id: Long, stats: MessageStats) = unsupported()
        override suspend fun deleteChat(chatId: String) = unsupported()
        override suspend fun setStarred(chatId: String, starred: Boolean) = unsupported()
        override suspend fun setArchived(chatId: String, archived: Boolean) = unsupported()
        override suspend fun deleteChats(chatIds: List<String>) = unsupported()
        override suspend fun setArchivedForChats(chatIds: List<String>, archived: Boolean) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
    }

    /** Everything [ModelStorage] exposes that this test never reaches. */
    private object UnusedModelStorage : ModelStorage {
        override val changes: Flow<Unit> get() = flow { }
        override suspend fun modelFile(spec: ModelSpec) = unsupported()
        override suspend fun partFile(spec: ModelSpec) = unsupported()
        override suspend fun importTarget(spec: ModelSpec) = unsupported()
        override suspend fun isDownloaded(spec: ModelSpec) = unsupported()
        override suspend fun hasUpdate(spec: ModelSpec) = unsupported()
        override suspend fun isPresent(spec: ModelSpec) = unsupported()
        override suspend fun downloadedBytes(spec: ModelSpec) = unsupported()
        override suspend fun delete(spec: ModelSpec) = unsupported()
        override suspend fun deleteFile(spec: ModelSpec) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
    }
}
