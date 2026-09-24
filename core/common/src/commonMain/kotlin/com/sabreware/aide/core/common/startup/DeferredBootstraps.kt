package com.sabreware.aide.core.common.startup

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A one-shot startup task that must run AFTER the first frame — reconnecting MCP servers, refreshing the
 * connector directory, reconciling which speech models are active.
 *
 * **Suspending, and idempotent.** Suspending because the alternative was a plain function called straight
 * from a `LaunchedEffect` on the main thread, so a bootstrap that did anything synchronous did it on the
 * frame it was supposed to run after. Idempotent because the shell re-runs its effect on activity
 * recreation — a bootstrap that starts a permanent collector must not start a second one.
 */
fun interface DeferredBootstrap {
    suspend fun start()
}

/**
 * The deferred bootstraps this application installs, resolved by the shell and started once after the first
 * frame. Startup only mounts the shell; this network/DataStore work must never block or race the first frame.
 *
 * The shell used to call a `Koin.startSharedBootstraps()` extension that resolved the concrete bootstraps out
 * of the data layer — a UI-to-data edge for the sake of a startup call. The list is assembled where the rest
 * of the graph is (`:di`), and the UI only knows it has bootstraps to start.
 */
class DeferredBootstraps(
    private val tasks: List<DeferredBootstrap>,
    /** Bootstraps run here, never on the caller's (main) thread. */
    private val dispatcher: CoroutineDispatcher,
) {
    @Volatile
    private var started = false

    /**
     * Runs every bootstrap, once per process. The guard is the second half of the idempotency contract:
     * each task promises to tolerate a repeat call, and this makes sure there is not one to tolerate.
     */
    suspend fun startAll() {
        if (started) return
        started = true
        withContext(dispatcher) { tasks.forEach { it.start() } }
    }
}
