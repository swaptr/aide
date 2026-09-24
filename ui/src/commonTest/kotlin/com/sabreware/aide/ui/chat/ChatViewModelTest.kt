package com.sabreware.aide.ui.chat

import com.sabreware.aide.ui.navigation.Route
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import com.sabreware.aide.core.common.media.CameraCapture
import com.sabreware.aide.core.common.media.FileAttachmentStore
import com.sabreware.aide.core.common.media.ImageAttachmentStore
import com.sabreware.aide.core.common.speech.DictationController
import com.sabreware.aide.core.common.speech.DictationSink
import com.sabreware.aide.core.common.speech.DictationState
import com.sabreware.aide.core.common.speech.DictationSurfaceId
import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.chat.MessageStats
import com.sabreware.aide.core.domain.chat.ObservableChatTranscript
import com.sabreware.aide.core.domain.chat.StoredMessage
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.fakes.FakeModelRegistryRepository
import com.sabreware.aide.core.common.persist.DocState
import com.sabreware.aide.core.domain.model.ModelCard
import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.fakes.FakeSamplerOverridesStore
import com.sabreware.aide.core.domain.io.MicClipRecorder
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.ChatStreamEvent
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.dispatch.IdempotencyCache
import com.sabreware.aide.core.domain.llm.dispatch.RateLimiter
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.dispatch.Tracer
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.RemoteLlmModel
import com.sabreware.aide.core.domain.model.ResidencyHandle
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.model.ResidentModel
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.PermissionResult
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.permission.SpecialPermission
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchResolver
import com.sabreware.aide.core.domain.tools.ToolBundle
import com.sabreware.aide.core.domain.tools.ToolBundleFactory
import com.sabreware.aide.core.domain.tools.ToolCategory
import com.sabreware.aide.core.domain.tools.ToolGate
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import com.sabreware.aide.core.domain.usecase.AcquireModelUseCase
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.CreateChatUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteMessagesFromUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatMessagesUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.SendChatMessageUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
import com.sabreware.aide.ui.fakes.FakeChatRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The chat surface's stop/cancel path — the fix with the highest blast radius and, until now, no test.
 *
 * The bug (C3): [ChatViewModel.stop] flipped the UI to Idle but cancelled nothing. `RemoteChatSession.cancel()`
 * defers to coroutine cancellation, and no coroutine was being cancelled — so the next streaming event flipped
 * the composer straight back to Generating with the text restored, while tokens kept billing and rows kept
 * being written. The fix is three-legged: `stop()` cancels [ChatViewModel]'s send job, the send guard asks
 * `sendJob?.isActive` (not the engine state, which stop() zeroes for immediate feedback), and the session is
 * asked to cancel as well. Each leg is pinned below.
 *
 * The harness drives the REAL [SendChatMessageUseCase] (it is final, and the turn's cancellation contract
 * lives inside it) over a scripted [ChatSession] whose stream the test feeds event by event — the same
 * layering the production graph has, minus the providers.
 *
 * Two traps this file works around, deliberately:
 *  - The route is decoded from an EMPTY [SavedStateHandle]. Present keys are read back through a platform
 *    `SavedState` — `android.os.Bundle` on the Android host-test JVM, where every Bundle method throws.
 *    Absent keys never touch the store: the decoder skips them and Kotlin defaults fill in, which is why
 *    `Route.Chat.chatId` defaults to "" (the draft). Do not "fix" this by seeding the handle.
 *  - `STREAM_PATCH_MIN_INTERVAL` runs on `TimeSource.Monotonic` — wall clock, NOT virtual test time — so no
 *    assertion here depends on the interval gate. The deterministic flush is the one any non-Streaming event
 *    forces (the "publish the held tail" rule), and that is the flush the streaming test leans on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Builds the harness and lets the header combine resolve — send() is dead until `hasModel` is true. */
    private fun withChat(block: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness()
        advanceUntilIdle()
        block(harness)
    }

    /**
     * The header's stages, from a registry whose chosen model has NOT resolved — the cold-start shape.
     *
     * The pill carries the chosen model's name from the choice document, and the composer may not act on
     * it. Both halves matter: drawing nothing is the shimmer the user waited seconds through, and drawing a
     * call-to-action would be the "Select a model" flash over a model that is configured fine.
     */
    @Test
    fun `an unresolved choice paints the chosen model name but does not enable sending`() = runTest {
        val card = ModelCard(id = TEST_MODEL.id, displayName = "Chosen Name", provider = "openai")
        val harness = Harness(registry = UnsettledRegistry(ModelSelection(lastUsedModelId = TEST_MODEL.id, cards = mapOf(TEST_MODEL.id to card))))
        advanceUntilIdle()

        val state = harness.vm.uiState.value
        assertEquals(ModelResolution.Cached, state.modelResolution)
        assertEquals("Chosen Name", state.modelDisplayName, "the pill draws the choice, not a skeleton")
        assertFalse(state.hasModel, "a recorded NAME is not a resolvable spec — sending must wait")
        assertFalse(state.promptsForModel, "a chosen model must never get a call to action drawn over it")
    }

    /** Before the choice document is read there is nothing to say: skeleton, and never a call to action. */
    @Test
    fun `an unread choice document stays unresolved`() = runTest {
        val harness = Harness(registry = UnsettledRegistry(selection = null))
        advanceUntilIdle()

        val state = harness.vm.uiState.value
        assertEquals(ModelResolution.Unresolved, state.modelResolution)
        assertTrue(state.modelDisplayName.isBlank())
        assertFalse(state.hasModel)
        assertFalse(state.promptsForModel)
    }

    /**
     * Nothing chosen is known the moment the document is read — no provider has to answer first — so the
     * header says "No model" and the call to action shows on the first frame.
     */
    @Test
    fun `nothing chosen prompts from the document alone, before the registry answers`() = runTest {
        val harness = Harness(registry = UnsettledRegistry(ModelSelection()))

        // Synchronous: the ViewModel's very first state — what the first frame composes — already knows.
        val first = harness.vm.uiState.value
        assertEquals(ModelResolution.NoneSelected, first.modelResolution)
        assertTrue(first.headerKnown)
        assertTrue(first.promptsForModel)

        advanceUntilIdle()
        assertTrue(harness.vm.uiState.value.promptsForModel, "and the settled answer agrees")
    }

    /**
     * Switching to a model whose source has not answered must not leave the previous model resolved —
     * otherwise the next send goes to the model the user just switched away from.
     */
    @Test
    fun `switching to an unresolved model un-resolves the previous one`() = runTest {
        val next = ModelCard(id = "openai:next", displayName = "Next", provider = "openai")
        val registry = SwitchingRegistry(TEST_MODEL)
        val harness = Harness(registry = registry)
        advanceUntilIdle()
        assertTrue(harness.vm.uiState.value.hasModel)

        registry.choose(next)
        advanceUntilIdle()

        val state = harness.vm.uiState.value
        assertFalse(state.hasModel, "nothing may be sent until the NEW choice resolves")
        assertEquals("", state.currentModelId)
        assertEquals("Next", state.modelDisplayName, "the pill paints the new choice meanwhile")
    }

    /**
     * A reroute (the user's own setting) makes the chat usable on a stand-in — and says so: the header names
     * the stand-in, `reroutedFrom` names the chosen model, and a send goes through because it is checked
     * against the CHOICE the header resolved for, not the stand-in's id.
     */
    @Test
    fun `a rerouted choice is usable, sends, and says whom it stands in for`() = runTest {
        val chosen = ModelCard(id = "gone-local", displayName = "Gone Local", local = true)
        val harness = Harness(registry = ReroutedRegistry(TEST_MODEL, chosen))
        advanceUntilIdle()

        val state = harness.vm.uiState.value
        assertTrue(state.hasModel)
        assertEquals(TEST_MODEL.id, state.currentModelId)
        assertEquals("Gone Local", state.reroutedFrom)
        assertEquals("gone-local", state.resolvedChoiceId)

        harness.type("hello")
        harness.vm.send()
        advanceUntilIdle()
        assertEquals(1, harness.session.sendCalls, "the guard compares the choice, not the stand-in's id")
    }

    /** A chosen model that turns out unusable is NAMED — the chat never swaps in a different one. */
    @Test
    fun `a chosen model that is missing is named, not replaced`() = runTest {
        val card = ModelCard(id = TEST_MODEL.id, displayName = "Gone Model", provider = "local", local = true)
        val selection = ModelSelection(lastUsedModelId = TEST_MODEL.id, cards = mapOf(TEST_MODEL.id to card))
        val harness = Harness(registry = UnsettledRegistry(selection, resolved = ModelGateState.Missing(card)))
        advanceUntilIdle()

        val state = harness.vm.uiState.value
        assertEquals(ModelResolution.Resolved, state.modelResolution)
        assertEquals("Gone Model", state.unavailableModel)
        assertFalse(state.hasModel)
        assertTrue(state.promptsForModel)
    }

    /** And the settled snapshot is what unlocks the composer. */
    @Test
    fun `a settled registry resolves and enables sending`() = withChat { h ->
        val state = stateOf(h.vm)
        assertEquals(ModelResolution.Resolved, state.modelResolution)
        assertTrue(state.hasModel)
    }

    /** The state once everything queued has run — for asserting the settled result of an action. */
    private fun TestScope.stateOf(vm: ChatViewModel): ChatUiState {
        advanceUntilIdle()
        return vm.uiState.value
    }

    // ── C3, leg one: stop() must actually cancel ────────────────────────────────────────────────────────

    @Test
    fun `stop cancels the session AND the collection job, and later tokens change nothing`() = withChat { h ->
        h.type("hello")
        h.vm.send()
        advanceUntilIdle()
        h.session.events.trySend(ChatStreamEvent.TextDelta("Half an "))
        assertEquals(EngineState.Generating, stateOf(h.vm).engineState)

        h.vm.stop()
        assertEquals(
            EngineState.Idle,
            h.vm.uiState.value.engineState,
            "immediate feedback: the composer flips before the job has unwound",
        )
        assertTrue(h.session.cancelCalled, "the session is asked to stop generating")

        advanceUntilIdle()
        assertTrue(
            h.session.streamCancelled,
            "the line that was missing: sendJob.cancel() must tear down the event collection — " +
                "RemoteChatSession.cancel() defers to exactly this",
        )

        // The regression itself: a token arriving after stop() used to flip the composer back to
        // Generating with the reply resumed. A cancelled collect must leave it on the floor.
        h.session.events.trySend(ChatStreamEvent.TextDelta("swer, resumed"))
        val after = stateOf(h.vm)
        assertEquals(EngineState.Idle, after.engineState, "a stopped turn stays stopped")
        assertTrue(
            after.messages.none { it is ChatMessage.Assistant && it.isStreaming },
            "and no streaming row is revived",
        )
    }

    // ── C3, leg two: the send guard is the job, not the engine state ────────────────────────────────────

    @Test
    fun `a second send while a turn is in flight is a no-op`() = withChat { h ->
        h.type("first")
        h.vm.send()
        advanceUntilIdle()
        assertEquals(1, h.session.sendCalls)
        assertEquals(1, h.transcript.userMessages.size)

        h.type("second")
        h.vm.send()
        advanceUntilIdle()

        assertEquals(1, h.session.sendCalls, "one turn, one session send — the guard held")
        assertEquals(1, h.transcript.userMessages.size, "and nothing extra was persisted")
        assertEquals(
            "second",
            h.vm.composerState.text.toString(),
            "a rejected send must not eat the draft — only an admitted one clears the composer",
        )
    }

    @Test
    fun `after stop the next send is admitted`() = withChat { h ->
        h.type("first")
        h.vm.send()
        advanceUntilIdle()
        h.vm.stop()
        advanceUntilIdle()

        h.type("take two")
        h.vm.send()
        advanceUntilIdle()

        assertEquals(2, h.session.sendCalls, "sendJob is no longer active, so the guard admits a new turn")
        assertEquals(2, h.transcript.userMessages.size)
        assertEquals(EngineState.Generating, h.vm.uiState.value.engineState, "and the second turn is live")
    }

    // ── The streaming patch: accumulate in flight, gone on Done ─────────────────────────────────────────

    @Test
    fun `streaming deltas accumulate into one assistant row and Done clears it`() = withChat { h ->
        h.type("hi")
        h.vm.send()
        advanceUntilIdle()

        h.session.events.trySend(ChatStreamEvent.TextDelta("Hello "))
        h.session.events.trySend(ChatStreamEvent.TextDelta("world"))
        // The 33 ms patch interval is wall-clock, so the deltas above may still be sitting in the builder.
        // Any non-Streaming event publishes the held tail — that flush is deterministic, so it is the one
        // this test stands on.
        h.session.events.trySend(ChatStreamEvent.ThinkingDelta("mulling"))

        val streaming = stateOf(h.vm).messages.filterIsInstance<ChatMessage.Assistant>().single()
        assertEquals("Hello world", streaming.text, "deltas accumulate — the event carries only the delta")
        assertTrue(streaming.isStreaming, "and the row renders as live")

        h.session.events.trySend(ChatStreamEvent.Completed(ChatStreamEvent.StopReason.EndTurn))
        h.session.events.close()

        val done = stateOf(h.vm)
        assertEquals(EngineState.Idle, done.engineState)
        assertTrue(
            done.messages.isEmpty(),
            "Done resets the patch and both chip maps; the persisted row is the repository's to re-emit",
        )
    }

    @Test
    fun `a cancelled collect resets the streaming state`() = withChat { h ->
        h.type("hi")
        h.vm.send()
        advanceUntilIdle()
        h.session.events.trySend(ChatStreamEvent.TextDelta("partial"))
        h.session.events.trySend(ChatStreamEvent.ThinkingDelta("mulling"))
        assertTrue(
            stateOf(h.vm).messages.any { it is ChatMessage.Assistant && it.isStreaming },
            "precondition: the reply is visibly streaming before the stop",
        )

        h.vm.stop()

        assertTrue(
            stateOf(h.vm).messages.isEmpty(),
            "the CancellationException path clears the patch and the in-flight chips — no orphaned " +
                "streaming row survives the stop",
        )
    }

    // ── Harness ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One ViewModel over the real use case, with the stream under the test's control. The route decodes to
     * the DRAFT chat (empty handle — see the class doc for why it must stay empty), so the first send also
     * exercises the lazy chat-row creation.
     */
    private class Harness(private val registry: ModelRegistryRepository = TestRegistry(TEST_MODEL)) {
        val session = ControllableSession()
        val transcript = RecordingTranscript()
        val repo: ChatRepository = MessageAwareRepo(FakeChatRepository())
        private val prefs = FakePreferenceStore()
        private val engine = SingleSessionEngine(session)
        private val transcriptFactory = object : ChatTranscriptFactory {
            override fun createInMemory(): ObservableChatTranscript =
                throw UnsupportedOperationException("incognito is not under test")
            override fun createPersistent(chatId: String): ChatTranscript = transcript
        }

        private val sendChatMessage = SendChatMessageUseCase(
            chats = repo,
            transcriptFactory = transcriptFactory,
            engine = engine,
            storage = DownloadedStorage,
            registry = registry,
            webSearchResolver = OffWebSearchResolver,
            writeGate = WriteConfirmGate(),
            toolBundleFactory = EmptyToolBundleFactory,
            contactPickGate = ContactPickGate(),
            toolDispatcher = ToolDispatcher(
                IdempotencyCache(),
                RateLimiter(),
                Tracer(),
                WriteConfirmGate(),
                prefs,
                Dispatchers.Unconfined,
            ),
            userPrefs = prefs,
            samplerOverrides = FakeSamplerOverridesStore(),
            acquireModel = AcquireModelUseCase(NoResidency, engine),
        )

        val vm = ChatViewModel(
            route = Route.Chat(),
            savedStateHandle = SavedStateHandle(),
            observeChat = ObserveChatUseCase(repo),
            observeMessages = ObserveChatMessagesUseCase(repo),
            createChat = CreateChatUseCase(repo),
            sendChatMessage = sendChatMessage,
            userPrefs = prefs,
            imageStore = NoImageStore,
            fileStore = FileAttachmentStore(UnusedPaths, FileSystem.SYSTEM, Dispatchers.Unconfined),
            registry = registry,
            engineRepository = engine,
            setChatStarred = SetChatStarredUseCase(repo),
            setChatArchived = SetChatArchivedUseCase(repo),
            renameChatUseCase = RenameChatUseCase(repo),
            deleteChatWithFallback = DeleteChatWithFallbackUseCase(repo),
            deleteMessagesFrom = DeleteMessagesFromUseCase(repo),
            archiveChatWithFallback = ArchiveChatWithFallbackUseCase(repo),
            dictationController = NoDictation,
            clipRecorder = NoClipRecorder(),
            gate = GrantEverything,
            transcriptFactory = transcriptFactory,
        )

        fun type(text: String) = vm.composerState.setTextAndPlaceCursorAtEnd(text)
    }

    /**
     * A [ChatSession] whose stream is a channel the test feeds. The finally-flag distinguishes a stream
     * that ran to its end from one torn down mid-flight — which is precisely what stop() must cause.
     */
    private class ControllableSession : ChatSession {
        val events = Channel<ChatStreamEvent>(Channel.UNLIMITED)
        var sendCalls = 0
            private set
        var cancelCalled = false
            private set
        var streamCancelled = false
            private set

        override fun send(
            userMessage: AideMessage,
            dispatchContext: ToolDispatcher.Context,
        ): Flow<ChatStreamEvent> = flow {
            sendCalls++
            var ranToEnd = false
            try {
                for (event in events) emit(event)
                ranToEnd = true
            } finally {
                if (!ranToEnd) streamCancelled = true
            }
        }

        override fun reset() = Unit
        override fun cancel() {
            cancelCalled = true
        }
        override fun close() = Unit
    }

    /**
     * Every write suspends (`yield()`): a suspend fun with no suspension point never observes cancellation,
     * and the use case's finalisation deliberately runs under NonCancellable — a synchronous fake would
     * make that distinction invisible.
     */
    private class RecordingTranscript : ChatTranscript {
        val userMessages = mutableListOf<AideMessage>()
        private var nextId = 1L

        override suspend fun priorMessages(): List<AideMessage> {
            yield()
            return emptyList()
        }
        override suspend fun appendUserMessage(message: AideMessage) {
            yield()
            userMessages += message
        }
        override suspend fun appendAssistantPlaceholder(): Long {
            yield()
            return nextId++
        }
        override suspend fun updateAssistantText(id: Long, text: String) = yield()
        override suspend fun updateAssistantMessage(id: Long, message: AideMessage) = yield()
        override suspend fun updateAssistantStats(id: Long, stats: MessageStats) = yield()
        override suspend fun appendToolResponse(response: AidePart.ToolResponse): Long {
            yield()
            return nextId++
        }
    }

    /** [FakeChatRepository]'s message half throws by design; the message list here is the VM's source. */
    private class MessageAwareRepo(inner: FakeChatRepository) : ChatRepository by inner {
        private val messages = MutableStateFlow<List<StoredMessage>>(emptyList())
        override fun observeMessages(chatId: String): Flow<List<StoredMessage>> = messages
    }

    private class TestRegistry(private val model: ChatModelSpec) : FakeModelRegistryRepository(mapOf(model.id to model)) {
        override val selection: StateFlow<DocState<ModelSelection>> =
            MutableStateFlow(DocState.Ready(ModelSelection(lastUsedModelId = model.id)))
        override fun resolve(id: String): Flow<ModelGateState> = flowOf(ModelGateState.Ready(model))
        override val lastUsedModelIdFlow: StateFlow<String?> = MutableStateFlow(model.id)
        override val effectiveLastUsedModelIdFlow: Flow<String?> = MutableStateFlow<String?>(model.id)
        private val summaries = listOf(
            ModelSummary(
                spec = model,
                downloadStatus = DownloadStatus.Completed(model.id),
                isLoadedInEngine = false,
                isDefault = false,
            ),
        )
        override val models: StateFlow<List<ModelSummary>?> = MutableStateFlow(summaries)
        override suspend fun recordUsed(spec: ModelSpec): Unit = Unit
    }

    /**
     * A registry stuck mid-cold-start: the chosen model's source has not answered, so `resolve` says
     * Unresolved (or [resolved], when a test wants a specific settled answer). [selection] is the choice
     * document — null while it has not been read — and it is the only thing the header can go on.
     */
    private class UnsettledRegistry(
        selection: ModelSelection?,
        private val resolved: ModelGateState = ModelGateState.Unresolved,
    ) : FakeModelRegistryRepository() {
        override val selection: StateFlow<DocState<ModelSelection>> =
            MutableStateFlow(if (selection == null) DocState.Loading else DocState.Ready(selection))
        override val lastUsedModelIdFlow: StateFlow<String?> = MutableStateFlow(selection?.lastUsedModelId)
        override fun resolve(id: String): Flow<ModelGateState> = flowOf(resolved)
        override suspend fun recordUsed(spec: ModelSpec): Unit = Unit
    }

    /** Resolves [model] and leaves anything else unresolved; [choose] records a new pick. */
    private class SwitchingRegistry(private val model: ChatModelSpec) :
        FakeModelRegistryRepository(mapOf(model.id to model)) {
        override val selection = MutableStateFlow<DocState<ModelSelection>>(
            DocState.Ready(ModelSelection(lastUsedModelId = model.id)),
        )
        override val lastUsedModelIdFlow: StateFlow<String?> = MutableStateFlow(model.id)
        override fun resolve(id: String): Flow<ModelGateState> =
            flowOf(if (id == model.id) ModelGateState.Ready(model) else ModelGateState.Unresolved)
        override suspend fun recordUsed(spec: ModelSpec): Unit = Unit

        fun choose(card: ModelCard) {
            selection.value = DocState.Ready(
                ModelSelection(lastUsedModelId = card.id, cards = mapOf(card.id to card)),
            )
        }
    }

    /** The chosen [chosen] is gone and the reroute setting stood [model] in for it. */
    private class ReroutedRegistry(private val model: ChatModelSpec, private val chosen: ModelCard) :
        FakeModelRegistryRepository(mapOf(model.id to model)) {
        override val selection: StateFlow<DocState<ModelSelection>> = MutableStateFlow(
            DocState.Ready(ModelSelection(lastUsedModelId = chosen.id, cards = mapOf(chosen.id to chosen))),
        )
        override val lastUsedModelIdFlow: StateFlow<String?> = MutableStateFlow(chosen.id)
        override fun resolve(id: String): Flow<ModelGateState> =
            flowOf(ModelGateState.Ready(model, reroutedFrom = chosen))
        override suspend fun recordUsed(spec: ModelSpec): Unit = error("a rerouted turn must not be recorded")
    }

    private class SingleSessionEngine(private val session: ChatSession) : LlmEngineRepository {
        override val loadedModelIdFlow: StateFlow<String?> = MutableStateFlow(null)
        override val loadedModelId: String? = null
        override val loadedAcceleratorFlow: StateFlow<Accelerator?> = MutableStateFlow(null)
        override suspend fun hydrateSpec(spec: ChatModelSpec) = Unit
        override suspend fun <T> withLifecycleLock(block: suspend () -> T): T = block()
        override suspend fun ensureLoaded(spec: ChatModelSpec, config: ChatGenerationConfig?) = Unit
        override suspend fun load(spec: ChatModelSpec, config: ChatGenerationConfig?) = Unit
        override suspend fun unload() = Unit
        override fun newChatSession(
            spec: ChatModelSpec,
            initialMessages: List<AideMessage>,
            tools: List<AideTool>,
            systemInstruction: String?,
            config: ChatGenerationConfig,
            dispatcher: ToolDispatcher,
            activationState: ToolActivationState?,
        ): ChatSession = session
        override fun engineGenerate(prompt: String, config: ChatGenerationConfig): Flow<String> = flow { }
    }

    private object NoResidency : ResidencyManager {
        override suspend fun acquire(model: ResidentModel): ResidencyHandle = object : ResidencyHandle {
            override suspend fun release(keepAliveMs: Long) = Unit
        }
        override fun residents(): List<ResidencyManager.Resident> = emptyList()
        override fun onTrimMemory(level: Int) = Unit
    }

    private object EmptyToolBundleFactory : ToolBundleFactory {
        override fun build(
            providerId: ProviderId,
            supportsTools: Boolean,
            enabledGated: Set<ToolGate>,
            enabledCategories: Set<ToolCategory>,
            surface: Surface,
        ): ToolBundle = ToolBundle(emptyList(), null, ToolActivationState())
    }

    private object OffWebSearchResolver : WebSearchResolver {
        override suspend fun resolve(): WebSearchProvider =
            throw UnsupportedOperationException("web search is off in this test")
        override fun activeProviderNameFlow(): Flow<String> = flow { }
        override fun allProviders(): List<WebSearchProvider> = emptyList()
    }

    /** Everything [ModelStorage] exposes that a remote-spec turn never reaches. */
    private object DownloadedStorage : ModelStorage {
        override val changes: Flow<Unit> get() = flow { }
        override suspend fun isDownloaded(spec: ModelSpec): Boolean = true
        override suspend fun modelFile(spec: ModelSpec) = unsupported()
        override suspend fun partFile(spec: ModelSpec) = unsupported()
        override suspend fun importTarget(spec: ModelSpec) = unsupported()
        override suspend fun hasUpdate(spec: ModelSpec) = unsupported()
        override suspend fun isPresent(spec: ModelSpec) = unsupported()
        override suspend fun downloadedBytes(spec: ModelSpec) = unsupported()
        override suspend fun delete(spec: ModelSpec) = unsupported()
        override suspend fun deleteFile(spec: ModelSpec) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used in this test")
    }

    private object NoImageStore : ImageAttachmentStore {
        override suspend fun importFromUri(uriString: String): String = uriString
        override val supportsCameraCapture: Boolean = false
        override fun newCameraCapture(): CameraCapture =
            throw UnsupportedOperationException("not used in this test")
        override suspend fun compressInPlace(path: String) = Unit
        override suspend fun delete(path: String) = Unit
    }

    /** Never touched — no test stages a file attachment — but the constructor wants real paths. */
    private object UnusedPaths : PlatformPaths {
        override val filesDir = "/tmp/aide-chat-vm-test".toPath()
        override val cacheDir = "/tmp/aide-chat-vm-test".toPath()
    }

    private object NoDictation : DictationController {
        private val idle = MutableStateFlow(DictationState.Idle)
        override fun stateFor(surface: DictationSurfaceId): StateFlow<DictationState> = idle
        override fun registerSurface(surface: DictationSurfaceId) = Unit
        override fun unregisterSurface(surface: DictationSurfaceId) = Unit
        override fun toggle(surface: DictationSurfaceId, sink: DictationSink, micAllowed: Boolean) = Unit
        override fun stop(surface: DictationSurfaceId?) = Unit
    }

    private class NoClipRecorder : MicClipRecorder {
        override val isRecording: StateFlow<Boolean> = MutableStateFlow(false)
        override val error: StateFlow<String?> = MutableStateFlow(null)
        override fun start() = Unit
        override suspend fun stop(): String? = null
        override fun cancel() = Unit
    }

    private object GrantEverything : RuntimePermissionGate {
        override fun isGranted(permission: AppPermission): Boolean = true
        override fun isGranted(special: SpecialPermission): Boolean = true
        override suspend fun ensure(permission: AppPermission, showRationale: Boolean): PermissionResult =
            PermissionResult.Granted
        override suspend fun ensureSpecial(special: SpecialPermission): PermissionResult =
            PermissionResult.Granted
    }

    private companion object {
        val TEST_MODEL = RemoteLlmModel(
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
    }
}
