# AIDE — engineering conventions

Read before adding UI or navigation. Reuse the shared pieces below; never re-derive them.

## Project stage — no backwards compatibility

Pre-release dev app, minSdk 35 / compileSdk 36. Wiping app data or reinstalling is fine.

- **No migration code.** Room uses `fallbackToDestructiveMigration(dropAllTables = true)`; DataStore/prefs
  evolve through kotlinx-serialization defaults + `enum.name`. Never write old→new key or schema migrations.
- **Every schema change bumps `@Database(version)`.** The destructive fallback fires only on a version
  change. Edit an entity without bumping and every install crashes at open (`IllegalStateException: Room
  cannot verify the data integrity`). Bump, let KSP write the next `N.json`, never touch old ones. Persisted
  documents follow the same ledger at `<module>/schemas/documents/<name>/N.json`: bump
  `PersistedDocument.version` and let `PersistedDocumentSchemaTest` write the file. `./gradlew schemaCheck`
  fails the build on a rewritten schema file.
- **No `Build.VERSION.SDK_INT` branches below 35.** Call the modern API, delete the `else`.
- **No `@Deprecated` shims, legacy paths or "kept for later" placeholders.** Delete dead code.
- **Keep** (forward resilience, not back-compat): JSON `ignoreUnknownKeys = true`, Room `exportSchema = true`
  + AutoMigration setup, network/engine anti-brick error handling, framework-required deprecated overrides
  (e.g. `UtteranceProgressListener.onError`).

## Module map

**Package path mirrors module path.** `com.sabreware.aide.platform.android.surface.ime.widget.KeyPopup` lives
in `:platform:android:surface:ime`. No package spans two modules. `docs/code-map.md` (`make map`) is the
generated index; `./gradlew codeMapCheck` fails the build on a split package, a package that does not mirror
its module, or a `package` declaration that contradicts its directory.

Every arrow points down. The sketch shows the shape; `docs/module-graph.md` (`make graph`) is generated from
the real dependencies and is authoritative.

```
app  /  desktopApp                       the two applications — each also HOLDS its platform-only code
 ├── di                                  the Koin graph — the ONLY module that sees both sides
 │    ├── ui                             shared responsive UI: screens, ViewModels, nav graph
 │    └── data                           shared implementation (Room, catalog, search, image, model registry,
 │         │                             prefs, Ktor factory, download stack)
 │         ├── data:llm                  the :aisdk seam — chat session, vendors, catalogs, modality engines
 │         ├── data:speech               speech assets + install        (both engines build on it)
 │         │    └── data:speech:sherpa   the on-device engine, shared by both apps
 │         ├── data:tools                portable toolsets              (:app's device tools build on it)
 │         └── data:connector            connectors + MCP               (both apps' OAuth halves build on it)
 ├── feature:tasks                       the IME's saved prompts, hosted in Settings by :app
 ├── platform:android                    Android machinery :app AND the IME both need
 ├── platform:android:surface:ime        the keyboard — own component lifecycle, UI and manifest
 └── core:designsystem                   Compose foundation: components, theme, resources, Navigator, Feature
      ├── core:domain                    value types, ports, use cases
      │    └── core:common               dispatchers, prefs, attachments, paths, DI names
      └── (nothing else)

aisdk                                    portable AI SDK (Kotlin port of Vercel's provider spec); depends on
                                         NOTHING of ours; :data:llm consumes it
server                                   separate Spring Boot service; shares no code
```

- **`:core:common`** — dispatchers, `PrefKey`/`PreferenceStore`, attachments, `PlatformPaths`, DI qualifier
  names. Depends on nothing of ours.
- **`:core:domain`** — value types, ports, use cases. No Compose, no Android, no `:data`. Only what BOTH apps
  compile.
- **`:core:designsystem`** — components, theme, composeResources, the `Navigator` port and the
  `Feature`/`SettingsFeature` contract (Compose types, so they stay out of `:core:domain`).
- **`:data`** — one module, one package per domain. The four sibling modules (`:data:llm`, `:data:speech`,
  `:data:tools`, `:data:connector`) exist only because a consumer needs them without all of `:data`.
- **`:ui`** — shared responsive UI. Depends on ports, never on `:data`. commonMain only.
- **`:di`** — the Koin graph; the only module that sees `:data` and `:ui`.
- **`:platform:android`** — Android machinery both `:app` and the IME need (permission gate, intent broker,
  launcher intents).
- **`:platform:android:surface:ime`** — the keyboard (see Surfaces).
- **`:feature:tasks`** — the IME's saved prompts; two consumers (the IME, `:app`'s Settings row).
- **`:aisdk`** — Kotlin Multiplatform port of the Vercel AI SDK (Apache-2.0): spec, utilities, providers,
  runtime. Knows nothing about AIDE (no `ProviderId`, `ChatProvider`, Koin or `:core:*`). Arrow is
  `:data:llm -> :aisdk`, never back. Both apps reach every remote model through it: chat runs on
  `AiSdkChatSession`; image, speech and transcription are thin engines over its modality wrappers. It
  carries `providerMetadata`, an opaque provider-namespaced map on every content and stream part, so vendor
  payloads (Anthropic thinking `signature`, Gemini `thoughtSignature`) survive the neutral layer. Design:
  `aisdk/DESIGN.md`; open work: root `TODO.md`.
- **`third_party/`** — reference material only (`vercel-ai`, `koog` submodules, the Sherpa AAR). Never
  compiled, depended on or imported; invisible to the build checks.
- **`:server`** — Spring Boot + Postgres. Shares no code with the app; nothing depends on it.

**When to make a module** — exactly three reasons:

1. Both applications compile it (`:ui`, `:data`, `:core:*`, `:di`).
2. A second consumer needs it without a whole layer (`:data:*` siblings, `:platform:android`, `:feature:tasks`).
3. It is an OS entry point with its own lifecycle and manifest (`:platform:android:surface:ime`).

Everything else is a **package inside the application that owns it**: device toolsets, Android data halves,
LiteRT, the assistant surface, the permission trampoline, the desktop platform layer. Split when a second
consumer appears, not before.

**Layering is enforced by `./gradlew dependencyDirectionCheck`**: each module has a rank and may depend only
on lower ranks, plus bans: `:ui` never sees `:data`; `:data` and device toolsets never see the design system;
`:platform:android` stays Compose-free; `:server` shares nothing; only `:di` and the two apps see `:ui`. If a
lower layer needs something above it, invert with a port (`BundledAssetReader`, `DeferredBootstraps`,
`Context.launchAppAt`).

### Surfaces

A **surface** is an OS entry point from outside the app: the IME (`InputMethodService`), the assistant
(`VoiceInteractionSession`). Surfaces live at `:platform:<target>:surface:*`. Every OS has them (Android:
IME, assistant, share target, tile, widget; desktop: tray quick-ask, hotkey overlay; iOS: keyboard
extension, App Intents, share extension); only the set differs per target.

- **A surface owns its UI.** Its shape is dictated by the OS API. Never make it reuse a shared screen.
- **The engine underneath is shared.** Surfaces consume the same ports as everyone else
  (`SendChatMessageUseCase`, speech engines, `ToolsetRegistry`); nothing under `:core`/`:data` knows a
  surface exists.
- **Do not pre-extract** the standing/enable state machine (`AideKeyboardManager`, `AideAssistantManager`).
  Lift it into a shared module when a second platform grows the same surface.

## Capabilities are contributed, never listed

Chat, speech, image generation, downloads and tools all plug in one way. Do not invent another.

**A capability = a port in `:core:domain` + a named registry that collects whatever was bound**
(`ChatProviderRegistry`, `SpeechProviderRegistry`, `ImageProviderRegistry`, `AssetSourceRegistry`,
`ToolsetRegistry`). Registries are named types, not generics: two erased `ProviderRegistry<*>` singles would
collide in Koin (the same erasure forces `named(...)` on `Map<K, V>` multibindings).

```kotlin
// wherever the thing lives — :di for anything portable, :app for an Android-only one:
single { GeminiVendor(get()) }.contributes(Vendor::class)     // a cloud wire: its connections are data
single { LocalProvider(…) } bind ChatProvider::class          // :app only

// once, in :di:
single { ChatProviderRegistry(getAll<ChatProvider>()) }
```

- **The binding IS the registration.** No central map, no per-platform copy. Whether a capability exists on a
  target is a property of that target's module graph.
- **One class per definition.** Koin keys by primary type, so two `single { Foo(…) }` blocks collide. Pinned
  by `:di` `ProviderContributionTest`.
- **Cloud implementations are commonMain** (Ktor + injected engine). **Platform implementations live in
  platform modules.**

**Never write a second class whose only difference is policy.** Inject the difference as data:

- `SpeechResolutionPolicy` — ordered fallback ladder, last entry terminal. Android `[SHERPA, ANDROID_SYSTEM]`,
  desktop `[SHERPA]`.
- `EngineLoadPolicy` — behaviour around a model load (Android trims memory and retries on CPU; network-only
  targets use `Direct`).
- `MimeTypeResolver` — the few lines of `JvmFileSystemBackend` that differ per JVM.

**One download stack.** `DownloadScheduler` (`:core:domain`) takes `(kind, id)`; the registered `AssetSource`
owns the URL and on-disk layout. A new downloadable is one `AssetSource` binding. Below the UI, paths are okio
`Path`, and **every port member that touches disk is `suspend`** (`ModelStorage`, `AssetSource`).

**Deferred startup work is contributed.** A `DeferredBootstrap` is a `suspend`, idempotent one-shot that
`AppShell` runs AFTER the first frame; `:di` collects them with `getAll`, each bound by its own class. Nothing
starts in `Application.onCreate`. Every surface that can be a process's first (app shell, IME, assistant)
calls `DeferredBootstraps.startAll()` after its first show; the guard makes repeat calls free. The only
startup exception is a **startup document** (see Async state).

**Tools declare whether they only read.** `AideTool.Function.readOnly` defaults to **false**. Only read-only
tools may be served from the idempotency cache, keyed by surface and turn. (A cached write once returned a
previous "text Bob" success without running.)

**A toolset describes itself**: category, display name, blurb, on-demand flag, permission requirement,
prompt guidance. `ToolBundleFactoryImpl` stamps each tool with its owner's category. Settings draws rows
from the registry. Adding a tool = its files + one binding. No name tables, enum entries or constructor
parameters.

**Cloud providers are connections, not bindings.**

- A **vendor** is code: a wire (`VendorId`: OpenAI-compatible, Anthropic, Gemini, ElevenLabs), bound with
  `single { XVendor(get()) }.contributes(Vendor::class)`. It lists **services** (`ServiceDescriptor`: OpenAI,
  OpenRouter, Groq, Ollama…; one flat catalogue, each service once). A new service on an existing wire is one
  `service(...)` line.
- A **connection** is data: one account or endpoint the user added (`connections` document + key in
  `SecureStore` at `connection.<id>.api_key`). Many may share a service. **A connection's id IS its
  `ProviderId`**; cloud model ids are `"<connectionId>:<wireId>"`.
- `ConnectionRuntimesImpl` turns connections into providers once (one collector; a runtime lives as long as
  its endpoint; removal cancels its scope). Every provider registry is `static + dynamic`: `get`/`all` are for
  PAINTING, `await(id)` is for ACTING (it waits out "connections not read yet", which is `null`, never an
  empty list). What an endpoint serves is decided per connection (an Ollama endpoint gets no speech or image
  rows). Names come from ONE port, `ProviderDirectory` (alias ?: connection name, kind, service, tags).

**Worked example of the whole pattern:** image generation (`:core:domain/image`, `:data/image`) — a port, a
commonMain engine, two `:di` bindings, no platform file.

**A forgotten binding compiles.** Koin resolves lazily. `DesktopKoinGraphTest` (`make check`) and
`AndroidKoinGraphTest` (`make device-test`) resolve every definition; run them after touching DI. A new
application target gets the same test.

### The ratchet

Breaking one of these is a bug, not a trade-off:

- Every module arrow points down (`dependencyDirectionCheck`).
- `commonMain`/`commonTest` import no `java.*`/`javax.*`/`android.*` (`portabilityCheck`).
- `:ui` has no platform source set and no `expect`/`actual`.
- Packages mirror modules (`codeMapCheck`).
- Schema changes bump their version; `schemaCheck` refuses a rewritten `N.json`.
- Every host test task runs in CI. `aideCheck` / `aideTest` in the root build are the single task lists that
  `make`, CI and the release workflow call.
- A capability exists on a target **iff** its module is in that target's dependency list — never a runtime
  flag, never an empty-list `expect`.
- Registries are contribution-collected; no hand-written per-platform maps.
- An aggregated snapshot stays unresolved until every source has SETTLED; "not yet" is a state in the type
  (`ModelGateState.Unresolved`). Pinned by `ModelRegistrySettlementTest`.
- No per-platform class whose only difference is policy.
- Cloud implementations commonMain; platform implementations in platform modules.
- One download stack; okio `Path` below the UI.
- Hand-written docs never restate what `docs/code-map.md` / `docs/module-graph.md` generate.
- A capability a target lacks is bound NOWHERE — no stub that throws, returns `Result.failure` or renders an
  empty list. The consumer takes the port as nullable and omits the affordance.
- An affordance is drawn only when something is wired to it (`ReplyActions` handlers are nullable).
- Release builds are shrunk. `app/proguard-rules.pro` covers what R8 cannot see: JNI classes, generated
  serializers, Room implementations, ServiceLoader engines.
- `detekt` (`config/detekt/detekt.yml`) runs on every check at zero findings, never baselined.

## Targets, hosts and gating

Five platforms, **three Kotlin targets**:

| Kotlin target | Hosts | Varies per host |
|---|---|---|
| `android` | Android | — |
| `jvm("desktop")` | Linux, macOS, Windows | app data dir, secret vault, tray vs menu bar, autostart |
| `ios*` | iOS, iPadOS | — |

macOS and Windows add no source set or module. iOS adds a target every shared module must satisfy.

Gating happens at two moments, with one grammar — **absent means nothing is bound, the port resolves to
null, the affordance is not drawn**. Never a stub, never `if (isMac)` inside an implementation.

- **Compile time — the module graph.** What a target can do. Each app composes its own list:

  ```kotlin
  val appModules     = commonModules + androidPlatformModule + featureModules(commonFeatures + androidFeatures)
  val desktopModules = commonModules + desktopPlatformModule + featureModules(commonFeatures)
  ```

  Never reintroduce `expect val platformFeatures`: it forces a platform with nothing to declare `emptyList()`.
- **Startup — the binding.** What this host can do. Detect once, choose the implementation, inject it.

**Desktop directories are resolved once.** `DesktopAppDirs` (`:desktopApp` `desktop/storage/`) maps
`DesktopHost` to XDG on Linux, `Application Support` on macOS, `%APPDATA%` on Windows. `desktopModules(dirs)`
receives it. Nothing else asks where the data dir is: `desktopDatabaseBuilder` takes the directory;
`PlatformPaths`, `SecureStore` and the connector cache take `DesktopAppDirs`.

## JVM-shared code

Code that needs the JVM but is identical on Android and desktop goes in **`src/jvmShared/kotlin`**, which
`aide.kmp.library` hands to both JVM source sets (a shared directory, not a `dependsOn` set). Check commonMain
first: okio and Ktor cover most JVM-looking code. Residents: the Sherpa engines, `JvmFileSystemBackend`,
`SpeechBundleExtractor`.

## Features

A navigable unit is a **`Feature`** (`com.sabreware.aide.core.feature.Feature`). One object declares how it
plugs in, and the app iterates the one injected `FeatureRegistry`. Never branch on platform or feature
identity.

- `EntryProviderScope<NavKey>.entries()` — its pages, registered once for every host. Outsiders navigate to
  its home route. Reference: `:feature:tasks` `TasksFeature.kt`.
- `PolymorphicModuleBuilder<NavKey>.routes()` — `subclassesOfSealed<XRoute>()` for the route types it owns,
  so a back stack holding them saves on every target. The shared `:ui` routes are registered by the shell
  (`navStateConfiguration`).
- `koinModule` — its DI (ViewModels, repos, even its own database). Most add none.
- `handleDeepLink(dest, nav: Navigator)` — returns true if it claims the link. `AppShell` tries features first.
- `SettingsFeature : Feature` adds `section`/`order`/`row`/`route`; `SettingsScreen` renders
  `FeatureRegistry.settingsFeatures` by section.

Consumers are uniform iterations: menu (`SettingsScreen`), pages (`AppNavGraph.appEntries`:
`features.features.forEach { with(it) { entries() } }`), saving (`navStateConfiguration`), DI
(`featureModules(...)`), deep links (`AppShell`). Core shell pages (chat, chats, custom instruction,
settings) stay in `appEntries`.

**A feature owns its whole subtree, including storage.** An Android-only feature with Room tables gets its
own Android-only database, not tables in `AideDatabase`. Reference: `:feature:tasks` `data/TaskDatabase.kt`
— classic `Room.databaseBuilder(ctx, X::class.java, name)`, schema exported to the feature's `schemas/`. The
DB builder single lives in `:app` (needs `androidContext()`); DAOs, repo and VMs live in the feature's
`koinModule`. Its routes are its own `@Serializable sealed interface … : NavKey` (like `TaskRoute`), not
members of `navigation.Route`.

Inline preference rows (a value + a sheet, e.g. Color theme) are **not** features; declare them in
`SettingsScreen`.

## Navigation

**ONE back stack, ONE page registration, ONE container rule** (Navigation 3). `AppShell` holds the back stack
(`rememberNavBackStack`, saved through process death) and one `NavDisplay`. A page never knows whether it is a
full screen, a sheet page or a dialog page; the back-stack element decides:

- A route key (`Route.Settings`, `ModelRoute.Home`, …) is a **screen**.
- `InModal(flow, key)` is that same page **in a modal container**. `ModalSceneStrategy` renders a trailing
  run of one flow as ONE sheet or dialog (`ModalPolicy` picks) over the page beneath. It is a regular scene
  keyed by (flow, page beneath), so pages push inside it without re-opening the container, with the app's
  `NavMotion`; predictive back scrubs a pop; back on the flow's first page closes it. Never render a flow as
  an `OverlayScene`: NavDisplay keeps the first overlay instance and computes back from the scene beneath.
- Presentation is the CALLER's intent (Settings pushes Models as a screen, the chat pill opens it in a
  modal), so it lives on the element, never on the route type.

Rules:

- **Leaving a page is never a parameter.** No screen takes `onClose`/`onBack`. Chrome (`AppPage`,
  `AppScaffold`, `PageScaffold`) with `leadingAction = null` draws a back chevron on a pushed page and nothing
  on a flow's first page. A top-level screen passes `HeaderAction.drawer(…)`. A page that must close itself
  calls `navigator().goBack()` (in a flow's first page, that closes the container).
- **The chevron follows the RENDERED page's depth**, never a live back-stack query: the shell's page-depth
  entry decorator for screens, the modal scene for flow pages. Predictive back composes the page underneath
  before the pop commits.
- **Moving between pages goes through `navigator()`** (`navigate`, `goBack`, `replace`); the nearest navigator
  decides what a push means (a screen, or a page in the open flow). Shell-only moves are explicit stack edits
  on `AppNavigator` (`openChat`, `resetTo`). Opening a flow in a modal is `Navigator.openModal(flow, start)`,
  wrapped once per flow (`openModelFlow()`, `openConnectorFlow()`).
- **Routes**: `@Serializable sealed interface XRoute : NavKey`, **primitive args only** (pinned by
  `RouteArgShapeTest`; pass `enum.name`, resolve rich types from an id inside the page). Every route type is
  registered for saving — shared `:ui` routes in `navStateConfiguration`, a feature's in `Feature.routes`.
  Off Android nothing is found by reflection, so an unregistered route crashes only there.
- **A ViewModel gets its route from its page's entry**: `koinViewModel { parametersOf(route) }`, never
  `SavedStateHandle.toRoute` (Navigation 3 keeps route args out of it). The Koin graph tests supply one route
  of each kind by type.
- Every entry gets its own saved state and ViewModel store (entry decorators), whether it is a screen or a
  flow page.

### Flows — one definition, screen AND modal

References: `:ui` `ui/models` (`ModelFlow.kt`), `:ui` `ui/settings/mcp` (`ConnectorFlow.kt`). Never write a
screen-only or modal-only variant.

- `XRoute.kt` — the flow's routes (above).
- `XPages.kt` — one `@Composable` per route, each a `PageScaffold(title, actions) { contentModifier -> … }`.
  No per-host code in a page.
- `XFlow.kt` — `fun EntryProviderScope<NavKey>.xEntries()` registering each page ONCE (a feature's
  `entries()` calls it), plus `fun Navigator.openXFlow()` for callers that want it in a modal.
- Prefer intrinsic mutations over callbacks: write to the repo and let observers react
  (`ModelsViewModel.select → registry.recordSelected`). No `onSelect` plumbing.
- Leaf modals (confirm, sampler, import, provider picker) stay plain `AppDialog`/`ConfirmDialog`; they stack
  over a flow. Not every dialog is a route.

## Async state

**The first frame paints from memory.** Never draw a default, empty list or skeleton for data the process
already has.

| Source | Data layer | ViewModel seed | Warm it |
|---|---|---|---|
| Expensive snapshot (disk, decrypt, N-flow fold) | `flow.snapshotCache(appScope, name)` (`:core:domain` `cache/`) | `stateInUiCached(scope)` / `stateInUiSeeded(scope, cache.value)` | add to `:di` `CacheWarmup` if a first open is one tap away |
| Preferences | `PreferenceStore` (one snapshot per commit) | `prefs.selectState(scope) { p -> State(p[KeyA], p[KeyB]) }`; with other inputs, `combine(prefs.select(::build), …)` seeded by `build(prefs.current)` | automatic (`createdAtStart`) |
| First-frame state (model choice, theme) | startup document / `prefs.current` | `AppAppearance(prefs) { … }` at every root | each host holds its first draw on it (capped): `MainActivity` pre-draw, desktop `main`, IME `onCreateInputView` |
| Secrets | `SecureStore.observe` (dedupes on ciphertext); multi-key writes via `write(map)` | `snapshotCache` when >1 consumer (`WebSearchCredentialsRepository.configured`) | — |
| "Not read yet" | — | `UiState.Loading`, never a null/empty seed | — |

**Reading values**

- `prefs.current`/`peek`, a cache's `.value` and a startup document's `state.value` are for PAINTING only.
  Never use them to decide, or as the read half of a read-modify-write: an uncollected `WhileSubscribed`
  state's `.value` is its seed. Read the store (`PreferenceStore.update`, `PreferenceStore.get`,
  `McpServerRepositoryImpl.mutate`).
- A decision right after a mutation (pick a fallback after delete) reads a FRESH query (`chatsSnapshot()`).
- A rule both a seed and a flow need is ONE function (`effectiveModelId`, `keyboardAppearance()`).

**Loading**

- **Startup mounts the shell only.** Only structural shell state (sidebar open, window class) loads at the
  app root / `AppNav`. Everything else resolves when its component mounts, with its own loading visual. Never
  gate the first frame.
- Data an animated panel needs at OPEN time is collected at the app root once per session, so the panel never
  queries mid-slide (`AppViewModel.chats` → `AppShell` → drawer).
- Flows whose emissions do I/O get `flowOn(Dispatchers.Default)` at the repo.
- A scheduler/WorkManager status never gates a snapshot: `onStart { emit(Idle) }`; disk decides Completed.
- A list whose length is known draws that many skeleton rows.
- Cold costs are logged (`snapshotCache`: `first snapshot in N ms`; `CacheWarmup`: `warm in N ms`). Read them
  before adding a warm-up.

**Caching expensive snapshots.** A repo whose flow stats disk, decrypts or folds N flows exposes
`StateFlow<T?> = flow.stateIn(appScope, WhileSubscribed(5s), null)` (null = not resolved this session) instead
of a cold flow per call. ViewModels consume it with `stateInUiCached(viewModelScope)`, which seeds Ready
synchronously (stale-while-revalidate). A derived flow seeds with `stateInUiSeeded(scope, cache.value)`.
References: `ModelRegistryRepository.models`, `SpeechAssetRepository.assets`, `McpServerRepository.servers`,
`ChatRepository.chats`.

**Resolution rules**

- **An aggregate is unresolved until EVERY source has settled** — and emitting is not settling. A merged
  snapshot publishes `null` while any source works, because once non-null every consumer commits (chat header,
  IME bar, assistant chip). A source mid-first-fetch with nothing cached is UNSETTLED. Worked example:
  `RemoteCatalogState.isSettled` (`Refreshing(previous = emptyList())` → false; `Failed` → true, so a dead
  network ends the skeleton). Pinned by `ModelRegistrySettlementTest`.
- **"Not yet" is a member of the type** (`ModelGateState.Unresolved`, `ModelResolution.Unresolved`), never a
  boolean or `NoModel` seed. A call to action belongs only to a SETTLED negative.
- **Record the label with the choice.** When the answer is slow but the surface only needs a name, store the
  name beside the choice: `ModelSelection.cards` + `ModelResolution.Cached` paint the model pill while
  `hasModel` stays false until the model resolves. A cached label may only paint.
- **Resolve the one thing acted on, not the aggregate.** `ModelGateState` and
  `ModelRegistryRepository.resolve(id)` consult only the chosen model's source, so a local pick never waits on
  a cloud catalog. "Nothing chosen" settles when the choice document is read; nothing picks for the user. An
  unusable choice is `ModelGateState.Missing(card)`, named, never silently replaced. "The chosen model" is
  `ModelSelection.chosenFor(modality)` everywhere. The only substitution is the user's `ModelFallback`
  (`Ready.reroutedFrom`): it never rewrites the choice, and surfaces name the stand-in while it lasts.
- **Startup documents** — the one thing read at process start. A `PersistedDocument` (`:core:common`
  `persist/`) is a `@Serializable` class versioned in `schemas/documents/<name>/N.json`, held by a
  `DocumentStore` (typed DataStore over okio: atomic writes, one writer, corruption policy by `Durability`).
  Bound `createdAtStart`, it is in memory before any surface draws. Reserve for small, local, crypto-free
  first-frame state; today only `ModelDocuments.Selection`.
- **An unreadable file never reads as "chose nothing".** A document whose default means that sets
  `defaultWhileUnreadable = false` and stays `DocState.Loading` until a read succeeds.

**The kit** (`:core:designsystem` `state/`)

- **Model** → `UiState<T>` (Loading / Ready / Failed), produced with `flow.stateInUi(viewModelScope)`.
  `Ready(emptyList())` IS the empty state.
- **Full pane** → `StatePane(state, loading = { … }) { value -> … }`: one crossfade (`StateMotionMs`); the
  loading visual appears only after `LoadingRevealDelayMs`.
- **Skeletons** → `SkeletonListRow` / `SkeletonTextBar` / `SkeletonCapsule` (`SkeletonShapes.kt`) over ONE
  `rememberSkeletonShimmer()` per surface; rows read `LocalAppListItemStyle`. References: drawer chats
  (`AppShell`), `ConnectorCatalogContent`, `ModelSelectorPill`.
- **Notices** → `AppNotice(text, severity = Info|Warning|Error)`; transient → `AutoDismissNotice(text,
  onDismiss)`. Info = secondaryContainer, Warning = tertiaryContainer, Error = errorContainer. Field
  validation → `AppTextField` errorText; app-wide notices → `AppBanner`.

## Collections — search, filter, select, act

Every list users search, filter, multi-select or act on (models, connections, chats, connectors, tags) runs
on ONE framework. Never hand-roll a filter menu, selection mode or per-row action builder.

- **Engine** — `:core:domain` `browse/`: `BrowseSpec<T>` (key, searchable text, `Facet`s, order, grouping) and
  `spec.run(items, query)`: relevance-ranked search (every token matches; name ranks highest), faceted counts
  (OR within a facet, AND across), exclusive facets with a default (a VIEW such as chats' All/Pinned/Archived),
  sections. Pure; runs over a local snapshot or a remote page.
- **Header** — `collectionBar(title, browse, placeholder, facets, actions, select)` is the ONE header of
  every collection page and sheet:
  - Browsing: Search, the page's own `actions`, Select. Past the button budget the tail folds into More.
  - Searching: the band becomes the `SearchField` in place (focused, keyboard up, no navigation) and Filter
    (when there are facets) crossfades into the trailing slot. **Filtering exists only inside search.**
  - Back leaves search and drops text AND filters. A page opened pre-filtered (`rememberBrowseState(initial)`)
    is the filtered list, not a search: no keyboard, Filter shown badged, the filter named as the subtitle,
    and leaving search returns to that filter. Back from it goes where it came from, never to its unfiltered
    self.
  - Filter carries a Material badge with the active filter count (`HeaderAction.badgeCount`).
  - Selection: wrap in `collectionHeader(...)` (Done, "N selected", select-all, bulk actions). Hand the
    result's `title` / `leadingAction` / `trailingActions` / `titleContent` to `PageScaffold`,
    `AppScaffold` or `AppDialog`.
  - A tabbed page keeps ONE `BrowseState` and puts its own actions in the header as `actions`, swapped per
    tab (add-model: Connect always, Import file on On-device), not as tiles over the tabs.
  - A slow search (API, directory) feeds the same text to `search/rememberSearchResults(text) { … }` and runs
    the spec over the result (connector catalog).
- **Kit** — `rememberBrowseState`, `rememberBrowseResult`, `BrowseFilterSheet` (hosted by the bar; re-tap a
  chosen option to drop it; no pills in the body), `BrowseNoMatches`.
- **Actions** — `CollectionAction<T>` is one definition for a row's long-press sheet, a header menu and a
  selection (`ActionScope`, `toggleAction`, built-in `Confirmation`, `leavesSheet`), run through
  `rememberActionRunner`. A page's own actions (Add, Connections, Tags, Refresh) are tiles above the list:
  `AppMenu(layout = AppMenuLayout.actions())`, never behind More. One item's actions in its sheet:
  `ActionRail(runner, item)`.
- **Browsing-only content** (page tiles, an item's rail, a New chat FAB) goes in `WhileBrowsing(browse,
  selection) { … }`, which folds it away with `ChromeMotion` while searching or selecting. Never write
  `if (!selection.active && !browse.searching)` in a page.
- **Shared actions are one definition.** An action offered in more than one place is one function every
  place calls: `rememberConnectAction()` (the Connect tile, empty-state button and Connect sheet),
  `rememberConnectionRunner(...)` (Edit, Test, Remove on a connection), `tagsEntry(nav)`. Never copy a tile,
  a sheet or its wiring into a second page.
- **Pickers** — plain `AppMenu` rows with `AppMenuEntry.selected`. A chosen row is brightened, never ticked or
  badged. Re-tap un-chooses where sensible. Never add a picker component.
- **Rounded, never flat** — lists in a page or sheet are rounded segmented sections: `AppMenu` when bounded,
  `LazyListScope.appMenuSection(items, key, title) { row }` when lazy (rows are `AppListItem`s).
- **Labels** — `:core:domain` `label/`: name, tags and pin for ANY subject (`LabelSubject` `model:` /
  `connection:` / `chat:` / `connector:`), one `labels` document with a tag vocabulary. `:ui` `labels/`:
  `LabelsViewModel`, `rememberLabelEditor`, `labelActions(...)` (Rename, Tags, Pin), `TagsPage`,
  `Labels.tagFacet`. A new labelled thing mints a subject kind, never a `customName` column. Model aliases
  apply where the registry builds rows (`ChatModelSpec.named`).

## Shared components (mostly `:core:designsystem`)

- **Header** → **`AppHeader`**, ONE function drawn by every host: screen top bar (`AppScaffold`/`AppPage`),
  flow page (`PageScaffold`), every sheet and dialog (`AppDialog`).
  - Buttons are DATA: `HeaderAction` (icon, label, enabled, destructive, `onClick` XOR a `HeaderMenu`), in
    two nullable slots, `leadingAction` and `trailingActions`. An empty slot reserves no width; a slot
    crossfades and resizes when its labels change, to and from empty too, so both slots are always drawn
    through `HeaderActionRow`, never behind an `if`.
  - The band shows `title`/`subtitle`, or `titleContent` when set (chat model picker, search field).
    **Swap title and band by argument, in ONE `AppHeader` call — never an `if` over two calls.** Two calls
    are two composables: the header is rebuilt and its slots snap instead of animating.
  - Spacing and height are ONE spec, `HeaderBandStyle` (`LocalHeaderBandStyle`): each band side sits at
    `AppMenuTextInset` or clear of its slot, whichever is wider. Every header is `HeaderPlacement.Page`, in
    every host; only the chat model picker is `CenteredPage`. Pinned by `HeaderBandTest`.
  - Never hand-roll an `IconButton` or a Box + open-state menu in a header.
- **Sheets / dialogs** → `AppDialog` (custom `AnchoredDraggable`, not `ModalBottomSheet`), drawn in the app's
  OWN window through `ModalHost` (installed by `AideTheme` at every root), never a platform `Dialog`/`Popup`
  window: one composition, one frame clock, one set of insets, so a page animates identically on a screen and
  in a modal. Content a modal covers gives up focus and its keyboard; an auto-focus engine checks
  `LocalCoveredByModal` and stands down while it is true (the chat composer does). Tests that open a modal use
  `setModalContent` (a flow page: `ModalFlowHost`). Multi-page modals
  are flows on the app back stack (see Navigation), never a private stack. **Sheet vs
  dialog is never decided at a call site**: each app provides a `ModalPolicy` (`LocalModalPolicy`; Android
  `Adaptive`, desktop `Dialog`) and `AppShell` resolves it into `LocalModalPresentation`. `Adaptive` = centered
  dialog only when the window is ≥ 600dp wide AND ≥ 480dp tall. Never branch on device type or width alone.
  Pinned by `ModalPresentationTest`.
- **Scrolling is inferred — never add `verticalScroll` to a body** (`ScrollOwner`). Every host scrolls its
  body under a pinned header: leaf `AppDialog`, `PageScaffold` (default), `AppPage`, bounded
  `PlaceholderLayout`. The only opt-out is a body that is itself a scroller: `PageScaffold(scroll =
  ScrollOwner.Content)` gives it a bounded fill-height slot. A nested same-axis scroller throws and a vertical
  `weight()` collapses to 0 inside a scrolled body. Raw `AppScaffold` is for fill-height list screens only.
  Pinned by `ScrollContractTest`.
- **Grouped list / menu / tiles** → **`AppMenu(items, layout)`**: one component, one data model
  (`AppMenuEntry`), one look (rounded outer corners, square inside, transparent seam, no divider). Shape is the
  layout argument: `AppMenuLayout.Rows` (default) · `Rail(tileWidth, tileHeight)` · `Grid(columns,
  tileHeight)`; per-layout knobs live on the layout. **No content slot**: extra row UI is data
  (`AppMenuEntry.action` = an icon button, optionally badged). Internals are `internal`. Arbitrary content in
  the same card → `AppMenuCard`. Unbounded lists → `AppListItem` in a `LazyColumn`. Section label →
  `AppMenuSectionTitle`.
- **Text that does not fit** → `MarqueeText` (or `Modifier.marquee()` on a one-line `Text`) — never a bare
  `maxLines`/`Ellipsis` title or subtitle. At rest it is an ellipsis. Each text runs its own clock: in view
  1.2s (+ stagger), slide by width + gap, rest 3s, repeat. Leaving view (or `CoverMarquees` under a modal)
  stops only that text. Hosts: `MarqueeHost` (`AppShell` root; `AppDialog` its own). Multi-line text never
  walks.
- **Empty states** → `Placeholder(iconRes?, title?, subtitle?, actions)`, top-anchored, owns its padding.
  Richer content → `PlaceholderLayout { }`. Position comes from `LocalPlaceholderStyle`; only the chat home
  uses `PlaceholderStyle.Hero`. Never pad a placeholder by hand.
- **Confirm** → `DeleteConfirmDialog(title, message, onConfirm, onDismiss)` / `ConfirmDialog`. Single-line
  input → `TextInputDialog`.
- **Models, any kind** → `LibraryItem` list (`:ui` `ui/models/Library.kt`) rendered by `LibraryRow` in
  `AvailabilityGrouping` (Pinned · In use · Installed · Built in · Ready to use · Downloading · Available to
  download), acted on through ONE `libraryActions(...)`, opened (never navigated to) in the ONE model sheet
  (`rememberModelSheets` + `ModelSheetHost`, body `ModelDetails`). Byte sizes → `humanBytes()`.
- **Progress** → `AppLinearProgress`, never raw `LinearProgressIndicator`.
- **Preferences** → one `PrefKey` line next to the owning feature (`SearchPrefs`, `ToolPrefs`, `ModelPrefs`,
  `SpeechPrefs`, `KeyboardPrefs`, `ConnectorPrefs`, `ShellKeys`), read via `PreferenceStore`. No preferences
  repository or god interface. Behaviour over a key is a `PreferenceStore` extension beside it. Meaningful
  absence → `nullableStringKey`/`nullableEnumKey`. View state = `Tier.UiState` + `ui.` prefix; user intent =
  `Tier.Settings`. Tests: `FakePreferenceStore(Key to value)`.
- **Attachments / gated inputs** → the part→capability rule lives once in `:core:domain`
  `model/InputModality.kt`; a new part type extends it (+ each wire codec). Recording UI →
  `VoiceRecordButton`/`VoiceBars`.

## UI conventions

- **`:ui` is commonMain only**: no `androidMain`, `desktopMain` or `expect`/`actual` (an `expect` forces
  every target to write an `actual`, even an empty `= {}` that draws a dead button).
- **Host capabilities are nullable members of `PlatformAffordances`** (`:ui` `ui/platform/`), provided per app
  through `LocalPlatformAffordances`. Null = the host cannot; omit the affordance. Its `rememberLauncher(...)`
  shape matches Android's `rememberLauncherForActivityResult`. A portable library is not a platform fork:
  call FileKit from commonMain.
- **Theme tokens only**: `MaterialTheme.colorScheme`, `AppSpacing`, app fonts. No hardcoded `Color` or raw dp.
- **Chrome motion is one spec**, `ChromeMotion` (duration, easing, vertical enter/exit). Header slots and
  `WhileBrowsing` use it so the header and the list under it move together. Never tune a separate spec.
- Per-call layout knobs go in a CompositionLocal style, not loose params on a shared component.
- User-typed text = AppSans; assistant reply = AppSerif.
- **Lean menus**: row titles, tile labels and section labels regular weight (`titleMedium`/`titleSmall`);
  subtitles `onSurfaceVariant`; only headings (`titleLarge`) bold. A chosen row is told by its brighter fill.
  Rail tiles size to their label, clamped between square and `RailTileMaxWidth`.
- **Copy is direct.** Titles a few words; a subtitle says what the option does in one plain sentence. No arrows
  or symbols as grammar, no "tap X → Y" chains, no hedging.
- **Leading visuals share ONE fixed slot** (`AppListItemStyle`: `leadingSlot` + `leadingGap`) so headlines
  align. Glyphs (`leadingIconRes`/`leading`, badges) are bare tinted `glyphSize` (24dp); media (`leadingMedia`)
  is `mediaSize` (40dp) clipped to `LeadingMediaShape`. Skeletons pass the kind
  (`SkeletonLeading.Glyph|Media`). Pinned by `LeadingSlotTest`. Rows and sheet headers: one title line + one
  subtitle line.
- Overlay/IME hosts: never cast `LocalContext` to `Activity`; use `LocalActivity` and guard null.

## Build

- `make check` (`aideCheck`): typechecks both targets, resolves the desktop Koin graph, runs Android Lint and
  detekt, and enforces the structural checks (code map, dependency direction, portability, schemas).
- `make test` (`aideTest`): every host test task, discovered automatically. Tests live in the module that owns
  their subject; `:app` has none.
- Direct: `./gradlew :app:compileDebugKotlin`, `:desktopApp:compileKotlin`, `:app:installDebug` (downgrade
  allowed).
- **Never hand-write target/SDK/Compose setup in a module build file.** Use the `build-logic` convention
  plugins: `aide.kmp.library`, `aide.kmp.compose`, `aide.android.library`, `aide.android.compose`,
  `aide.android.application`. A new module is one `plugins { id("aide.…") }` line plus dependencies. Versions
  live in `gradle/libs.versions.toml`, no exceptions.
