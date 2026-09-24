package com.sabreware.aide.app.di

import com.sabreware.aide.di.documentStore
import com.sabreware.aide.data.model.DocumentImportedModelsStore
import com.sabreware.aide.core.domain.model.ModelDocuments
import com.sabreware.aide.core.domain.model.ImportedModelsStore
import com.sabreware.aide.core.domain.model.ModelImporter
import com.sabreware.aide.core.common.startup.DeferredBootstrap
import android.content.ComponentCallbacks2
import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.svg.SvgDecoder
import com.sabreware.aide.app.feature.androidFeatures
import com.sabreware.aide.app.platform.AndroidAboutLibrariesJson
import com.sabreware.aide.app.platform.AndroidDeviceInfo
import com.sabreware.aide.app.platform.AndroidGrantedFolderResolver
import com.sabreware.aide.core.common.di.contributes
import com.sabreware.aide.core.common.di.APPLICATION_SCOPE
import com.sabreware.aide.core.common.di.APP_SCOPE
import com.sabreware.aide.core.common.media.AttachmentBytesReader
import com.sabreware.aide.data.media.JvmAttachmentBytesReader
import com.sabreware.aide.core.common.di.CONNECTOR_IMAGE_LOADER
import com.sabreware.aide.core.common.di.DEFAULT
import com.sabreware.aide.core.common.di.IO
import com.sabreware.aide.core.common.di.MAIN
import com.sabreware.aide.core.common.di.MAIN_IMMEDIATE
import com.sabreware.aide.core.common.media.ImageAttachmentStore
import com.sabreware.aide.core.common.speech.DictationController
import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.connector.oauth.RedirectReceiverFactory
import com.sabreware.aide.core.domain.device.DeviceInfo
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.core.domain.llm.ChatProvider
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.secure.SecureStore
import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.SpeechAssetRepository
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechResolutionPolicy
import com.sabreware.aide.app.speech.VoiceTurnLoop
import com.sabreware.aide.core.domain.tools.Toolset
import com.sabreware.aide.core.domain.tools.calc.MathEvaluator
import com.sabreware.aide.core.domain.tools.fs.FileSystemBackend
import com.sabreware.aide.core.designsystem.feature.featureModules
import com.sabreware.aide.app.data.attachments.ImageStore
import com.sabreware.aide.app.data.catalog.AndroidModelCatalog
import com.sabreware.aide.app.data.catalog.RemoteAllowlistBootstrap
import com.sabreware.aide.app.data.connector.icon.ConnectorIconFetcher
import com.sabreware.aide.app.data.connector.icon.ConnectorIconKeyer
import com.sabreware.aide.app.data.connector.oauth.RedirectReceiverFactoryImpl
import com.sabreware.aide.app.data.download.AndroidDownloadScheduler
import com.sabreware.aide.app.data.download.DownloadController
import com.sabreware.aide.app.data.model.AndroidEngineLoadPolicy
import com.sabreware.aide.app.data.model.ModelImportRepositoryImpl
import com.sabreware.aide.app.data.model.ResidencyManagerImpl
import com.sabreware.aide.app.data.notification.NotificationScheduler
import com.sabreware.aide.app.data.secure.EncryptedPreferences
import com.sabreware.aide.app.data.secure.TinkAead
import com.sabreware.aide.app.data.storage.AndroidPlatformPaths
import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.data.chat.androidDatabaseBuilder
import com.sabreware.aide.data.chat.buildDatabase
import com.sabreware.aide.data.download.DownloadEngine
import com.sabreware.aide.data.llm.EngineLoadPolicy
import com.sabreware.aide.app.llm.LiteRtLmEngine
import com.sabreware.aide.app.llm.LocalProvider
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.data.speech.SpeechAssetRepositoryImpl
import com.sabreware.aide.data.speech.SpeechAssetStorage
import com.sabreware.aide.data.speech.SpeechAssetStorageImpl
import com.sabreware.aide.data.speech.SpeechBundleExtractor
import com.sabreware.aide.data.speech.ExtractingBundleInstaller
import com.sabreware.aide.data.speech.SpeechBundleInstaller
import com.sabreware.aide.app.speech.ActiveSpeechBootstrap
import com.sabreware.aide.app.speech.audio.AudioFocusGate
import com.sabreware.aide.app.speech.audio.AudioRouteMonitor
import com.sabreware.aide.app.speech.audio.AudioPlayer
import com.sabreware.aide.app.speech.audio.AudioPlayerImpl
import com.sabreware.aide.app.speech.audio.AudioRecordCapturer
import com.sabreware.aide.app.speech.audio.SensitiveAudioPolicy
import com.sabreware.aide.app.speech.dictation.DictationControllerImpl
import com.sabreware.aide.app.speech.io.VoiceOutputChannel
import com.sabreware.aide.app.speech.io.VoiceTurnLoopImpl
import com.sabreware.aide.app.speech.system.SystemSpeechProvider
import com.sabreware.aide.app.speech.system.SystemSttEngine
import com.sabreware.aide.app.speech.system.SystemTtsEngine
import com.sabreware.aide.data.speech.sherpa.SherpaSpeechProvider
import com.sabreware.aide.data.speech.sherpa.SherpaSttEngine
import com.sabreware.aide.data.speech.sherpa.SherpaTtsEngine
import com.sabreware.aide.data.speech.sherpa.SherpaVadEngine
import com.sabreware.aide.data.model.storage.ModelStorageImpl
import com.sabreware.aide.data.tools.calc.SafeMathEvaluator
import com.sabreware.aide.data.tools.fs.JvmFileSystemBackend
import com.sabreware.aide.di.commonModules
import com.sabreware.aide.feature.tasks.data.buildTaskDatabase
import com.sabreware.aide.platform.android.intent.BrokeredIntentDispatcher
import com.sabreware.aide.platform.android.intent.DirectIntentDispatcher
import com.sabreware.aide.platform.android.intent.IntentDispatchers
import com.sabreware.aide.platform.android.permission.AndroidRuntimePermissionGate
import com.sabreware.aide.platform.android.permission.PermissionTrampoline
import com.sabreware.aide.app.permission.AndroidPermissionTrampoline
import com.sabreware.aide.app.assistant.AssistantVoiceController
import com.sabreware.aide.app.assistant.data.AndroidAideAssistantManager
import com.sabreware.aide.app.assistant.domain.AideAssistantManager
import com.sabreware.aide.platform.android.surface.ime.data.AndroidAideKeyboardManager
import com.sabreware.aide.platform.android.surface.ime.domain.AideKeyboardManager
import com.sabreware.aide.platform.android.surface.ime.text.TextContextRepository
import com.sabreware.aide.platform.android.surface.ime.transform.TransformController
import com.sabreware.aide.app.tools.ClockToolset
import com.sabreware.aide.app.tools.PhoneToolset
import com.sabreware.aide.app.tools.calendar.CalendarToolset
import com.sabreware.aide.app.tools.clipboard.ClipboardToolset
import com.sabreware.aide.app.tools.contacts.ContactsToolset
import com.sabreware.aide.app.tools.device.DeviceToolset
import com.sabreware.aide.app.tools.fs.AndroidMimeTypeResolver
import com.sabreware.aide.app.tools.notification.ReminderToolset
import com.sabreware.aide.core.domain.licenses.AboutLibrariesJson
import com.sabreware.aide.ui.settings.registry.commonFeatures
import com.sabreware.aide.core.domain.tools.fs.GrantedFolderResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Android platform Koin wiring (Phase 6a KMP split). Holds every definition whose impl is `:app`-only or that
 * calls a platform API (`androidContext()`), plus the dispatcher/scope providers (Dispatchers.IO/Main are
 * JVM/Android-only; a desktop module supplies its own). The platform-agnostic definitions live in
 * `commonModules` (`:di`, package `com.sabreware.aide.di`), and `appModules` appends this module onto them.
 *
 * Providers are CONTRIBUTED, not listed: a `bind<ChatProvider>()` / `bind<SpeechProvider>()` here is the
 * whole registration, and `:di` collects them with `getAll`. There is no per-platform provider map to keep
 * in step with desktop's copy.
 *
 * Qualifier names (`IO`, `APPLICATION_SCOPE`, …) are declared once in
 * `:core:common` `di/Names.kt` so both modules resolve identical instances. A multibinding `Map<K,V>` MUST
 * keep its `named()` qualifier (all maps erase to the same runtime type).
 */

private val androidPlatformModule = module {
    // --- Dispatchers / scopes (platform-specific: Dispatchers.IO/Main are JVM/Android-only) ---
    single<CoroutineDispatcher>(IO) { Dispatchers.IO }
    single<CoroutineDispatcher>(DEFAULT) { Dispatchers.Default }
    single<CoroutineDispatcher>(MAIN) { Dispatchers.Main }
    single<CoroutineDispatcher>(MAIN_IMMEDIATE) { Dispatchers.Main.immediate }
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(IO)) }
    single(APPLICATION_SCOPE) { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(DEFAULT)) }

    // --- Storage / download / database (hold the Context) ---
    // The USER_PREFS DataStore is built in commonMain over PlatformPaths (same filesDir/datastore/… path
    // preferencesDataStoreFile() used, so the existing file is picked up) — see data/prefs.
    single<ModelStorage> { ModelStorageImpl(get(), get(IO)) }
    single<PlatformPaths> { AndroidPlatformPaths(androidContext()) }
    // okio.FileSystem.SYSTEM is the same OS filesystem on Android + JVM — the shared DownloadEngine writes
    // identically on both.
    single<FileSystem> { FileSystem.SYSTEM }
    single { DownloadEngine(get(), get(IO)) }
    single { DownloadController(androidContext(), get(), get(APPLICATION_SCOPE), get(IO)) }
    single<DownloadScheduler> { AndroidDownloadScheduler(get()) }
    single { buildDatabase(androidDatabaseBuilder(androidContext())) }
    // The Tasks feature owns its own Android-only Room DB (its DAOs/repo/VMs are bound by TasksFeature.koinModule,
    // which resolves this cross-module). Lives here because the builder needs androidContext (koin-android).
    single { buildTaskDatabase(androidContext()) }

    single<ResidencyManager> {
        @Suppress("DEPRECATION")
        ResidencyManagerImpl(
            scope = get(APP_SCOPE),
            trimThresholdLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            deviceInfo = get(),
            journal = get(),
        )
    }

    single(CONNECTOR_IMAGE_LOADER) { connectorImageLoader(androidContext()) }

    // Platform capabilities for the shared UI (commonMain interfaces, Android impls hold the Context).
    single<DeviceInfo> { AndroidDeviceInfo(androidContext()) }
    single<AboutLibrariesJson> { AndroidAboutLibrariesJson(androidContext()) }
    single<GrantedFolderResolver> { AndroidGrantedFolderResolver(androidContext()) }

    // --- Repositories / managers whose impl is :app-only (bound to their domain interface) ---
    singleOf(::AndroidAideKeyboardManager) { bind<AideKeyboardManager>() }
    singleOf(::AndroidAideAssistantManager) { bind<AideAssistantManager>() }
    factoryOf(::VoiceTurnLoopImpl) { bind<VoiceTurnLoop>() }

    // The two policies the shared engine routers take: what has to happen around a model load, and which
    // speech engine wins. The routers themselves are common (:di) — only these differ per platform.
    single<EngineLoadPolicy> { AndroidEngineLoadPolicy(androidContext()) }
    // Sherpa if its weights are downloaded and capable, else the system engines, which are always present
    // and therefore terminate the ladder.
    single { SpeechResolutionPolicy(listOf(ProviderId.SHERPA, ProviderId.ANDROID_SYSTEM)) }

    // Qualified-param repos.
    // Shared with :desktopApp; Android's MimeTypeMap is the one thing that differs.
    single<FileSystemBackend> { JvmFileSystemBackend(AndroidMimeTypeResolver) }
    single<ImageAttachmentStore> { ImageStore(androidContext(), get(IO)) }
    single<AudioPlayer> { AudioPlayerImpl(get(), get(), get(IO)) }
    single<AudioCapturer> { AudioRecordCapturer(get(), get(), get(), get(), get(IO)) }
    single<DictationController> { DictationControllerImpl(get(), get(), get(MAIN_IMMEDIATE)) }
    // One implementation, two ports: every target must be able to LIST imported models (the registry
    // merges them into its catalog); IMPORTING is a capability only this one has, so only this one binds
    // ModelImporter. Desktop binds the reader and nothing else.
    // Importing a model file is an Android-app capability, so its document is bound here and nowhere else.
    single<ImportedModelsStore> { DocumentImportedModelsStore(documentStore(ModelDocuments.ImportedModels)) }
    single { ModelImportRepositoryImpl(androidContext(), get(), get(), get(IO)) } binds
        arrayOf(ModelImportRepository::class, ModelImporter::class)
    // OAuth ports (discovery/registrar/token/coordinator) now live in commonModules; :app only supplies the
    // Android RedirectReceiverFactory (loopback + custom-scheme, user-selectable).
    single<RedirectReceiverFactory> { RedirectReceiverFactoryImpl(get(), get(APPLICATION_SCOPE), get(IO)) }
    // ModelRegistryRepository now lives in commonModules (commonDataModule) — its impl moved to
    // :shared/commonMain and resolves the Android leaf ports (ModelStorage/ModelCatalog/download/import) from here.

    // --- Concrete-only leaf classes (secure store, catalog/storage, notifications) ---
    singleOf(::TinkAead)
    singleOf(::EncryptedPreferences) { bind<com.sabreware.aide.core.domain.secure.SecureStore>() }
    single<ModelCatalog> { AndroidModelCatalog(androidContext()) }
    single<SpeechAssetStorage> { SpeechAssetStorageImpl(get()) }
    single { SpeechBundleExtractor(get(IO)) }
    single<SpeechBundleInstaller> { ExtractingBundleInstaller(get(), get()) }
    single<SpeechAssetRepository> { SpeechAssetRepositoryImpl(get(), get(), get(), get(APPLICATION_SCOPE)) }
    singleOf(::NotificationScheduler)

    // --- Speech engines / providers / audio (Sherpa + Android system) ---
    // The two `bind<SpeechProvider>()` calls ARE the registration: :di's SpeechProviderRegistry collects
    // whatever the loaded modules contributed.
    single { SherpaSttEngine(get(), get(), get(), get(), get(IO)) }
    single { SherpaTtsEngine(get(), get(), get(IO)) }
    single { SherpaVadEngine(get(), get(IO)) }
    single { SystemSttEngine(androidContext(), get(), get(MAIN_IMMEDIATE)) }
    single { SystemTtsEngine(androidContext(), get(MAIN_IMMEDIATE)) }
    singleOf(::SherpaSpeechProvider) { contributes<SpeechProvider>() }
    singleOf(::SystemSpeechProvider) { contributes<SpeechProvider>() }
    singleOf(::AudioFocusGate)
    // Becoming-noisy + input-device changes, shared by the capturer and the player.
    singleOf(::AudioRouteMonitor)
    singleOf(::SensitiveAudioPolicy)
    singleOf(::VoiceOutputChannel)

    // --- Bootstraps (app-init) that hold the Context ---
    // Contributed, not called from Application.onCreate: platform cold-start is deferred past the first
    // frame like everything else, and `getAll<DeferredBootstrap>()` in :di collects it.
    single { ActiveSpeechBootstrap(get(), get(), get(), get(), get(APPLICATION_SCOPE)) }.contributes(DeferredBootstrap::class)
    single { RemoteAllowlistBootstrap(androidContext(), get(APPLICATION_SCOPE)) }.contributes(DeferredBootstrap::class)

    // --- LLM providers ---
    // On-device LiteRT-LM (LiteRtLmEngine constructed here — the local provider owns it). Android-only, so
    // the LOCAL provider is bound here; the remote (OpenAI/Gemini/Anthropic) builders live in
    // commonProvidersModule. Binding ChatProvider is the whole registration — desktop simply never runs this
    // module, so its chat registry has three members instead of four with nothing to keep in sync.
    single { LocalProvider(chat = LiteRtLmEngine(androidContext(), get())) }.contributes(ChatProvider::class)

    // The attachment-bytes reader. A named port bound by type — it used to be a raw `(String) -> ByteArray?`
    // told apart from any other function of that shape by a DI qualifier string. The implementation is
    // JVM-shared, so this and :desktopApp bind the SAME class rather than each keeping a copy of the two
    // lines that read a file.
    single<AttachmentBytesReader> { JvmAttachmentBytesReader() }

    // --- Android toolsets + intent dispatch + IME/permission + platform controllers ---
    single<MathEvaluator> { SafeMathEvaluator() }
    // Each `bind<Toolset>()` IS the registration: :di's ToolsetRegistry collects whatever the running
    // application contributed, and the bundle factory names none of them.
    singleOf(::ReminderToolset)
    singleOf(::ClockToolset) { contributes<Toolset>() }
    singleOf(::PhoneToolset) { contributes<Toolset>() }
    singleOf(::ContactsToolset) { contributes<Toolset>() }
    singleOf(::CalendarToolset) { contributes<Toolset>() }
    singleOf(::ClipboardToolset) { contributes<Toolset>() }
    singleOf(::DeviceToolset) { contributes<Toolset>() }
    singleOf(::DirectIntentDispatcher)
    singleOf(::BrokeredIntentDispatcher)
    singleOf(::IntentDispatchers)
    singleOf(::TextContextRepository)
    single<PermissionTrampoline> { AndroidPermissionTrampoline(androidContext()) }
    single { AndroidRuntimePermissionGate(androidContext(), get()) } bind RuntimePermissionGate::class
    single { TransformController(get(), get(), get(), get(), get(), get(), get(APPLICATION_SCOPE)) }
    single { AssistantVoiceController(get(), get(), get(), get(), get(), get(), get(MAIN_IMMEDIATE), get()) }
}

private fun connectorImageLoader(context: Context): ImageLoader {
    val httpClient = KtorClientFactory.finite(connectMs = 5_000, readMs = 8_000)
    return ImageLoader.Builder(context)
        .components {
            add(SvgDecoder.Factory())
            add(ConnectorIconFetcher.Factory(httpClient))
            add(ConnectorIconKeyer())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.10).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("connector_icons").absolutePath.toPath())
                .maxSizeBytes(16L * 1024 * 1024)
                .build()
        }
        .build()
}

/**
 * All app modules: the platform-agnostic `commonModules` (`:shared`), the Android platform module, and the
 * feature set THIS application ships — the shared features plus Android's own (keyboard, digital assistant,
 * tasks). `featureModules` installs each feature's DI and the `FeatureRegistry` the shell iterates, so a
 * platform that never lists a feature also never gets its definitions.
 */
val appModules = commonModules + androidPlatformModule + featureModules(commonFeatures + androidFeatures)
