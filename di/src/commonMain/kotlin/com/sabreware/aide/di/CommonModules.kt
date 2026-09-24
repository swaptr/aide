package com.sabreware.aide.di

import com.sabreware.aide.ui.labels.LabelsViewModel
import com.sabreware.aide.ui.models.connections.ConnectionsViewModel
import com.sabreware.aide.core.domain.connection.ConnectionDocuments
import com.sabreware.aide.core.domain.connection.ConnectionRepository
import com.sabreware.aide.core.domain.connection.ConnectionRuntime
import com.sabreware.aide.core.domain.connection.ConnectionRuntimes
import com.sabreware.aide.core.domain.connection.ProviderDirectory
import com.sabreware.aide.data.connection.ProviderDirectoryImpl
import com.sabreware.aide.core.domain.connection.Vendor
import com.sabreware.aide.core.domain.connection.VendorRegistry
import com.sabreware.aide.core.domain.label.LabelDocuments
import com.sabreware.aide.core.domain.label.LabelStore
import com.sabreware.aide.data.connection.ConnectionRepositoryImpl
import com.sabreware.aide.data.image.ConnectedImageModelCatalog
import com.sabreware.aide.data.label.DocumentLabelStore
import com.sabreware.aide.data.llm.vendor.AnthropicVendor
import com.sabreware.aide.data.llm.vendor.ConnectionRuntimesImpl
import com.sabreware.aide.data.llm.vendor.ElevenLabsVendor
import com.sabreware.aide.data.llm.vendor.GeminiVendor
import com.sabreware.aide.data.llm.vendor.OpenAiCompatibleVendor
import com.sabreware.aide.data.llm.vendor.VendorEnvironment
import com.sabreware.aide.data.speech.cloud.ConnectedCloudSpeechCatalog
import com.sabreware.aide.data.catalog.RemoteCatalogCache
import com.sabreware.aide.data.connector.directory.ConnectorDirectoryCache
import com.sabreware.aide.data.connector.directory.DocumentConnectorDirectoryCache
import com.sabreware.aide.data.llm.CatalogRefreshBootstrap
import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.common.di.contributes
import com.sabreware.aide.core.common.di.AISDK_HTTP
import com.sabreware.aide.core.common.di.APPLICATION_SCOPE
import com.sabreware.aide.core.common.di.APP_SCOPE
import com.sabreware.aide.core.common.persist.DocumentStore
import com.sabreware.aide.core.common.persist.PersistedDocument
import com.sabreware.aide.core.common.persist.documentPath
import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.model.ModelDocuments
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import com.sabreware.aide.core.domain.model.SamplerOverridesStore
import com.sabreware.aide.data.model.DocumentModelSelectionStore
import com.sabreware.aide.data.model.DocumentSamplerOverridesStore
import org.koin.core.scope.Scope
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.core.common.di.CONNECTOR_HTTP
import com.sabreware.aide.core.common.di.CONNECTOR_IMAGE_LOADER
import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.common.di.SEARCH_HTTP
import com.sabreware.aide.core.common.di.USER_PREFS
import com.sabreware.aide.core.common.di.WEB_FETCH_PROVIDERS
import com.sabreware.aide.core.common.di.WEB_SEARCH_PROVIDERS
import com.sabreware.aide.core.common.media.FileAttachmentStore
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.storage.BundledAssetReader
import com.sabreware.aide.core.designsystem.ComposeResourceAssetReader
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.chat.ChatRepository
import com.sabreware.aide.core.domain.chat.ChatTranscriptFactory
import com.sabreware.aide.core.domain.connector.ConnectCoordinator
import com.sabreware.aide.core.domain.connector.ConnectorCatalog
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectory
import com.sabreware.aide.core.domain.connector.directory.ConnectorDirectorySelection
import com.sabreware.aide.core.domain.connector.oauth.ClientRegistrar
import com.sabreware.aide.core.domain.connector.oauth.OAuthClientStore
import com.sabreware.aide.core.domain.connector.oauth.OAuthDiscovery
import com.sabreware.aide.core.domain.connector.oauth.OAuthTokenClient
import com.sabreware.aide.core.domain.custom.CustomInstructionRepository
import com.sabreware.aide.core.domain.image.ImageProvider
import com.sabreware.aide.core.domain.image.ImageProviderRegistry
import com.sabreware.aide.core.domain.io.MicClipRecorder
import com.sabreware.aide.core.domain.io.ReasonEvent
import com.sabreware.aide.core.domain.io.VoiceInputChannel
import com.sabreware.aide.core.domain.io.VoiceReasoner
import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.llm.ChatProviderRegistry
import com.sabreware.aide.core.domain.llm.LlmEngineRepository
import com.sabreware.aide.core.domain.llm.Manageable
import com.sabreware.aide.core.domain.llm.ManageableRegistry
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.llm.dispatch.IdempotencyCache
import com.sabreware.aide.core.domain.llm.dispatch.RateLimiter
import com.sabreware.aide.core.domain.llm.dispatch.ToolDispatcher
import com.sabreware.aide.core.domain.llm.dispatch.Tracer
import com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate
import com.sabreware.aide.core.domain.mcp.McpClient
import com.sabreware.aide.core.domain.mcp.McpConnections
import com.sabreware.aide.core.domain.mcp.McpServerRepository
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelRegistryRepository
import com.sabreware.aide.core.domain.speech.SpeechAssetRepository
import com.sabreware.aide.core.domain.notification.ScheduledNotificationStore
import com.sabreware.aide.core.domain.search.ProviderChain
import com.sabreware.aide.core.domain.search.WebFetchProvider
import com.sabreware.aide.core.domain.search.WebFetchProviderId
import com.sabreware.aide.core.domain.search.WebFetcher
import com.sabreware.aide.core.domain.search.WebSearchCredentialsRepository
import com.sabreware.aide.core.domain.search.WebSearchProvider
import com.sabreware.aide.core.domain.search.WebSearchProviderId
import com.sabreware.aide.core.domain.search.WebSearchResolver
import com.sabreware.aide.core.domain.speech.CloudSpeechCatalog
import com.sabreware.aide.core.domain.speech.MicActivityMonitor
import com.sabreware.aide.core.domain.speech.SpeechEngineRepository
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechProviderRegistry
import com.sabreware.aide.core.domain.tools.CalculatorToolset
import com.sabreware.aide.core.domain.tools.TimeToolset
import com.sabreware.aide.core.domain.tools.ToolBundleFactory
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.ToolsetRegistry
import com.sabreware.aide.core.domain.tools.WebToolset
import com.sabreware.aide.core.domain.tools.fs.FileSystemRoots
import com.sabreware.aide.core.domain.tools.fs.FileSystemToolset
import com.sabreware.aide.core.domain.tools.phone.ContactPickGate
import com.sabreware.aide.core.domain.usecase.AcquireModelUseCase
import com.sabreware.aide.core.domain.presence.SurfacePresence
import com.sabreware.aide.core.domain.usecase.ArchiveChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.CancelDownloadUseCase
import com.sabreware.aide.core.domain.usecase.CreateChatUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteChatsWithFallbackUseCase
import com.sabreware.aide.core.domain.usecase.DeleteMessagesFromUseCase
import com.sabreware.aide.core.domain.usecase.DeleteModelUseCase
import com.sabreware.aide.core.domain.usecase.DownloadModelUseCase
import com.sabreware.aide.core.domain.usecase.LoadModelUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatMessagesUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatUseCase
import com.sabreware.aide.core.domain.usecase.ObserveChatsUseCase
import com.sabreware.aide.core.domain.usecase.ObserveLastUsedForTierUseCase
import com.sabreware.aide.core.domain.usecase.ObserveModelsUseCase
import com.sabreware.aide.core.domain.usecase.PauseDownloadUseCase
import com.sabreware.aide.core.domain.usecase.RenameChatUseCase
import com.sabreware.aide.core.domain.usecase.ResolveActiveModelUseCase
import com.sabreware.aide.core.domain.usecase.SendChatMessageUseCase
import com.sabreware.aide.core.domain.usecase.SetChatArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetChatStarredUseCase
import com.sabreware.aide.core.domain.usecase.SetChatsArchivedUseCase
import com.sabreware.aide.core.domain.usecase.SetDefaultModelUseCase
import com.sabreware.aide.core.domain.usecase.StartDictationUseCase
import com.sabreware.aide.core.domain.usecase.UnloadModelUseCase
import com.sabreware.aide.data.chat.AideDatabase
import com.sabreware.aide.data.chat.ChatRepositoryImpl
import com.sabreware.aide.data.chat.ChatTranscriptFactoryImpl
import com.sabreware.aide.data.connector.AggregateConnectorCatalog
import com.sabreware.aide.data.connector.ConnectorDirectoryBootstrap
import com.sabreware.aide.data.connector.directory.CuratedConnectorDirectory
import com.sabreware.aide.data.connector.directory.DefaultConnectorDirectorySelection
import com.sabreware.aide.data.connector.directory.OfficialMcpRegistryDirectory
import com.sabreware.aide.data.connector.oauth.ClientRegistrarImpl
import com.sabreware.aide.data.connector.oauth.ConnectCoordinatorImpl
import com.sabreware.aide.data.connector.oauth.OAuthClientStoreImpl
import com.sabreware.aide.data.connector.oauth.OAuthDiscoveryImpl
import com.sabreware.aide.data.connector.oauth.OAuthTokenClientImpl
import com.sabreware.aide.data.connector.registry.McpRegistryClient
import com.sabreware.aide.data.custom.CustomInstructionRepositoryImpl
import com.sabreware.aide.core.domain.download.AssetSource
import com.sabreware.aide.core.domain.download.AssetSourceRegistry
import com.sabreware.aide.data.model.ModelAssetSource
import com.sabreware.aide.data.speech.SpeechAssetSource
import com.sabreware.aide.core.domain.image.ImageModelCatalog
import com.sabreware.aide.data.image.ImageToolset
import com.sabreware.aide.data.llm.LlmEngineRepositoryImpl
import com.sabreware.aide.data.connector.mcp.McpClientImpl
import com.sabreware.aide.data.connector.mcp.McpConnectionManager
import com.sabreware.aide.data.connector.mcp.McpReconnectBootstrap
import com.sabreware.aide.data.connector.mcp.McpServerRepositoryImpl
import com.sabreware.aide.data.model.ModelRegistryRepositoryImpl
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.data.notification.ScheduledNotificationStoreImpl
import com.sabreware.aide.core.domain.model.NativeLoadJournal
import com.sabreware.aide.core.domain.cache.CacheWarmup
import com.sabreware.aide.data.model.NativeCrashReportBootstrap
import com.sabreware.aide.data.model.PreferenceNativeLoadJournal
import com.sabreware.aide.data.prefs.DataStorePreferenceStore
import com.sabreware.aide.data.prefs.userPreferencesDataStore
import com.sabreware.aide.data.search.ChainedWebFetcher
import com.sabreware.aide.data.search.DuckDuckGoSearchClient
import com.sabreware.aide.data.search.ProviderChainImpl
import com.sabreware.aide.data.search.WebSearchCredentialsRepositoryImpl
import com.sabreware.aide.data.search.WebSearchResolverImpl
import com.sabreware.aide.data.search.providers.BraveWebSearchProvider
import com.sabreware.aide.data.search.providers.DuckDuckGoWebFetchProvider
import com.sabreware.aide.data.search.providers.DuckDuckGoWebSearchProvider
import com.sabreware.aide.data.search.providers.OllamaWebFetcher
import com.sabreware.aide.data.search.providers.OllamaWebSearchProvider
import com.sabreware.aide.data.search.providers.TavilyWebSearchProvider
import com.sabreware.aide.data.speech.SpeechEngineRepositoryImpl
import com.sabreware.aide.data.speech.io.MicClipRecorderImpl
import com.sabreware.aide.data.tools.ToolBundleFactoryImpl
import com.sabreware.aide.ui.app.AppViewModel
import com.sabreware.aide.ui.chat.ChatViewModel
import com.sabreware.aide.ui.chats.ChatsViewModel
import com.sabreware.aide.ui.custom.CustomInstructionViewModel
import com.sabreware.aide.ui.models.ModelsViewModel
import com.sabreware.aide.ui.settings.SettingsViewModel
import com.sabreware.aide.ui.settings.mcp.McpSettingsViewModel
import com.sabreware.aide.ui.settings.mcp.browse.ConnectorBrowseViewModel
import com.sabreware.aide.ui.settings.mcp.directories.ConnectorDirectoriesViewModel
import com.sabreware.aide.ui.settings.speech.SpeechSettingsViewModel
import com.sabreware.aide.ui.settings.tools.ToolsSettingsViewModel
import com.sabreware.aide.ui.settings.websearch.WebSearchSettingsViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Platform-agnostic Koin wiring (Phase 6a KMP split). Every definition here binds an impl that lives in
 * `:shared/commonMain` and references NO platform API (`androidContext()`/`androidApplication()`) and no
 * platform-only type in its lambda. Interface-typed / qualified `get()` dependencies whose PROVIDER lives
 * in a platform module (DataStore, SecureStore, AideDatabase, dispatchers, isolated HTTP,
 * the connector image loader, the speech/LLM engine impls) resolve across modules at startup — that is fine.
 *
 * The platform module (`androidPlatformModule` in `:app`; a desktop one later) supplies the rest. Together:
 * `appModules = commonModules + <platformModule>`. See CLAUDE.md, "The gating rule".
 */

/** Cross-cutting singles whose impls are common (isolated HTTP clients, MCP registry client, Room DAOs). */
private val commonCoreModule = module {
    // Isolated Ktor clients (engine injected per platform via KtorClientFactory.engineProvider).
    single(SEARCH_HTTP) { KtorClientFactory.finite() }
    single(AISDK_HTTP) { KtorClientFactory.streaming() }
    single(CONNECTOR_HTTP) { KtorClientFactory.finite() }

    single { McpRegistryClient { KtorClientFactory.finite() } }

    // Room DAOs — the AideDatabase provider is platform (needs the platform DB builder); the DAO accessors
    // are engine-agnostic, so they stay common and resolve the DB across modules.
    single { get<AideDatabase>().chatDao() }
}

/** Repositories / managers / providers whose impl is in commonMain (bound to their domain interface). */
private val commonDataModule = module {
    // Interface-bound repos / managers.
    single<ChatRepository> { ChatRepositoryImpl(get(), get(APPLICATION_SCOPE)) }
    singleOf(::CustomInstructionRepositoryImpl) { bind<CustomInstructionRepository>() }
    singleOf(::ChatTranscriptFactoryImpl) { bind<ChatTranscriptFactory>() }
    // One preferences file + the typed-key store over it. Built here, not per host: a new target supplies
    // PlatformPaths and needs no storage wiring. See docs/configuration-and-state.md.
    single(USER_PREFS) { userPreferencesDataStore(get()) }
    // createdAtStart: the theme and chat font are first-frame state, so the one small, local, crypto-free
    // prefs file starts reading inside startKoin — the same reason and bound as the selection document.
    single<PreferenceStore>(createdAtStart = true) { DataStorePreferenceStore(get(USER_PREFS), get(APPLICATION_SCOPE)) }
    // Durable breadcrumb for native model loads. Bound for BOTH apps: desktop runs Sherpa through the same
    // JNI, so a native crash there is just as silent.
    single<NativeLoadJournal> { PreferenceNativeLoadJournal(get()) }
    // Assets packaged with the app (today: the models.dev registry snapshot) are read through this port, so
    // layers below the UI never reach up into the Compose-resources bundle that carries them.
    single<BundledAssetReader> { ComposeResourceAssetReader }
    // Clip recording rides the platform's AudioCapturer (already needed for dictation) — no per-platform
    // recorder, so any target that can dictate can also attach an audio clip.
    single<MicClipRecorder> { MicClipRecorderImpl(get(), get(), get(), get(APPLICATION_SCOPE)) }
    // The one live-mic signal every level meter reads, whichever source is actually holding the mic.
    singleOf(::MicActivityMonitor)
    single { FileAttachmentStore(get(), get(), get(IO)) }
    // ModelRegistryRepository — WRITE-ONCE (impl in commonMain). It lists/resolves models + tracks the
    // gate/residency state, orchestrating only interfaces: the platform leaf ports ModelStorage /
    // ModelCatalog / ModelImportRepository (Android only — desktop binds none) + the
    // shared LlmEngineRepository + the ManageableRegistry. Adding a platform = supply those leaf ports,
    // nothing else.
    // Startup document: the user's model choices. `createdAtStart` is the
    // point — the read begins inside startKoin (Application.onCreate on Android, which the IME's process
    // runs too; main() on desktop), so the file is in memory before any surface draws. One small local
    // file: no network, no crypto, no registry work. See CLAUDE.md "Startup documents".
    single<ModelSelectionStore>(createdAtStart = true) {
        DocumentModelSelectionStore(documentStore(ModelDocuments.Selection))
    }
    // User-authored documents, read on first use (not first-frame state, so not createdAtStart).
    single<SamplerOverridesStore> { DocumentSamplerOverridesStore(documentStore(ModelDocuments.SamplerOverrides)) }
    single<ModelRegistryRepository> {
        // downloadRepo, engineRepo, storage, selection, importRepo, modelCatalog, manageables, appScope
        ModelRegistryRepositoryImpl(
            get(), get(), get(), get(), get(), get(), get(), get(APPLICATION_SCOPE),
            modality = Modality.Chat,
            prefs = get(),
            // The chat registry waits on chat vendors and no others. A Manageable that serves only images
            // or transcription (OpenAiModalityProvider's siblings) has a catalog this snapshot can neither
            // use nor send to, and gating the chat header on it would be the startup flash all over again,
            // for a reason the user could never act on.
            serves = { it is ChatProvider },
            labels = get(),
            directory = get(),
        )
    }
    singleOf(::ScheduledNotificationStoreImpl) { bind<ScheduledNotificationStore>() }
    single<McpServerRepository> { McpServerRepositoryImpl(get(), get(APPLICATION_SCOPE)) }
    single<WebSearchCredentialsRepository> { WebSearchCredentialsRepositoryImpl(get(), get(APPLICATION_SCOPE)) }
    singleOf(::McpConnectionManager) { bind<McpConnections>() }
    singleOf(::DefaultConnectorDirectorySelection) { bind<ConnectorDirectorySelection>() }
    singleOf(::OAuthClientStoreImpl) { bind<OAuthClientStore>() }

    // Connector-OAuth machinery (portable; the loopback/custom-scheme RedirectReceiverFactory is the only
    // platform-supplied piece — each platform module binds its own). CONNECTOR_HTTP-qualified so the finite
    // discovery/token client is injected, not the streaming transport the providers use.
    single<ClientRegistrar> { ClientRegistrarImpl(get(CONNECTOR_HTTP), get()) }
    single<OAuthDiscovery> { OAuthDiscoveryImpl(get(CONNECTOR_HTTP)) }
    single<OAuthTokenClient> { OAuthTokenClientImpl(get(CONNECTOR_HTTP)) }
    singleOf(::ConnectCoordinatorImpl) { bind<ConnectCoordinator>() }

    // Map-consuming repos — explicit so the qualified multibinding map is injected (erasure collision).
    single<WebSearchResolver> { WebSearchResolverImpl(get(WEB_SEARCH_PROVIDERS), get(), get()) }
    single<ProviderChain> { ProviderChainImpl(get(WEB_SEARCH_PROVIDERS), get()) }
    single<WebFetcher> { ChainedWebFetcher(get(WEB_FETCH_PROVIDERS)) }

    // Qualified-param variants.
    single<McpClient> { McpClientImpl(get(IO)) }
    single<ConnectorCatalog> { AggregateConnectorCatalog(get(), get(), get(APPLICATION_SCOPE)) }

    // Connectors (concrete members of the multibound Set + bootstrap).
    // Contributed, like every other capability: the set below collects whatever was bound, so adding a
    // directory is one binding rather than a binding plus an edit to a hand-written set.
    singleOf(::CuratedConnectorDirectory) { contributes<ConnectorDirectory>() }
    single { OfficialMcpRegistryDirectory(get(), get(), get(IO)) }.contributes(ConnectorDirectory::class)
    single { ConnectorDirectoryBootstrap(get(APPLICATION_SCOPE), get(), get()) }.contributes(DeferredBootstrap::class)

    // Web search / fetch providers (concrete; assembled into maps by commonRegistriesModule).
    single { BraveWebSearchProvider(get(SEARCH_HTTP), get()) }
    single { TavilyWebSearchProvider(get(SEARCH_HTTP), get()) }
    single { OllamaWebSearchProvider(get(SEARCH_HTTP), get()) }
    singleOf(::DuckDuckGoWebSearchProvider)
    single { OllamaWebFetcher(get(SEARCH_HTTP), get()) }
    singleOf(::DuckDuckGoWebFetchProvider)
    single { DuckDuckGoSearchClient(get(SEARCH_HTTP), get(IO)) }

    // Bootstrap (app-init) whose impl is common.
    single { McpReconnectBootstrap(get(), get(), get(APPLICATION_SCOPE)) }.contributes(DeferredBootstrap::class)
    // Last-good remote listings on disk, so a cold start resolves cloud models WITHOUT waiting on the
    // refresh below — the one that used to be the only source of a cloud spec. Under cacheDir: refetchable
    // by definition, and an OS that reclaims it costs one cold start, never correctness.
    single { RemoteCatalogCache(documentStore(RemoteCatalogCache.Document)) }
    // One connector-directory cache for every target: the path is PlatformPaths', so no host needs its own.
    single<ConnectorDirectoryCache> {
        DocumentConnectorDirectoryCache(documentStore(DocumentConnectorDirectoryCache.Document))
    }
    // The first remote-catalog fetch for every configured provider — deferred past the first frame, which
    // is where it used to happen (in each provider's constructor).
    single { CatalogRefreshBootstrap(get(), get(APPLICATION_SCOPE)) }.contributes(DeferredBootstrap::class)
    // The caches a first open would otherwise compute mid-animation — the model picker's two lists, a tap
    // from chat — resolved once after the first frame. The MCP list is warmed by the reconnect bootstrap.
    single {
        CacheWarmup(
            get(APPLICATION_SCOPE),
            mapOf(
                "models" to get<ModelRegistryRepository>().models,
                "speech assets" to get<SpeechAssetRepository>().assets,
            ),
        )
    }.contributes(DeferredBootstrap::class)
    // Reads the native-load journal after the first frame: an entry that survived means the last process
    // died inside JNI, which is the one failure that leaves no exception behind.
    single { NativeCrashReportBootstrap(get()) }.contributes(DeferredBootstrap::class)
}

/**
 * The capability registries, plus the map-shaped multibindings that have not moved to one yet.
 *
 * A provider registry is COLLECTED, never assembled: `getAll<ChatProvider>()` returns whatever the loaded
 * modules bound to that capability, so a module that owns a provider binds it and nothing central changes.
 * That is what removed the two hand-written, hand-synchronised `Map<String, ChatProvider>` blocks (one per
 * platform) whose only failure mode was silent — forget one and the provider shipped on a single platform
 * with no compile error.
 *
 * Each registry is its own named type ([ChatProviderRegistry], …) rather than a generic: generics erase, so
 * `ProviderRegistry<ChatProvider>` and `ProviderRegistry<Manageable>` would be the same container key.
 */
private val commonRegistriesModule = module {
    // Each registry = the capability's contributed providers (the on-device engines) + the matching slot of
    // every connection's runtime. Selected once here, eagerly, so every consumer shares one list.
    single { ChatProviderRegistry(getAll<ChatProvider>(), connected { it.chat }) }
    single { ManageableRegistry(getAll<Manageable>(), connected { it.manageable }) }
    single { SpeechProviderRegistry(getAll<SpeechProvider>(), connected { it.speech }) }
    single { ImageProviderRegistry(getAll<ImageProvider>(), connected { it.image }) }
    // Same contribution shape for the download stack: a new downloadable capability binds an AssetSource
    // and the scheduler, the progress plumbing and the resume logic already cover it.
    single { AssetSourceRegistry(getAll<AssetSource>()) }
    single { ModelAssetSource(get(), get()) }.contributes(AssetSource::class)
    single { SpeechAssetSource(get(), get()) }.contributes(AssetSource::class)

    // Toolsets, same shape again. These four are portable, so they are bound here and every target that
    // installs commonModules has them — which is what let desktop's empty-bundle factory go away.
    single { TimeToolset() }.contributes(Toolset::class)
    single { CalculatorToolset(get()) }.contributes(Toolset::class)
    single {
        val names = get<WebSearchResolver>().activeProviderNameFlow()
            .stateIn(get(APPLICATION_SCOPE), SharingStarted.Eagerly, "")
        WebToolset(get(), get()) { names.value }
    }.contributes(Toolset::class)
    single { FileSystemToolset(get(), get()) }.contributes(Toolset::class)
    // The image models the wizard lists and the tool draws with: every connection's curated rows.
    single<ImageModelCatalog> { ConnectedImageModelCatalog(get(), get(APPLICATION_SCOPE)) }
    single { ImageToolset(get(), get(), get(), get()) }.contributes(Toolset::class)
    single { ToolsetRegistry(getAll<Toolset>()) }
    single<ToolBundleFactory> { ToolBundleFactoryImpl(get(), get()) }

    // The engine routers over those registries. Their one platform-shaped step is injected: EngineLoadPolicy
    // (memory trim + GPU fallback on Android, direct elsewhere) and SpeechResolutionPolicy (the fallback
    // ladder). Neither warrants a per-platform repository class.
    single<LlmEngineRepository> { LlmEngineRepositoryImpl(get(), get(), get(), get(APPLICATION_SCOPE)) }
    // The cloud speech models the wizard lists and the ladder consults (who owns the active pick).
    single<CloudSpeechCatalog> { ConnectedCloudSpeechCatalog(get(), get(APPLICATION_SCOPE)) }
    single<SpeechEngineRepository> { SpeechEngineRepositoryImpl(get(), get(), get(), get(), get(), get()) }

    single<Map<WebSearchProviderId, WebSearchProvider>>(WEB_SEARCH_PROVIDERS) {
        listOf<WebSearchProvider>(get<BraveWebSearchProvider>(), get<TavilyWebSearchProvider>(), get<OllamaWebSearchProvider>(), get<DuckDuckGoWebSearchProvider>())
            .associateBy { it.id }
    }
    single<Map<WebFetchProviderId, WebFetchProvider>>(WEB_FETCH_PROVIDERS) {
        listOf<WebFetchProvider>(get<OllamaWebFetcher>(), get<DuckDuckGoWebFetchProvider>()).associateBy { it.id }
    }
    single<Set<ConnectorDirectory>> { getAll<ConnectorDirectory>().toSet() }
}

/**
 * A [PersistedDocument]'s store on this host: the path from [PlatformPaths] (so no document names a directory),
 * read on the app's I/O scope. Public so an application module can bind a document only it has (the Android
 * app's imported models) through the same one path — bind each document ONCE: DataStore refuses two live
 * stores on one file.
 */
fun <T> Scope.documentStore(document: PersistedDocument<T>): DocumentStore<T> =
    DocumentStore(
        document = document,
        fileSystem = get(),
        path = get<PlatformPaths>().documentPath(document),
        scope = get(APP_SCOPE),
        onProblem = { message, error -> AideLog.w("AideDocs", message, error) },
    )

/**
 * The user's connections and the vendors that serve them.
 *
 * A vendor is code, contributed like any capability (`single { … } bind Vendor::class`) and collected into
 * the [VendorRegistry]; a connection is data — the user's account or endpoint — held by the
 * [ConnectionRepository] and turned into providers by [ConnectionRuntimesImpl]. Nothing here names a
 * connection: each registry above reads the runtimes' slots, so a second OpenRouter account is a second row
 * in a document, not a second binding.
 *
 * Every dependency is common (`:aisdk` is commonMain Ktor), so `:desktopApp` reusing `commonModules` gets
 * every vendor. NOT here: the LOCAL LiteRT provider (Android-only, bound by `:app`).
 */
private val commonConnectionsModule = module {
    // Read on first use: the chat pill paints from the selection card, and a chosen cloud model's gate waits
    // on its own connection's catalog either way.
    single<ConnectionRepository> {
        ConnectionRepositoryImpl(documentStore(ConnectionDocuments.Connections), get(), get(), get(), get())
    }
    single<LabelStore> { DocumentLabelStore(documentStore(LabelDocuments.Labels)) }
    single {
        VendorEnvironment(
            http = get(AISDK_HTTP),
            assets = get(),
            dispatcher = get(),
            readBytes = get(),
            catalogCache = get(),
            selection = get(),
            micActivity = get(),
            speechCatalog = { get() },
        )
    }
    single { OpenAiCompatibleVendor(get()) }.contributes(Vendor::class)
    single { AnthropicVendor(get()) }.contributes(Vendor::class)
    single { GeminiVendor(get()) }.contributes(Vendor::class)
    single { ElevenLabsVendor(get()) }.contributes(Vendor::class)
    single { VendorRegistry(getAll<Vendor>()) }
    single<ConnectionRuntimes> { ConnectionRuntimesImpl(get(), get(), get(APPLICATION_SCOPE)) }
    single<ProviderDirectory> { ProviderDirectoryImpl(get(), get(), get(), get(APPLICATION_SCOPE)) }
}

/** Providers whose impl is common and that are not a connection's. */
private val commonProvidersModule = module {
    // Voice reason seam → chat use case (Surface.VOICE), adapting the event union to neutral ReasonEvent.
    single<VoiceReasoner> {
        val useCase = get<SendChatMessageUseCase>()
        VoiceReasoner { transcript, modelId, userText, holder ->
            useCase.invoke(
                transcript = transcript,
                modelId = modelId,
                userParts = listOf(AidePart.Text(userText)),
                holder = holder,
                enabledGated = emptySet(),
                surface = Surface.VOICE,
            ).mapNotNull { event ->
                when (event) {
                    is SendChatMessageUseCase.Event.Warming -> ReasonEvent.Warming
                    is SendChatMessageUseCase.Event.Streaming -> ReasonEvent.TextDelta(event.delta)
                    is SendChatMessageUseCase.Event.ToolCallStarted -> ReasonEvent.ToolAnnounce(event.name)
                    is SendChatMessageUseCase.Event.Done -> ReasonEvent.Done(event.finalText)
                    is SendChatMessageUseCase.Event.Error -> ReasonEvent.Error(event.message)
                    else -> null
                }
            }
        }
    }
}

/** Domain use-cases + tool dispatch/gates + IO channels whose impl is common. Android toolsets / intent
 *  dispatch / TransformController / AssistantVoiceController stay in the platform module. */
private val commonDomainModule = module {
    // Chat / model use cases.
    singleOf(::ObserveChatsUseCase)
    singleOf(::ObserveChatUseCase)
    singleOf(::ObserveChatMessagesUseCase)
    singleOf(::ObserveModelsUseCase)
    singleOf(::ObserveLastUsedForTierUseCase)
    singleOf(::CreateChatUseCase)
    singleOf(::RenameChatUseCase)
    singleOf(::DeleteChatWithFallbackUseCase)
    singleOf(::DeleteChatsWithFallbackUseCase)
    singleOf(::DeleteMessagesFromUseCase)
    singleOf(::ArchiveChatWithFallbackUseCase)
    singleOf(::SetChatArchivedUseCase)
    singleOf(::SetChatsArchivedUseCase)
    singleOf(::SetChatStarredUseCase)
    singleOf(::SetDefaultModelUseCase)
    singleOf(::ResolveActiveModelUseCase)
    singleOf(::SendChatMessageUseCase)
    singleOf(::StartDictationUseCase)
    singleOf(::AcquireModelUseCase)
    // Surfaces report themselves; the host's HiddenWorkPolicy (bound per app) says what hiding costs.
    single { SurfacePresence(get()) }
    singleOf(::LoadModelUseCase)
    singleOf(::UnloadModelUseCase)
    singleOf(::DownloadModelUseCase)
    singleOf(::PauseDownloadUseCase)
    singleOf(::CancelDownloadUseCase)
    singleOf(::DeleteModelUseCase)
    // Tool dispatch pipeline + gates.
    singleOf(::IdempotencyCache)
    singleOf(::RateLimiter)
    singleOf(::Tracer)
    singleOf(::WriteConfirmGate)
    // Explicit rather than singleOf: the handler dispatcher is a QUALIFIED CoroutineDispatcher, which
    // constructor autowiring cannot express.
    single { ToolDispatcher(get(), get(), get(), get(), get(), get(IO)) }
    singleOf(::ContactPickGate)
    // IO channels.
    singleOf(::VoiceInputChannel)
    // Qualified-param.
    single { FileSystemRoots(get(), get(APPLICATION_SCOPE), get()) }
}

/** ViewModels (all in commonMain UI). Uses the multiplatform `koin-compose-viewmodel` DSL so the shared
 *  composables' `koinViewModel()` resolves them on every platform. */
private val commonViewModelModule = module {
    viewModelOf(::AppViewModel)
    viewModelOf(::ChatViewModel)
    viewModelOf(::ChatsViewModel)
    viewModelOf(::CustomInstructionViewModel)
    viewModelOf(::ConnectionsViewModel)
    viewModelOf(::LabelsViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::McpSettingsViewModel)
    viewModelOf(::ConnectorDirectoriesViewModel)
    viewModelOf(::SpeechSettingsViewModel)
    viewModelOf(::WebSearchSettingsViewModel)
    // Qualified params → explicit.
    viewModel { ConnectorBrowseViewModel(get(), get(), get(), get(), get(CONNECTOR_IMAGE_LOADER)) }
    viewModel { ToolsSettingsViewModel(get(), get(), get(), get(), get()) }
    viewModel {
        ModelsViewModel(
            deviceInfo = get(),
            observeModels = get(),
            downloadModel = get(),
            pauseDownload = get(),
            cancelDownload = get(),
            deleteModel = get(),
            loadModel = get(),
            unloadModel = get(),
            selection = get(),
            samplerOverridesStore = get(),
            speechAssets = get(),
            registry = get(),
            gate = get(),
            importRepo = get(),
            // getOrNull for the IMPORTER: a target that cannot import binds none, so the screen omits the
            // affordance rather than offering one that refuses. The reader above is bound everywhere.
            importer = getOrNull(),
            cloudSpeech = get(),
            imageModels = get(),
            speechProviders = get(),
            directory = get(),
        )
    }
}

/**
 * All platform-agnostic modules. Each application appends its platform module and its feature set — see
 * CLAUDE.md, "The gating rule".
 *
 * Feature DI is NOT included here — `featureModules(...)` installs each feature's own module along with the
 * [com.sabreware.aide.core.designsystem.feature.FeatureRegistry], so a feature a platform does not install contributes no
 * definitions there either.
 */
val commonModules: List<Module> = listOf(
    commonCoreModule,
    commonDataModule,
    commonRegistriesModule,
    commonConnectionsModule,
    commonProvidersModule,
    commonDomainModule,
    commonViewModelModule,
    sharedBootstrapModule,
)

/**
 * One capability slot of every connection's runtime, as the registries consume it: null until the
 * connections are read, then the providers of the connections that fill [slot].
 */
private inline fun <reified P : Any> Scope.connected(crossinline slot: (ConnectionRuntime) -> P?): StateFlow<List<P>?> {
    val runtimes = get<ConnectionRuntimes>().runtimes
    return runtimes
        .map { list -> list?.mapNotNull(slot) }
        .stateIn(get(APPLICATION_SCOPE), SharingStarted.Eagerly, runtimes.value?.mapNotNull(slot))
}
