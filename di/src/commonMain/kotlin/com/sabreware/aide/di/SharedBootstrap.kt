package com.sabreware.aide.di

import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.common.startup.DeferredBootstraps
import com.sabreware.aide.data.connector.ConnectorDirectoryBootstrap
import com.sabreware.aide.data.connector.mcp.McpReconnectBootstrap
import com.sabreware.aide.core.common.di.IO
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Deferred bootstraps SHARED across every platform — run ONCE by `AppShell` in a LaunchedEffect AFTER the
 * first frame, NOT by the app entrypoints. Startup only mounts the shell; this network/DataStore work must
 * never block or race the first frame. Each bootstrap's `start()` is idempotent (the effect re-runs on
 * activity recreation). Written once here so a whole lifecycle stage can't be silently dropped per-platform:
 *
 * - [McpReconnectBootstrap] — reconnects persisted MCP servers AND is the only place OAuth access
 *   tokens are refreshed (near-expiry) before reconnect. Was Android-only, so desktop MCP servers vanished on
 *   restart and their tokens never refreshed.
 * - [ConnectorDirectoryBootstrap] — refreshes the live MCP-registry connector directory (TTL-gated).
 *
 * Platform-specific cold-start (speech residency, on-device model allowlist) is deferred too — it is
 * contributed by the platform's own module and collected here through `getAll`, so a platform task cannot
 * quietly go back to running inside `Application.onCreate`.
 *
 * The shell resolves [DeferredBootstraps] and calls `startAll()`; it never names a bootstrap, which is what
 * keeps the UI free of any dependency on the data layer.
 */
internal val sharedBootstrapModule: Module = module {
    // Collected, not listed — the same contribution rule the provider registries use, so a platform adds a
    // deferred task by binding one and nothing central changes.
    single { DeferredBootstraps(getAll<DeferredBootstrap>(), get(IO)) }
}
