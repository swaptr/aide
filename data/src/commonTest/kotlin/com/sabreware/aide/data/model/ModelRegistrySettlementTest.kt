package com.sabreware.aide.data.model

import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.download.DownloadStatus
import com.sabreware.aide.core.domain.chat.AideMessage
import com.sabreware.aide.core.domain.llm.ChatGenerationConfig
import com.sabreware.aide.core.domain.llm.ChatSession
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.ProviderManagement
import com.sabreware.aide.core.domain.llm.RemoteCatalogState
import com.sabreware.aide.core.domain.model.Accelerator
import com.sabreware.aide.core.domain.model.ChatCapabilities
import com.sabreware.aide.core.domain.model.ChatModelSpec
import com.sabreware.aide.core.domain.model.LocalLlmModel
import com.sabreware.aide.core.domain.model.ModelArtifact
import com.sabreware.aide.core.domain.model.ModelBackend
import com.sabreware.aide.core.domain.model.ModelCard
import com.sabreware.aide.core.domain.model.ModelSelection
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.fakes.FakeModelSelectionStore
import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.fakes.FakeLabelStore
import com.sabreware.aide.core.domain.fakes.FakeProviderDirectory
import com.sabreware.aide.core.domain.connection.ConnectionKind
import com.sabreware.aide.core.domain.connection.ProviderInfo
import com.sabreware.aide.core.domain.label.LabelSubject
import com.sabreware.aide.core.domain.label.Labels
import com.sabreware.aide.core.domain.model.ModelFallback
import com.sabreware.aide.core.domain.model.ModelFallbackPrefs
import com.sabreware.aide.core.domain.model.ImportedModelEntry
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.ModelSpec
import com.sabreware.aide.core.domain.model.ModelGateState
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.model.ModelSummary
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ToolActivationState
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.data.catalog.RemoteCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path
import okio.Path.Companion.toPath

/**
 * What the registry is allowed to SAY while its providers are still answering.
 *
 * `models == null` means "not resolved this session"; every surface holds its skeleton on it. The moment it
 * goes non-null the chat header commits — it names a model or it offers "Set up a model to begin". So a
 * snapshot published before every provider has a real answer is not a slightly-early snapshot, it is a
 * WRONG one, and it is the flash this pins.
 */
class ModelRegistrySettlementTest {

    private fun caps(id: String): ChatCapabilities? =
        ChatCapabilities(toolsLocal = true, maxContext = 8192, maxOutput = 4096)

    private fun spec(id: String, provider: ProviderId = OPENAI): ChatModelSpec = RemoteCatalog.openAiSpec(provider, id, ::caps)

    private class FakeManagement(initial: RemoteCatalogState) : ProviderManagement {
        val state = MutableStateFlow(initial)
        override val remoteCatalogFlow: Flow<RemoteCatalogState> = state
        override suspend fun refreshCatalog() = Unit
        override suspend fun testConnection() =
            com.sabreware.aide.core.domain.provider.ConnectionTestResult.Failed("n/a")
    }

    /**
     * [servesChat] stands in for the role interface a real provider implements ([ChatProvider] and
     * friends): `Manageable` cross-cuts modality, so a vendor can have a refreshable catalog and no text
     * models at all.
     */
    private class FakeManageable(
        override val id: ProviderId,
        override val management: ProviderManagement,
        val servesChat: Boolean = true,
    ) : Manageable

    private object NoCatalog : ModelCatalog {
        override val models: List<ChatModelSpec> = emptyList()
        override fun findById(id: String): ChatModelSpec? = null
    }

    private object NoScheduler : DownloadScheduler {
        override fun enqueue(kind: String, id: String): String = id
        override fun observe(kind: String, id: String): Flow<DownloadStatus> = flowOf(DownloadStatus.Idle(id))
        override fun pause(kind: String, id: String) = Unit
        override fun cancel(kind: String, id: String) = Unit
    }

    private object NoImports : ModelImportRepository {
        override fun observe(): Flow<List<ImportedModelEntry>> = flowOf(emptyList())
        override suspend fun remove(id: String) = Unit
    }

    private object NoStorage : ModelStorage {
        override val changes: Flow<Unit> = emptyFlow()
        override suspend fun modelFile(spec: ModelSpec): Path = "/none".toPath()
        override suspend fun partFile(spec: ModelSpec): Path = "/none".toPath()
        override suspend fun importTarget(spec: ModelSpec): Path = "/none".toPath()
        override suspend fun isDownloaded(spec: ModelSpec): Boolean = !spec.requiresDownload
        override suspend fun hasUpdate(spec: ModelSpec): Boolean = false
        override suspend fun isPresent(spec: ModelSpec): Boolean = false
        override suspend fun downloadedBytes(spec: ModelSpec): Long = 0
        override suspend fun delete(spec: ModelSpec): Boolean = false
        override suspend fun deleteFile(spec: ModelSpec): Boolean = false
    }

    private object NoEngine : LlmEngineRepository {
        override val loadedModelIdFlow = MutableStateFlow<String?>(null)
        override val loadedModelId: String? = null
        override val loadedAcceleratorFlow = MutableStateFlow<Accelerator?>(null)
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
        ): ChatSession = error("not used")
        override fun engineGenerate(prompt: String, config: ChatGenerationConfig): Flow<String> = emptyFlow()
    }

    /**
     * What a SUBSCRIBED surface sees. [ModelRegistryRepositoryImpl.models] shares `WhileSubscribed` (so it
     * holds its `null` seed until something collects) and runs its upstream on `Dispatchers.Default` (so
     * the test scheduler cannot step it) — this waits on the real thing, with a real timeout.
     *
     * A [timeoutMs] expiry IS the assertion for the unresolved cases: no snapshot was published.
     */
    private suspend fun ModelRegistryRepositoryImpl.firstSnapshot(timeoutMs: Long): List<ModelSummary>? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) { models.filterNotNull().first() }
        }

    private fun registry(
        scope: kotlinx.coroutines.CoroutineScope,
        vararg providers: FakeManageable,
        selection: ModelSelectionStore = FakeModelSelectionStore(),
        catalog: ModelCatalog = NoCatalog,
        storage: ModelStorage = NoStorage,
        fallback: ModelFallback = ModelFallback.Never,
        // Every cloud provider is a connection: the DYNAMIC half of the registry. Null = connections unread.
        connected: MutableStateFlow<List<Manageable>?> = MutableStateFlow(providers.toList()),
        labels: FakeLabelStore = FakeLabelStore(),
        directory: FakeProviderDirectory = FakeProviderDirectory(),
    ) =
        ModelRegistryRepositoryImpl(
            scheduler = NoScheduler,
            engineRepo = NoEngine,
            storage = storage,
            selectionStore = selection,
            importRepo = NoImports,
            modelCatalog = catalog,
            manageables = ManageableRegistry(emptyList(), connected),
            appScope = scope,
            prefs = FakePreferenceStore(ModelFallbackPrefs.Policy to fallback),
            serves = { (it as FakeManageable).servesChat },
            labels = labels,
            directory = directory,
        )

    private companion object {
        // Cloud provider ids are connection ids.
        val OPENAI = ProviderId("openai-test01")
        val GEMINI = ProviderId("gemini-test02")

        // Long enough that a snapshot which is going to be published has been.
        const val SETTLE_MS = 3_000L
        // Short: nothing should ever arrive, so this is pure test latency.
        const val HOLD_MS = 400L
    }

    /** The baseline: nothing has reported, so there is no snapshot to publish. */
    @Test
    fun `stays unresolved while a provider has not reported`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Unknown)
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai))

        assertNull(reg.firstSnapshot(HOLD_MS), "Unknown means not-yet-answered; a snapshot here is a guess")
    }

    /**
     * The regression. A provider whose FIRST fetch is in flight carries `Refreshing(previous = emptyList())`:
     * no last-good to answer with, so it knows exactly as much as [RemoteCatalogState.Unknown] does.
     * Publishing on it says "you have no models" about a provider that is configured fine — the chat header
     * commits to "No model" and the empty state offers "Set up a model to begin" for the whole first fetch.
     */
    @Test
    fun `stays unresolved while a provider is mid-first-fetch with nothing cached`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Refreshing(emptyList()))
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai))

        assertNull(reg.firstSnapshot(HOLD_MS), "a first fetch with no last-good is not an answer")
    }

    /** A refresh over a last-good listing IS an answer — the previous specs stay on screen. */
    @Test
    fun `a refresh over a last-good listing resolves from that listing`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Refreshing(listOf(spec("gpt-5"))))
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai))

        assertEquals(listOf("openai-test01:gpt-5"), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id })
    }

    /** A dead network must terminate the skeleton: Failed is a settled answer, empty or not. */
    @Test
    fun `a failed first fetch resolves to empty rather than hanging`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Failed(emptyList(), "offline"))
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai))

        assertEquals(emptyList(), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id })
    }

    /** No credentials is a definite answer too. */
    @Test
    fun `an unconfigured provider resolves`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Unconfigured)
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai))

        assertEquals(emptyList(), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id })
    }

    /** One straggler holds the whole snapshot — the merged list is all-or-nothing by design. */
    @Test
    fun `one unsettled provider holds the snapshot back`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L))
        val gemini = FakeManagement(RemoteCatalogState.Unknown)
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            FakeManageable(GEMINI, gemini),
        )

        assertNull(reg.firstSnapshot(HOLD_MS))

        gemini.state.value = RemoteCatalogState.Unconfigured
        assertEquals(listOf("openai-test01:gpt-5"), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id })
    }

    // --- The gate: scoped to the ONE model the user chose ---------------------------------------------------

    private val localSpec = LocalLlmModel(
        id = "gemma-local",
        displayName = "Gemma Local",
        family = "gemma",
        params = "1B",
        quantization = "q4",
        artifact = ModelArtifact(downloadUrl = "https://host/m.bin", fileName = "m.bin", sizeBytes = null),
        minRamGb = 2,
        recommendedRamGb = 4,
        capabilities = ChatCapabilities(maxContext = 4096, maxOutput = 1024),
        defaultBackend = ModelBackend.CPU,
        licenseName = "test",
        licenseUrl = "https://example.com/license",
        sourceUrl = "https://example.com/source",
    )

    private val otherLocal = localSpec.copy(id = "qwen-local", displayName = "Qwen Local")

    private inner class OneLocal(vararg extra: ChatModelSpec) : ModelCatalog {
        override val models: List<ChatModelSpec> = listOf(localSpec) + extra
        override fun findById(id: String): ChatModelSpec? = models.firstOrNull { it.id == id }
    }

    /** On disk: every local model in [presentIds] (all of them when [present] and no ids are named). */
    private class Disk(var present: Boolean, private val presentIds: Set<String> = emptySet()) : ModelStorage by NoStorage {
        override suspend fun isDownloaded(spec: ModelSpec): Boolean =
            !spec.requiresDownload || (if (presentIds.isEmpty()) present else spec.id in presentIds)
    }

    private fun chose(spec: ChatModelSpec) = ModelSelection().withActive(
        com.sabreware.aide.core.domain.model.Modality.Chat, spec.id,
    ).withCard(ModelCard.of(spec))

    private suspend fun ModelRegistryRepositoryImpl.settledGate(): ModelGateState? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(SETTLE_MS) { gateStateFlow.first { it != ModelGateState.Unresolved } }
        }

    /** Until the choice document is read the gate cannot say anything — least of all "set up a model". */
    @Test
    fun `the gate is Unresolved until the choice document has been read`() = runTest {
        val selection = FakeModelSelectionStore(loaded = false)
        val openai = FakeManagement(RemoteCatalogState.Unconfigured)
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai), selection = selection)

        assertEquals(ModelGateState.Unresolved, reg.gateStateFlow.value)
        selection.finishLoading()
        assertEquals(ModelGateState.NoModel, reg.settledGate())
    }

    /**
     * Nothing chosen is a settled answer the moment the document is read — nothing here ever chooses on
     * the user's behalf — so the call to action must not wait on a provider that has not answered yet.
     */
    @Test
    fun `nothing chosen is NoModel whatever the providers are doing`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Unknown)
        val reg = registry(backgroundScope, FakeManageable(OPENAI, openai))

        assertEquals(ModelGateState.NoModel, reg.settledGate())
    }

    /** The on-device pick answers from the disk alone, while every cloud provider is still silent. */
    @Test
    fun `a chosen local model is Ready without waiting for any cloud provider`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Unknown)
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(chose(localSpec)),
            catalog = OneLocal(),
            storage = Disk(present = true),
        )

        assertEquals(ModelGateState.Ready(localSpec), reg.settledGate())
    }

    /** Deleted weights say which model is gone — never NoModel, never a cloud model in its place. */
    @Test
    fun `a chosen local model whose weights are gone is Missing, not a substitute`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(chose(localSpec)),
            catalog = OneLocal(),
            storage = Disk(present = false),
        )

        assertEquals(ModelGateState.Missing(ModelCard.of(localSpec)), reg.settledGate())
    }

    // --- Reroute: only as far as the user's setting allows, and never rewriting the choice ------------------

    /** The default: an unusable pick is reported, never substituted — even with other models on hand. */
    @Test
    fun `with rerouting off a missing model stays Missing`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(chose(localSpec)),
            catalog = OneLocal(otherLocal),
            storage = Disk(present = false, presentIds = setOf(otherLocal.id)),
        )

        assertEquals(ModelGateState.Missing(ModelCard.of(localSpec)), reg.settledGate())
    }

    /** Same kind keeps an on-device pick on-device, skipping a cloud model that is right there. */
    @Test
    fun `same-kind rerouting substitutes on-device for on-device, not cloud`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(chose(localSpec)),
            catalog = OneLocal(otherLocal),
            storage = Disk(present = false, presentIds = setOf(otherLocal.id)),
            fallback = ModelFallback.SameKind,
        )

        assertEquals(ModelGateState.Ready(otherLocal, reroutedFrom = ModelCard.of(localSpec)), reg.settledGate())
    }

    /** With nothing of the same kind, same-kind rerouting reports the gap instead of crossing to the cloud. */
    @Test
    fun `same-kind rerouting never crosses from on-device to cloud`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(chose(localSpec)),
            catalog = OneLocal(),
            storage = Disk(present = false),
            fallback = ModelFallback.SameKind,
        )

        assertEquals(ModelGateState.Missing(ModelCard.of(localSpec)), reg.settledGate())
    }

    /** "Any model" may cross kinds — the user opted in — and says whom it stands in for. */
    @Test
    fun `any-model rerouting may use a cloud model and names the missing one`() = runTest {
        val gpt = spec("gpt-5")
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(gpt), 1L))
        val selection = FakeModelSelectionStore(chose(localSpec))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = selection,
            catalog = OneLocal(),
            storage = Disk(present = false),
            fallback = ModelFallback.AnyModel,
        )

        assertEquals(ModelGateState.Ready(gpt, reroutedFrom = ModelCard.of(localSpec)), reg.settledGate())
        assertEquals(localSpec.id, selection.current().activeFor(com.sabreware.aide.core.domain.model.Modality.Chat), "the choice is untouched")
    }

    /** A remote pick waits for ITS provider only; a slow unrelated vendor does not hold it. */
    @Test
    fun `a chosen remote model waits only for its own provider`() = runTest {
        val gpt = spec("gpt-5")
        val openai = FakeManagement(RemoteCatalogState.Unknown)
        val gemini = FakeManagement(RemoteCatalogState.Unknown)
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            FakeManageable(GEMINI, gemini),
            selection = FakeModelSelectionStore(chose(gpt)),
        )

        // Its own provider has not answered: that is "not yet", not "missing".
        assertEquals(ModelGateState.Unresolved, reg.gateStateFlow.value)
        openai.state.value = RemoteCatalogState.Ready(listOf(gpt), 1L)
        assertEquals(ModelGateState.Ready(gpt), reg.settledGate())
    }

    /**
     * The chat picker records a LAST-USED model, not the chat slot. The keyboard and the assistant must see
     * that same choice — they used to read only the slot, and told a cloud-only user they had no model.
     */
    @Test
    fun `the gate serves the chat header's choice, not only the chat slot`() = runTest {
        val gpt = spec("gpt-5")
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(gpt), 1L))
        val lastUsedOnly = ModelSelection().withUsed(ModelCard.of(gpt), com.sabreware.aide.core.domain.model.ProviderTier.of(gpt))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(lastUsedOnly),
        )

        assertEquals(ModelGateState.Ready(gpt), reg.settledGate())
    }

    /** A pick its provider no longer lists is reported as missing, by name. */
    @Test
    fun `a chosen remote model its provider no longer lists is Missing`() = runTest {
        val gpt = spec("gpt-5")
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-4o")), 1L))
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            selection = FakeModelSelectionStore(chose(gpt)),
        )

        assertEquals(ModelGateState.Missing(ModelCard.of(gpt)), reg.settledGate())
    }

    /**
     * A vendor whose catalog this registry can neither list nor send to must not gate it.
     *
     * `Manageable` is modality-agnostic, so the same OpenAI-shaped credentials back an image and a
     * transcription catalog too. Waiting on those to answer before the chat header may name a model means
     * a slow image catalog holds the text UI's skeleton open — and an empty one reports "no model" — for a
     * reason no amount of setting up a chat model would fix.
     */
    @Test
    fun `a provider that does not serve this modality never gates it`() = runTest {
        val openai = FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L))
        val images = FakeManagement(RemoteCatalogState.Unknown)
        val reg = registry(
            backgroundScope,
            FakeManageable(OPENAI, openai),
            FakeManageable(ProviderId("openai-images0"), images, servesChat = false),
        )

        assertEquals(listOf("openai-test01:gpt-5"), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id })
    }

    // --- The provider set is dynamic: connections come and go ------------------------------------------------

    /** Unread connections are not "no connections": nothing may be published while they are unknown. */
    @Test
    fun `stays unresolved while the connections have not been read`() = runTest {
        val connected = MutableStateFlow<List<Manageable>?>(null)
        val reg = registry(backgroundScope, connected = connected)

        assertNull(reg.firstSnapshot(HOLD_MS), "unread connections would read as 'no cloud models'")

        connected.value = emptyList()
        assertEquals(emptyList(), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id }, "no connections IS an answer")
    }

    /** Removing the last connection must take its models with it, not pin the last snapshot on screen. */
    @Test
    fun `removing the last connection drops its models`() = runTest {
        val openai = FakeManageable(OPENAI, FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L)))
        val connected = MutableStateFlow<List<Manageable>?>(listOf(openai))
        val reg = registry(backgroundScope, connected = connected)

        assertEquals(listOf("openai-test01:gpt-5"), reg.firstSnapshot(SETTLE_MS)?.map { it.spec.id })

        connected.value = emptyList()
        val after = withContext(Dispatchers.Default) {
            withTimeoutOrNull(SETTLE_MS) { reg.models.first { it != null && it.isEmpty() } }
        }
        assertEquals(emptyList(), after?.map { it.spec.id })
    }

    /**
     * A connection added after the first settle starts mid-first-fetch. Re-nulling the aggregate for it would
     * put every surface back behind a skeleton for a model none of them is using.
     */
    @Test
    fun `a connection added after settling does not unsettle the snapshot`() = runTest {
        val openai = FakeManageable(OPENAI, FakeManagement(RemoteCatalogState.Ready(listOf(spec("gpt-5")), 1L)))
        val gemini = FakeManagement(RemoteCatalogState.Refreshing(emptyList()))
        val connected = MutableStateFlow<List<Manageable>?>(listOf(openai))
        val reg = registry(backgroundScope, connected = connected)
        val seen = mutableListOf<List<String>?>()

        val last = withContext(Dispatchers.Default) {
            // One subscriber for the whole story, recording every snapshot it is shown.
            val recorder = launch { reg.models.collect { snapshot -> seen += snapshot?.map { it.spec.id } } }
            withTimeoutOrNull(SETTLE_MS) { reg.models.first { it != null } }
            connected.value = listOf(openai, FakeManageable(GEMINI, gemini))
            delay(HOLD_MS)
            gemini.state.value = RemoteCatalogState.Ready(listOf(spec("gemini-3", GEMINI)), 1L)
            val landed = withTimeoutOrNull(SETTLE_MS) {
                reg.models.first { snapshot -> snapshot.orEmpty().any { it.provider == GEMINI } }
            }
            recorder.cancelAndJoin()
            landed?.map { it.spec.id }
        }

        val afterSettle = seen.dropWhile { it == null }
        assertTrue(afterSettle.isNotEmpty(), "the registry settled")
        assertTrue(afterSettle.none { it == null }, "re-nulled after settling: $seen")
        assertEquals(listOf("openai-test01:gpt-5", "gemini-test02:gemini-3"), last)
    }

    /** The user's name for a model and for its connection are applied where the rows are built. */
    @Test
    fun `rows carry the alias and the connection's name`() = runTest {
        val gpt = spec("gpt-5")
        val openai = FakeManageable(OPENAI, FakeManagement(RemoteCatalogState.Ready(listOf(gpt), 1L)))
        val labels = FakeLabelStore(Labels().rename(LabelSubject.model(gpt.id), "Work GPT", originalName = gpt.displayName))
        val directory = FakeProviderDirectory(
            mapOf(OPENAI.value to ProviderInfo(OPENAI, name = "My OpenAI", kind = ConnectionKind.Cloud)),
        )
        val reg = registry(backgroundScope, openai, labels = labels, directory = directory)

        val row = reg.firstSnapshot(SETTLE_MS)?.single()
        assertEquals("Work GPT", row?.spec?.displayName)
        assertEquals("gpt-5", row?.originalName)
        assertEquals("My OpenAI", row?.sourceName)
    }
}
