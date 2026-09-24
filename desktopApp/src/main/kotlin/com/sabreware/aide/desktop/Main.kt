package com.sabreware.aide.desktop

import okhttp3.ConnectionPool
import com.sabreware.aide.core.domain.model.ModelSelectionStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.peek
import com.sabreware.aide.core.designsystem.LocalAppBanner
import com.sabreware.aide.core.designsystem.LocalModalPolicy
import com.sabreware.aide.core.designsystem.ModalPolicy
import com.sabreware.aide.core.designsystem.rememberAppBannerState
import com.sabreware.aide.data.catalog.RemoteModelMetadata
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.desktop.di.desktopModules
import com.sabreware.aide.desktop.platform.desktopAffordances
import com.sabreware.aide.desktop.storage.DesktopAppDirs
import com.sabreware.aide.desktop.storage.SingleInstanceLock
import com.sabreware.aide.ui.app.AppAppearance
import com.sabreware.aide.ui.app.AppShell
import com.sabreware.aide.ui.platform.LocalPlatformAffordances
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinContext
import org.koin.compose.koinInject
import org.koin.core.context.startKoin
import javax.swing.JOptionPane
import kotlin.system.exitProcess

// App-lifetime scope for the cold-start metadata preload (fire-and-forget), mirroring AideApp on Android.
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// Strongly reachable for the life of the process: an unreachable channel can be cleaned up by the GC, which
// closes the file descriptor and silently drops the lock.
private var instanceLock: SingleInstanceLock? = null

@OptIn(FlowPreview::class)
fun main() {
    // Inject the platform HTTP transport into the shared networking stack BEFORE any client is built
    // (Koin singles + the metadata preload go through KtorClientFactory). OkHttp = the JVM engine.
    // One connection pool for the process (OkHttp's guidance); each client keeps its own engine and dispatcher.
    val sharedPool = ConnectionPool()
    KtorClientFactory.engineProvider = { options ->
        OkHttp.create {
            config {
                connectionPool(sharedPool)
                retryOnConnectionFailure(options.retryOnConnectionFailure)
                followRedirects(true)
                followSslRedirects(true)
            }
        }
    }

    // One instance per data directory, decided before anything opens a store under it — DataStore only
    // guards a single writer within one process, so a second instance would race the first on prefs and
    // persisted documents. Resolved once and handed to the graph, so the lock and the stores agree on where.
    val dirs = DesktopAppDirs()
    instanceLock = SingleInstanceLock.acquire(dirs.dataPath()) ?: run {
        JOptionPane.showMessageDialog(null, "AIDE is already running.", "AIDE", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }

    // Start the Koin graph before any injected member is touched: commonModules (domain + VMs + registries +
    // remote LLM providers) + the desktop platform module.
    // allowOverride(false): a duplicate definition is a bug, and Koin's default of letting the second one
    // win made it invisible — the graph test resolves by type and gets whichever landed last.
    val koin = startKoin {
        allowOverride(false)
        modules(desktopModules(dirs))
    }.koin

    // NOTE: the shared bootstraps (MCP reconnect, connector-directory refresh) are started by AppShell
    // after the first frame — not here — so their network/DataStore work never races window startup.

    // models.dev capability overlay for remote specs — parsed off the main thread (~2 MB bundled resource);
    // lookups before it lands fall back to conservative defaults, so this never blocks window start.
    appScope.launch { RemoteModelMetadata.ensureLoaded(koin.get()) }

    // Read before the window exists — blocking is fine (nothing is drawn yet) and avoids opening at the
    // default size and then jumping.
    val store = koin.get<PreferenceStore>()
    // The prefs snapshot (window bounds, theme, chat font) and the user's model choices — both small local
    // files whose reads started inside startKoin — so the first frame opens at the saved size, in the user's
    // theme, on the chosen model's name (or a settled "No model") rather than a shimmer.
    runBlocking {
        store.awaitLoaded()
        koin.get<ModelSelectionStore>().current()
    }
    val saved = WindowBounds.decode(store.peek(WindowKeys.Bounds))

    application {
        // Defaults are wide enough for the Expanded layout (pinned sidebar + a roomy content pane) instead of
        // Compose Desktop's cramped 800x600. Still freely resizable — the UI is responsive, so narrowing the
        // window falls back to Medium and then the Compact (drawer + bottom-sheet) layout.
        val windowState = rememberWindowState(
            size = DpSize(saved.width.dp, saved.height.dp),
            position = if (saved.x == UNPLACED) WindowPosition.PlatformDefault
            else WindowPosition(saved.x.dp, saved.y.dp),
        )
        // Debounced: DataStore rewrites the whole file per commit and a resize drag emits every frame.
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size to windowState.position }
                .debounce(500)
                .collect { (size, position) ->
                    store.set(
                        WindowKeys.Bounds,
                        WindowBounds(
                            width = size.width.value,
                            height = size.height.value,
                            x = if (position.isSpecified) position.x.value else UNPLACED,
                            y = if (position.isSpecified) position.y.value else UNPLACED,
                        ).encode(),
                    )
                }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Aide",
            state = windowState,
        ) {
            // Bind the global Koin into the composition so koinInject / koinViewModel resolve.
            KoinContext {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    AppAppearance(koinInject<PreferenceStore>()) {
        // The banner controller — provided once here, mirroring MainActivity.
        val appBanner = rememberAppBannerState()
        CompositionLocalProvider(
            LocalAppBanner provides appBanner,
            // What this host lets the shared UI reach outside the app. Desktop provides five of the seven;
            // the camera and contact pickers are absent, and the UI omits their affordances rather than
            // drawing ones that do nothing.
            LocalPlatformAffordances provides desktopAffordances,
            // A pointer host: modals are centered dialogs at every window size (a drag-handle sheet is a
            // touch affordance). Android leaves the default, ModalPolicy.Adaptive.
            LocalModalPolicy provides ModalPolicy.Dialog,
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppShell()
            }
        }
    }
}
