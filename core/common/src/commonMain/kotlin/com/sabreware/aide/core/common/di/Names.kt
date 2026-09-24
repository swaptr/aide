package com.sabreware.aide.core.common.di

import org.koin.core.qualifier.named

/**
 * Shared DI qualifier names (`named("…")`), centralized so BOTH the platform-agnostic `commonModules`
 * (this module, `:shared/commonMain`) and each platform module (e.g. `androidPlatformModule` in `:app`,
 * later a desktop one) resolve against the SAME qualifier instances. They are `public` (not `internal`)
 * because they cross the module boundary — a platform module in `:app` references them by the same
 * `com.sabreware.aide.di.*` package.
 *
 * These `named(...)` handles are the ONLY qualifier mechanism: there are no annotation markers to keep in
 * sync (koin-annotations was dropped — without koin-ksp on the classpath the annotations were inert).
 *
 * Code must NOT hardcode `Dispatchers.IO`/`Default`/`Main` — inject the qualified dispatcher below so tests
 * can swap in a `TestDispatcher` (the official Android coroutines guidance).
 *
 * The multibinding `Map<K,V>` qualifiers are mandatory: every `Map<K,V>` erases to the same runtime type,
 * so unqualified they would silently override each other. A capability whose collection became a named
 * registry type (chat/speech providers, asset sources) needs no qualifier and has none.
 */

// Dispatchers — the single place that names a concrete CoroutineDispatcher (platform provides the values).
val IO = named("IoDispatcher")
val DEFAULT = named("DefaultDispatcher")
val MAIN = named("MainDispatcher")
val MAIN_IMMEDIATE = named("MainImmediateDispatcher")

// Coroutine scopes (platform provides the values — SupervisorJob + a platform dispatcher).
val APP_SCOPE = named("AppScope")
val APPLICATION_SCOPE = named("ApplicationScope")

// Isolated Ktor clients + the connector Coil loader. AISDK_HTTP is the streaming client every `:aisdk`
// provider is constructed over — chat, image, speech, transcription — one transport for the whole SDK.
val AISDK_HTTP = named("AisdkHttp")
val SEARCH_HTTP = named("SearchHttp")
val CONNECTOR_HTTP = named("ConnectorHttp")
val CONNECTOR_IMAGE_LOADER = named("ConnectorImageLoader")

// Platform DataStore<Preferences> (file "user_prefs") backing the shared DataStorePreferenceStore.
val USER_PREFS = named("userPrefs")


// Multibinding maps — MUST stay qualified (see the erasure note above). The provider and asset-source
// registries no longer appear here: each is a named type of its own, which is what a qualifier was
// standing in for.
val WEB_SEARCH_PROVIDERS = named("webSearchProviders")
val WEB_FETCH_PROVIDERS = named("webFetchProviders")
