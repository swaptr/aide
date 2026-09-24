package com.sabreware.aide.desktop.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
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
import com.sabreware.aide.core.domain.model.ModelImportRepository
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.secure.SecureStore
import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.SpeechAssetRepository
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechResolutionPolicy
import com.sabreware.aide.core.domain.tools.calc.MathEvaluator
import com.sabreware.aide.core.domain.tools.fs.FileSystemBackend
import com.sabreware.aide.core.designsystem.feature.featureModules
import com.sabreware.aide.core.domain.catalog.ModelCatalog
import com.sabreware.aide.data.chat.buildDatabase
import com.sabreware.aide.data.chat.desktopDatabaseBuilder
import com.sabreware.aide.core.domain.download.DownloadScheduler
import com.sabreware.aide.data.download.CoroutineDownloadScheduler
import com.sabreware.aide.data.download.DownloadEngine
import com.sabreware.aide.data.llm.EngineLoadPolicy
import com.sabreware.aide.core.domain.presence.HiddenWorkPolicy
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.data.speech.ExtractingBundleInstaller
import com.sabreware.aide.data.speech.SpeechAssetRepositoryImpl
import com.sabreware.aide.data.speech.SpeechAssetStorage
import com.sabreware.aide.data.speech.SpeechAssetStorageImpl
import com.sabreware.aide.data.speech.SpeechBundleExtractor
import com.sabreware.aide.data.speech.SpeechBundleInstaller
import com.sabreware.aide.data.speech.sherpa.SherpaSpeechProvider
import com.sabreware.aide.data.speech.sherpa.SherpaSttEngine
import com.sabreware.aide.data.speech.sherpa.SherpaTtsEngine
import com.sabreware.aide.data.speech.sherpa.SherpaVadEngine
import com.sabreware.aide.core.domain.model.ModelStorage
import com.sabreware.aide.data.model.storage.ModelStorageImpl
import com.sabreware.aide.data.tools.calc.SafeMathEvaluator
import com.sabreware.aide.data.tools.fs.JvmFileSystemBackend
import com.sabreware.aide.desktop.connector.DesktopRedirectReceiverFactory
import com.sabreware.aide.desktop.secure.DesktopSecureStore
import com.sabreware.aide.desktop.speech.DesktopDictationController
import com.sabreware.aide.desktop.speech.audio.DesktopAudioCapturer
import com.sabreware.aide.desktop.speech.audio.DesktopAudioPlayer
import com.sabreware.aide.desktop.storage.DesktopAppDirs
import com.sabreware.aide.desktop.storage.DesktopPlatformPaths
import com.sabreware.aide.di.commonModules
import com.sabreware.aide.core.domain.licenses.AboutLibrariesJson
import com.sabreware.aide.ui.settings.registry.commonFeatures
import com.sabreware.aide.core.domain.tools.fs.GrantedFolderResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Desktop platform Koin wiring (Phase 6b-2) — the JVM peer of `:app`'s `androidPlatformModule`. Supplies
 * every definition `commonModules` needs that is desktop-specific or platform-coupled: the dispatcher/scope
 * providers, the DataStore/Room/attachment-bytes primitives, the REAL desktop impls
 * (SecureStore/FileSystemBackend), the two engine policies, and the desktop-v1
 * stubs (DesktopStubs.kt).
 *
 * It lists no providers. Sherpa binds `SpeechProvider` here because it is wired here; the cloud chat
 * providers bind themselves in `:di` and the registries collect them, so desktop's provider set is a
 * consequence of its classpath, not a second copy of a map.
 *
 * Qualifier names (`IO`, `APPLICATION_SCOPE`, …) are the shared instances from `:core:common` `di/Names.kt`
 * so both modules resolve identically.
 */

private fun desktopPlatformModule(dirs: DesktopAppDirs) = module {
    // --- Dispatchers / scopes (Dispatchers.Main = the Swing EDT via kotlinx-coroutines-swing) ---
    single<CoroutineDispatcher>(IO) { Dispatchers.IO }
    single<CoroutineDispatcher>(DEFAULT) { Dispatchers.Default }
    single<CoroutineDispatcher>(MAIN) { Dispatchers.Main }
    single<CoroutineDispatcher>(MAIN_IMMEDIATE) { Dispatchers.Main.immediate }
    single(APP_SCOPE) { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(IO)) }
    single(APPLICATION_SCOPE) { CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(DEFAULT)) }

    // --- Database / transports ---
    // The USER_PREFS DataStore is built in commonMain over PlatformPaths — see data/prefs.
    single { buildDatabase(desktopDatabaseBuilder(get<DesktopAppDirs>().data)) }
    single(CONNECTOR_IMAGE_LOADER) { connectorImageLoader() }

    // --- On-device asset download + storage (the desktop peer of :app's WorkManager stack) ---
    // Where this host keeps app state. Everything on-device roots here, and it is the ONE place the
    // Linux/macOS/Windows difference is decided — see DesktopAppDirs.
    single { dirs }
    single<PlatformPaths> { DesktopPlatformPaths(get()) }
    // okio.FileSystem.SYSTEM is the same OS filesystem on Android + JVM — the shared DownloadEngine writes
    // identically on both.
    single<FileSystem> { FileSystem.SYSTEM }
    single { DownloadEngine(get(), get(IO)) }
    single<SpeechAssetStorage> { SpeechAssetStorageImpl(get(), get()) }
    // No WorkManager here, so the transfer is a coroutine on the app scope — the same shared class any
    // non-Android target would use, resolving paths through the same AssetSourceRegistry.
    single<DownloadScheduler> { CoroutineDownloadScheduler(get(), get(), get(APP_SCOPE), get(IO)) }

    // The same JVM-shared reader :app binds — see there for why it is a port rather than a lambda.
    single<AttachmentBytesReader> { JvmAttachmentBytesReader() }

    // --- REAL desktop impls (android impls are hard-coupled to a Context) ---
    single<SecureStore> { DesktopSecureStore(get()) }
    // exp4j is JVM-only, so the shared evaluator is jvmShared and each application binds it (:core:domain
    // holds only the MathEvaluator port).
    single<MathEvaluator> { SafeMathEvaluator() }
    // Shared with :app; only the MIME lookup differs, and NIO's probe is the right one here.
    single<FileSystemBackend> { JvmFileSystemBackend() }
    single<ImageAttachmentStore> { DesktopImageAttachmentStore(get()) }
    single<DeviceInfo> { DesktopDeviceInfo() }
    single<AboutLibrariesJson> { DesktopAboutLibrariesJson() }
    single<GrantedFolderResolver> { DesktopGrantedFolderResolver() }

    // ModelRegistryRepository is WRITE-ONCE in commonModules — desktop supplies its leaf ports. Storage is
    // the shared okio implementation now, so on-device models here need a catalog, not a port.
    single<ModelStorage> { ModelStorageImpl(get(), get(IO), get()) }
    single<ModelCatalog> { DesktopModelCatalog() }

    // --- On-device speech (Sherpa-ONNX on the JVM + Java Sound audio) ---
    // Engines: the shared :data:speech:sherpa module — the same classes :app runs. The native libs
    // are loaded via `-Djava.library.path` (set in build.gradle.kts). VAD → STT (offline STT feeds VAD) →
    // TTS → provider → repository, mirroring the Android graph.
    single { SherpaVadEngine(get(), get(IO)) }
    single { SherpaSttEngine(get(), get(), get(), get(), get(IO)) }
    single { SherpaTtsEngine(get(), get(), get(IO)) }
    // `bind<SpeechProvider>()` IS the registration — :di's SpeechProviderRegistry collects it.
    single { SherpaSpeechProvider(get(), get(), get(), get(), get()) }.contributes(SpeechProvider::class)
    // Sherpa is the only speech engine here, so it both leads and terminates the ladder. A cloud TTS later
    // extends this list; it does not add a repository.
    single { SpeechResolutionPolicy(listOf(ProviderId.SHERPA)) }
    single<AudioCapturer> { DesktopAudioCapturer(get(), get(IO)) }
    single { DesktopAudioPlayer(get(IO)) } // streaming-PCM output peer of the on-device TTS engine
    single<DictationController> { DesktopDictationController(get(), get(MAIN_IMMEDIATE)) }
    single { SpeechBundleExtractor(get(IO)) }
    single<SpeechBundleInstaller> { ExtractingBundleInstaller(get(), get()) }
    single<SpeechAssetRepository> { SpeechAssetRepositoryImpl(get(), get(), get(), get(APPLICATION_SCOPE)) }

    // --- Desktop-v1 stubs (no on-device model / permission / connector-oauth). The IME + digital-assistant
    // surfaces are NOT stubbed — they are Android-only SettingsFeatures, absent from desktop's compilation. ---
    // Every remote chat engine is a network call, so nothing resides weights in RAM and the load needs no
    // pre-flight trim or GPU fallback.
    single<EngineLoadPolicy> { EngineLoadPolicy.Direct }
    // A minimised window is still a running desktop app: a reply keeps streaming.
    single { HiddenWorkPolicy.KeepRunning }
    // The reader, answering "nothing imported". NO ModelImporter: importing a local model file is an
    // Android capability, and desktop used to bind one whose `import` returned
    // Result.failure(UnsupportedOperationException) — absence dressed as an implementation. Binding nothing
    // is how the ratchet spells absence, and ModelsViewModel takes the importer as nullable so the
    // affordance is simply not drawn.
    single<ModelImportRepository> { DesktopImportedModels() }
    single<ResidencyManager> { DesktopResidencyManager() }
    single<RuntimePermissionGate> { DesktopRuntimePermissionGate() }
    // Connector-OAuth: the ConnectCoordinator + discovery/registrar/token clients are common (commonModules).
    // Desktop supplies only the redirect receiver — loopback-only (no custom-scheme Activity peer).
    single<RedirectReceiverFactory> { DesktopRedirectReceiverFactory(get(APPLICATION_SCOPE), get(IO)) }

    // No chat-provider list here: the OpenAI/Gemini/Anthropic providers bind themselves in :di and the
    // registry collects them. Desktop's set is remote-only because `:data:llm:litert` is simply not on this
    // module's classpath — omission, not a shorter copy of a map.
}

// Connector-icon Coil loader (mirror of :app's, minus the app-only custom fetcher/keyer). SVG decoder for the
// Simple Icons brand marks + the Ktor network fetcher reusing our injected engine.
private fun connectorImageLoader(): ImageLoader {
    val httpClient = KtorClientFactory.finite(connectMs = 5_000, readMs = 8_000)
    return ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(SvgDecoder.Factory())
            add(KtorNetworkFetcherFactory(httpClient))
        }
        .build()
}

/**
 * All desktop app modules: the platform-agnostic `commonModules` (`:di`) + the desktop platform module.
 *
 * The desktop feature set is exactly the shared one: the keyboard / digital-assistant / tasks surfaces are
 * Android-only, and desktop says so by not listing them — there is no empty `actual` to write and none of
 * that code is on this classpath.
 *
 * [dirs] is a parameter because WHERE this process keeps its state is decided at startup, not compiled in:
 * one `jvm("desktop")` binary serves Linux, macOS and Windows. It also means a test can point the whole
 * graph at a scratch directory without depending on `user.home`, which `XDG_DATA_HOME` would override.
 */
fun desktopModules(dirs: DesktopAppDirs = DesktopAppDirs()): List<Module> =
    commonModules + desktopPlatformModule(dirs) + featureModules(commonFeatures)
