package com.sabreware.aide.app

import android.app.Application
import android.util.Log
import com.sabreware.aide.app.di.appModules
import com.sabreware.aide.core.common.storage.BundledAssetReader
import com.sabreware.aide.core.domain.model.ResidencyManager
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.catalog.RemoteModelMetadata
import com.sabreware.aide.data.net.KtorClientFactory
import com.sabreware.aide.feature.tasks.domain.TaskRepository
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class AideApp : Application() {

    // Cold-start bootstraps + residency, resolved lazily from Koin (accessed after startKoin in onCreate).
    private val taskRepository: TaskRepository by inject()
    private val residencyManager: ResidencyManager by inject()
    private val bundledAssets: BundledAssetReader by inject()

    // App-lifetime scope for the two cold-start init launches below (fire-and-forget).
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Route domain-layer logging to android.util.Log. Set first so early logs are captured.
        AideLog.backend = AideLog.Backend { priority, tag, message, throwable ->
            when (priority) {
                AideLog.ERROR -> Log.e(tag, message, throwable)
                AideLog.WARN -> Log.w(tag, message, throwable)
                AideLog.DEBUG -> Log.d(tag, message, throwable)
                else -> Log.i(tag, message, throwable)
            }
        }
        // Inject the platform HTTP transport into the shared networking stack BEFORE any client is built
        // (Koin singles + bootstraps below all go through KtorClientFactory). OkHttp = the JVM/Android engine;
        // its connection-level knobs live here, everything else is common. iOS would set Darwin here.
        KtorClientFactory.engineProvider = { options ->
            OkHttp.create {
                config {
                    retryOnConnectionFailure(options.retryOnConnectionFailure)
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
        }
        // Start the Koin graph before any injected member is touched. CoreModule carries the @ComponentScan
        // that collects every @Single/@Factory/@KoinViewModel class; the other modules add provider wiring.
        startKoin {
            // A duplicate definition is a bug, not a policy. Koin allows overriding by default and both
            // entry points log at ERROR, so a second definition of the same key silently replaced the
            // first — and neither graph test could see it, because both resolve BY TYPE and get the
            // winner. Refusing the override turns that into a startup failure with the key in the message.
            allowOverride(false)
            // ERROR only: INFO logs every definition resolution, and the chat screen resolves a large
            // graph during first composition — the log spam is measurable at cold start.
            androidLogger(Level.ERROR)
            androidContext(this@AideApp)
            modules(appModules)
        }
        // models.dev capability overlay for remote specs — parsed off the main thread (~2 MB bundled asset);
        // lookups before it lands fall back to conservative defaults, so this never blocks startup.
        appScope.launch { RemoteModelMetadata.ensureLoaded(bundledAssets) }
        appScope.launch { taskRepository.seedBuiltInsIfMissing() }
        // NO bootstraps here. Every one of them — MCP reconnect, connector-directory refresh, speech
        // residency reconciliation, the model allowlist refresh — is a `DeferredBootstrap`, and AppShell
        // starts them all after the first frame. The two Android ones used to run right here, ahead of the
        // first frame and with no idempotency guard, which is exactly what the contract exists to prevent.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        residencyManager.onTrimMemory(level)
    }
}
