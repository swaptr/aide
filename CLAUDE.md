# AIDE — engineering conventions

Read before adding UI or navigation. The goal of this file: **reuse the shared bits below instead of
re-deriving them**, so we don't re-grow the duplication we just removed.

## Project stage — no backwards compatibility

Pre-release dev app. minSdk 35 / compileSdk 36. Wiping app data / reinstalling is fine; nuking a build is an
option. So:

- **No data/build migration code.** Room uses `fallbackToDestructiveMigration(dropAllTables = true)`;
  DataStore/prefs evolve via kotlinx-serialization defaults + `enum.name`. Don't write old→new key/schema
  migrations.
- **BUT every schema change bumps `@Database(version)`.** The destructive fallback is not a catch-all: it
  fires on a *missing migration path*, i.e. on a version CHANGE. Edit an entity while the version stays put
  and every existing install throws `IllegalStateException: Room cannot verify the data integrity` at open —
  the exported `N.json` no longer matches the identity hash on disk and nothing wipes it. So: bump the
  version, let KSP write the next `N.json`, and leave the old ones alone. `./gradlew schemaCheck`
  (in `make check` and CI) fails the build if a committed schema file is rewritten instead. The same ledger
  covers persisted documents at `<module>/schemas/documents/<name>/N.json`: bump `PersistedDocument.version`
  and let `PersistedDocumentSchemaTest` write the next file; never edit an old one.
- **No SDK-version branches for SDK < 35.** `Build.VERSION.SDK_INT >= …` checks whose lower branch is
  unreachable at minSdk 35 are dead — call the modern API directly, delete the `else`.
- **No `@Deprecated` shims, legacy paths, or "kept for later" placeholders.** Delete dead code; re-add when
  the feature actually lands.
- **KEEP (these are forward-resilience, not back-compat):** JSON `ignoreUnknownKeys = true`, Room
  `exportSchema = true` + AutoMigration *setup*, network/engine anti-brick error handling, framework-required
  deprecated overrides (e.g. `UtteranceProgressListener.onError`).

## Module map — where code goes

**An import names its module.** Package path mirrors module path, so
`com.sabreware.aide.platform.android.surface.ime.widget.KeyPopup` is in `:platform:android:surface:ime`, and
`com.sabreware.aide.core.designsystem.AppMenu` is in `:core:designsystem`. No package spans two modules:
`docs/code-map.md` (`make map`) is the generated index, and `./gradlew codeMapCheck` — wired into
`make check` — fails the build if one ever does.

Read top-down; every arrow points DOWN and nothing points back up. This sketch is for orientation and shows
the *shape*, not every edge — `docs/module-graph.md` (`make graph`) is generated from the real dependencies
and is the one to trust. `./gradlew dependencyDirectionCheck` (in `make check`) fails the build on an edge
that inverts the layering, which is what replaced the deleted `LayeringRulesTest`.

```
app  /  desktopApp                       the two applications — each also HOLDS its platform-only code
 ├── di                                  the Koin graph — the ONLY module that sees both sides
 │    ├── ui                             the shared, responsive UI: screens, ViewModels, nav graph
 │    └── data                           the shared implementation layer (Room, catalog, search, image,
 │         │                             model registry, prefs, Ktor factory, download stack)
 │         ├── data:llm                  the :aisdk seam — chat session, vendor classes, catalogs, modality engines (:app's LiteRT builds on it)
 │         ├── data:speech               speech assets + install        (both engines build on it)
 │         │    └── data:speech:sherpa   the on-device engine, shared by both apps
 │         ├── data:tools                portable toolsets              (:app's device tools build on it)
 │         └── data:connector            connectors + MCP               (both apps' OAuth halves build on it)
 ├── feature:tasks                       the IME's saved prompts, hosted in Settings by :app
 ├── platform:android                    Android machinery :app AND the IME both need
 ├── platform:android:surface:ime        the keyboard — its own component lifecycle, UI and manifest
 └── core:designsystem                   the shared Compose foundation: components, theme, resources,
      │                                  the Navigator port and the Feature contract
      ├── core:domain                    value types, ports, use cases
      │    └── core:common               dispatchers, prefs, attachments, paths, DI names
      └── (nothing else — navigation and feature folded in here)

aisdk                                    the portable AI SDK: a Kotlin port of Vercel's provider spec.
                                         Depends on NOTHING of ours; :data:llm consumes it.
server                                   a separate Spring Boot service; shares no code (see below)
```

- **`:core:common`** — dispatchers, `PrefKey`/`PreferenceStore`, attachments, `PlatformPaths`, DI qualifier
  names. Depends on nothing of ours.
- **`:core:domain`** — value types, ports, use cases. No Compose, no Android, **no `:data`**. It holds only
  what BOTH apps compile: the Android-only contracts that used to sit here (keyboard prefs, the device-tool
  result types, the voice-turn loop) moved to the code that uses them.
- **`:core:designsystem`** — the shared Compose foundation: components, theme, composeResources, plus the
  `Navigator` port and the `Feature`/`SettingsFeature` contract. Those last two were their own modules; they
  are Compose types, so folding them into `:core:domain` would have dragged Compose into every data module.
- **`:data`** — the shared implementation layer, one module, one package per domain. **Four domains are
  siblings**, and each earns a module by having a consumer that would otherwise compile all of `:data` to
  reach a handful of symbols: `:data:llm` (`:app`'s LiteRT provider), `:data:speech` (both speech engines),
  `:data:tools` (`:app`'s device toolsets), `:data:connector` (both apps' OAuth halves). Nothing else was
  split, because nothing else had a consumer to protect.
- **`:ui`** — the shared, responsive UI. **Depends on the ports, never on `:data`.** commonMain only.
- **`:di`** — the Koin graph; the only module that sees both `:data` and `:ui`.
- **`:platform:android`** — the Android machinery `:app` and the IME BOTH need (permission gate, intent
  broker, launcher intents). It is a module for exactly that reason: two consumers.
- **`:platform:android:surface:ime`** — the keyboard. Its own `InputMethodService` lifecycle, its own View
  layer, its own manifest entries. See **Surfaces** below.
- **`:feature:tasks`** — the IME's saved prompts. Two consumers (the IME's domain, `:app`'s Settings row).
- **An application HOLDS its platform-only code.** `:app` is Android-only and `:desktopApp` is desktop-only,
  so a module boundary around Android-only or desktop-only code gates nothing the application does not
  already gate. The device toolsets, the Android data halves, LiteRT, the assistant surface, the permission
  trampoline and the desktop platform layer are therefore **packages inside their application**, not
  modules. A module is for code that is genuinely shared, or that a second consumer needs.
- **`:aisdk`** — a Kotlin Multiplatform port of the Vercel AI SDK (Apache-2.0): the spec, the utilities,
  every provider and the runtime. It **knows nothing about AIDE** — no `ProviderId`, no `ChatProvider`,
  no Koin, no `:core:*` — which is what lets it target everything Kotlin does and makes AIDE merely its
  first consumer. The arrow is `:data:llm -> :aisdk`, never back. Both apps reach every remote model of
  every modality through it: chat runs on the runtime's loop (`AiSdkChatSession`), and image, speech
  and transcription are thin engines over the runtime's modality wrappers. It exists because Koog dropped
  the thinking `signature` on the Anthropic stream and the `thoughtSignature` on Gemini, and the fix is a
  modelling one: `providerMetadata`, an opaque provider-namespaced map on every content and stream part,
  so the neutral layer never has to understand a vendor payload in order to avoid losing it. Design:
  `aisdk/DESIGN.md`; remaining work: root `TODO.md`.
- **`third_party/`** — vendored **reference material only**: `vercel-ai` and `koog` as git submodules,
  plus the Sherpa AAR. Never compiled, never a dependency, never imported. They are not Gradle
  subprojects, so `codeMapCheck`, `portabilityCheck`, `dependencyDirectionCheck` and detekt — all of which
  scope to `subprojects` holding a build file — cannot see them.
- **`:server`** — a Spring Boot + Postgres service that shares **no code** with the app. It lives in this
  repo so `./gradlew :server:bootRun` works from the root and so a future shared wire model has somewhere
  obvious to go; nothing in the app graph depends on it, and nothing should until it has a real consumer.

The layering rules are the dependency lists, and `./gradlew dependencyDirectionCheck` enforces them: a rank
per module (a module may depend only on a strictly lower rank) plus the handful of bans a rank cannot state
— `:ui` never sees `:data`, `:data` and the device toolsets never inherit the design system,
`:platform:android` stays Compose-free, `:server` shares nothing in either direction, and only `:di` and the
two entry points may see `:ui`. It replaced `LayeringRulesTest`, which counted imports and became
unwritable; reading the declared project dependencies is where the layering actually lives. Adding a
dependency that inverts an arrow is the mistake the graph exists to prevent; if a lower layer seems to need
something above it, invert with a port
(see `BundledAssetReader`, `DeferredBootstraps`, `Context.launchAppAt` for the three worked examples).

### Surfaces — a platform's entry points, not a shared layer

A **surface** is a way the host OS lets the user reach us from *outside* the app: the IME
(`InputMethodService`), the assistant (`VoiceInteractionSession`). They live at
`:platform:android:surface:*` rather than a top-level `:surface:*` because that is what they are — the split
belongs to the platform, not above it. Each target grows its own `:platform:<target>:surface:*`; the shared
layers underneath do not move, and no app's set is expressed anywhere but its own dependency list.

**Every OS has entry points — this was never an Android quirk.** Android has the IME, the assistant, a share
target, a quick-settings tile and a widget; desktop has a tray / menu-bar quick-ask and a global hotkey
overlay; iOS has a keyboard extension, App Intents and a share extension. Only the *set* is per-target.

**A surface owns its own UI.** Its shape is dictated by the OS API it plugs into: the IME draws Android
Views into a window the system sizes, the assistant draws an overlay over whatever app is in front. Neither
is portable, and neither should be talked into reusing a shared screen. What a future platform's keyboard
would share with ours is the *idea* — the state machine below — not the pixels.

**What is expected to become shared is the engine underneath, and it already is.** A surface consumes the
same ports every other consumer does (`SendChatMessageUseCase`, the speech engines, `ToolsetRegistry`); it
contributes its bindings the same way; nothing under `:core`/`:data` knows a surface exists. So when a
second platform gains a surface, the extraction is a *lift* of the standing/enable state machine
(`AideKeyboardManager`, `AideAssistantManager`) into a shared module, not a rewrite. **Do not pre-extract
it.** Both are poll-based standing checks plus an ordered list of OS setup steps, and the steps have nothing
in common across OSes yet — one real second implementation is what tells us which half is the contract.

## Capabilities are contributed, never listed

Chat, speech, image generation, downloads and tools all plug in the same way. Follow it; do not invent a
sixth shape.

**A capability is a port in `:core:domain` + a registry that collects whatever was bound.** The registry is
a named type per capability (`ChatProviderRegistry`, `SpeechProviderRegistry`, `ImageProviderRegistry`,
`AssetSourceRegistry`, `ToolsetRegistry`) rather than a generic, because generics erase and two
`ProviderRegistry<*>` singles would be the same key in the container — the same erasure that forces the
remaining `Map<K, V>` multibindings to carry `named(...)` qualifiers.

```kotlin
// wherever the thing lives — :di for anything portable, :app for an Android-only one:
single { GeminiVendor(get()) }.contributes(Vendor::class)     // a cloud wire: its connections are data
single { LocalProvider(…) } bind ChatProvider::class          // :app only

// once, in :di:
single { ChatProviderRegistry(getAll<ChatProvider>()) }
```

- **A binding IS the registration.** There is no central map to edit, and — the failure this replaced — no
  second per-platform copy of that map to forget. Whether a capability exists on a target is a property of
  that target's module graph.
- **Two definitions of the same Kotlin class collide.** Koin keys a definition by its primary type, so give
  each provider its own class rather than two `single { Foo(…) }` blocks. `:di` `ProviderContributionTest`
  pins the mechanism.
- **Cloud implementations are commonMain** (they are Ktor + an injected engine, so they cost nothing to
  support everywhere). **Platform implementations are platform modules.**

**Never write a second class whose only difference is policy.** Where two targets need different behaviour
inside one implementation, inject the difference as data:

- `SpeechResolutionPolicy` — an ordered fallback ladder whose last entry is the terminal fallback. Android
  passes `[SHERPA, ANDROID_SYSTEM]`, desktop `[SHERPA]`. Gaining an engine extends a list.
- `EngineLoadPolicy` — what happens around a model load (Android trims memory and retries on CPU; a
  network-only target uses `Direct`).
- `MimeTypeResolver` — the four lines of `JvmFileSystemBackend` that actually differ per JVM.

**One download stack.** `DownloadScheduler` (`:core:domain`) takes `(kind, id)`; the registered `AssetSource`
owns the URL and the on-disk layout. A new downloadable capability is one `AssetSource` binding — no
scheduler, no repository port, no stub. Paths below the UI are okio `Path`, one currency, and **every port
member that touches the disk is `suspend`** (`ModelStorage`, `AssetSource`) so no caller has to read the
implementation to be dispatcher-correct.

**Deferred startup work is contributed too.** A `DeferredBootstrap` is a `suspend`, idempotent one-shot that
`AppShell` runs AFTER the first frame; `:di` collects them with `getAll` and each is bound by its own class.
Nothing starts in `Application.onCreate` — that is what made two of them run ahead of the first frame with no
guard against a second call. Every surface that can be a process's first (the app shell, the IME) calls
`DeferredBootstraps.startAll()`; its guard makes the second call free. The ONE exception to "nothing at
startup" is a **startup document** (below).

**A tool declares whether it only reads.** `AideTool.Function.readOnly` defaults to **false**, and only a
read-only tool may be served from the idempotency cache — whose key is scoped to the surface and the turn.
The default is the safety property: it used to be `cacheable = true`, so a second "text Bob" in another chat
returned the first call's success envelope without the handler running.

**A toolset describes itself.** `Toolset` declares its category, display name, blurb, on-demand flag,
permission requirement and any prompt guidance; `ToolBundleFactoryImpl` stamps each tool with its owner's
category and names no toolset. Settings draws its rows from the registry. Adding a tool is its files plus
one binding — do not add a name table, an enum entry or a constructor parameter anywhere.

**Cloud providers are connections, not bindings.** A *vendor* is code — a wire (`VendorId`: OpenAI-compatible,
Anthropic, Gemini, ElevenLabs) contributed as `single { XVendor(get()) }.contributes(Vendor::class)`; it lists
the *services* users recognise (`ServiceDescriptor`: OpenAI, OpenRouter, Groq, Ollama… — one flat catalogue,
each service once, whatever modality it serves). A *connection* is data — one account or endpoint the user
added (`connections` document + key in `SecureStore` at `connection.<id>.api_key`) — and any number may share
a service. **A connection's id IS its runtime `ProviderId`**; cloud model ids are `"<connectionId>:<wireId>"`.
`ConnectionRuntimesImpl` turns connections into providers ONCE (one stateful collector; a runtime lives as
long as its endpoint; removal cancels its scope), and every provider registry is `static + dynamic`:
`get`/`all` are for PAINTING, `await(id)` is for ACTING (it waits out "connections not read yet", which is a
state — `null` — never an empty list). What an endpoint actually serves is decided per connection (an Ollama
endpoint gets no speech or image rows). Adding a service on an existing wire is one `service(...)` line.
Names come from ONE port, `ProviderDirectory` (alias ?: connection name, kind, service, tags).

**Reference: image generation** (`:core:domain/image`, `:data/image`) is the worked example of the whole
pattern end to end — a port, a commonMain engine, two `:di` bindings, and no platform file mentions it.

**A binding you forget is not a compile error.** Koin resolves lazily, so a missing definition passes the
compiler and every test that does not touch it. `DesktopKoinGraphTest` (`make check`) and
`AndroidKoinGraphTest` (`make device-test`) resolve every definition in their application's module list and
fail on the first `NoDefinitionFoundException` — run them after touching DI, and if you add an application
target, give it the same test.

### The ratchet

These are the invariants the refactor bought. Breaking one is the mistake, not a trade-off:

- Every arrow in the module graph points down — `dependencyDirectionCheck` fails the build on an inversion.
- `commonMain`/`commonTest` import no `java.*`/`javax.*`/`android.*` — `portabilityCheck` fails the build,
  so the day a Kotlin/Native target is added the shared layers already compile.
- `:ui` has no platform source set and no `expect`/`actual`. Host capability = a nullable
  `PlatformAffordances` member, absent where the host lacks it.
- `codeMapCheck` fails the build on a split package, on a package that does not mirror its module, and on a
  file whose declared package contradicts its directory. It reads real `package` declarations, not folders.
- A Room schema change bumps `@Database(version)`, a persisted-document change bumps
  `PersistedDocument.version`; `schemaCheck` refuses a rewritten `N.json` in any `schemas/` dir.
- Every host test task in the build runs in CI. `aideCheck` / `aideTest` are the single task lists, in the
  root build — `make`, the CI workflow and the release workflow all call them rather than keeping their own.
- A capability exists on a target **iff** its module is in that target's dependency list — never behind a
  runtime flag, never via an empty-list `expect`.
- No hand-written per-platform provider/toolset/source maps. Registries are contribution-collected.
- An aggregated snapshot stays unresolved until every source has SETTLED, and "not yet" is a state in the
  type (`ModelGateState.Unresolved`), never a seed that reads as an answer. `ModelRegistrySettlementTest`
  fails the build on a snapshot published from an unfinished picture.
- No per-platform class whose only difference is policy. Policy is injected data.
- Cloud implementations are commonMain; platform implementations are platform modules.
- One download stack; one path currency (okio `Path`) below the UI.
- Hand-written docs never restate what `docs/code-map.md` / `docs/module-graph.md` generate.
- A capability a target lacks is bound NOWHERE — never a stub that throws, returns `Result.failure`, or
  renders an empty list. The consumer takes the port as nullable and omits the affordance.
- An affordance is drawn only when something is wired to it (`ReplyActions`' handlers are nullable for
  exactly this reason).
- Release builds are shrunk. `app/proguard-rules.pro` covers what R8 cannot see: JNI classes, generated
  serializers and Room implementations, ServiceLoader engines.
- Static analysis runs on every check (`detekt`, config in `config/detekt/detekt.yml`) and is kept at zero
  findings rather than baselined.

## JVM-shared code

Some implementations need the JVM (`java.io`, a JVM-only artifact) yet are identical on Android and desktop.
They go in **`src/jvmShared/kotlin`**, which `aide.kmp.library` wires into both JVM source sets whenever the
directory exists. It is one source directory handed to two source sets, deliberately NOT an intermediate
`dependsOn` set — Kotlin does not officially support a JVM+Android shared source set.

Reach for it only after checking commonMain: okio and Ktor cover most of what looks JVM-shaped
(`SpeechAssetStorageImpl` and `ModelStorageImpl` both turned out to be plain commonMain once written in
okio). Current residents: the Sherpa engines, `JvmFileSystemBackend`, `SpeechBundleExtractor`.

## Host-agnostic flows (one definition → full screen AND sheet)

A multi-page feature that renders both as NavHost destinations and as a bottom sheet (e.g. opened from chat).
**References: `:ui` `ui/settings/mcp` (connectors) and `:ui` `ui/models`.** Mirror this shape — do not hand-roll a
screen-only or sheet-only variant:

- `XRoute.kt` — `@Serializable sealed interface` of routes. **Carry args as primitive Strings/Ints ONLY** so no
  custom `NavType` is needed and the route is portable across every host (Android/Desktop/iOS). **NOT enums/rich
  types** — the JetBrains KMP nav (`org.jetbrains.androidx.navigation`) lacks the Android reflection-based enum
  `NavType`, so an enum arg crashes at runtime on Desktop/iOS (`could not find any NavType … typeMap was {}`) while
  compiling + working on Android. Pass `enum.name` (String) and resolve the enum inside the page; resolve rich
  domain types (e.g. `ModelSpec`) from an id String in the page.
- `XPages.kt` — one `@Composable fun XHomePage()` / `XDetailPage(id)` per route. Use
  `PageScaffold(title, actions) { contentModifier -> … }` for chrome (it adapts screen-vs-sheet via
  `LocalPagePresentation`) and `navigator().navigate(route)` / `goBack()` for navigation (host-agnostic).
  **No per-host code in a page.**
- `XFlow.kt` — declares the pages **once per host**: `fun NavGraphBuilder.xDestinations()` (uses
  `navigation.page<T>{ }`) for the NavHost, and `@Composable fun XSheet(onDismiss)`
  (`AppDialog(backStack) { page<T>{ } }`, `AppDialogSize.Expandable`) for the sheet. The app graph calls
  `xDestinations()`; chat opens `XSheet`.
- Prefer **intrinsic** mutations over host callbacks: write to the repo and let observers react
  (e.g. `ModelsViewModel.select → registry.recordSelected`; chat switches reactively). No `onSelect` plumbing.
- Leaf modals (confirm / sampler / import / provider picker) stay plain `AppDialog`/`ConfirmDialog` —
  `AppDialog` is a `Dialog`, so they stack fine over a flow sheet. Don't turn every dialog into a route.

## Navigation — one rule per question

Two hosts render the same pages (the app NavHost and a bottom sheet), so navigation splits by *what is
being decided*, not by which screen you are in:

- **How to LEAVE a page is never a parameter.** No screen takes `onClose`/`onBack`. Chrome
  (`AppPage`/`AppScaffold`/`PageScaffold`) leaves `leadingAction` null, which means: a page you navigated
  into gets a back chevron, a flow's first sheet page gets nothing. A top-level screen that wants the drawer
  hamburger passes `leadingAction = HeaderAction.drawer(…)`. A screen that must close
  itself calls `navigator().goBack()`. **The chevron derives from the RENDERED page's own depth, never a
  live back-stack query**: a transition host that knows the depth provides `LocalPageCanGoBack` per page
  (`AppDialog` does — predictive back composes the page underneath BEFORE the pop commits, so any stack
  read there, even frozen at first composition, captures the wrong depth and leaves a stale chevron on the
  root page); without a provider the value freezes at first composition, because a live query re-read
  mid-transition flashes the wrong icon on the outgoing page.
- **Forward navigation is an explicit event** (`onOpenChat: (String) -> Unit`) wired by the host — the
  official Compose guidance, and it keeps `NavHostController` out of every composable. The exception is a
  **multi-host flow**: its own routes go through `navigator().navigate(route)` so the flow is declared once
  and both hosts (NavHost + sheet) drive it — see `:ui` `ui/models`, `:ui` `ui/settings/mcp`.

## Features are self-contained & platform-gated (the `Feature` registry)

A navigable unit is a **`Feature`** (`com.sabreware.aide.core.feature.Feature`), not code hand-wired into central
files. One object declares everything it needs to plug in, and the app iterates the **one `features` list**
for all of it — **never** branch on platform or feature identity:

- `register(builder, nav)` — hosts its NavHost destination(s). Multi-screen feature → a **nested graph**
  (`builder.navigation<XGraph>(startDestination = XHomeRoute) { composable<…>{ } }`): outsiders navigate to
  the graph, its sub-routes stay encapsulated. (Reference: `:feature:tasks` `TasksFeature.kt`.)
- `koinModule` — its DI: ViewModels, repos, **even its own database**. Most features add none.
- `handleDeepLink(dest, nav)` — claim a deep-link string (returns true if consumed). `AppShell` tries
  `features` first, then shared fallbacks. A feature a platform lacks simply never claims its deep-link.
- `SettingsFeature : Feature` (`:core:feature`, `com.sabreware.aide.core.feature`) adds `section`/`order`/`row`/
  `route` for a Settings menu row. `SettingsScreen` renders `FeatureRegistry.settingsFeatures` by section.

Consumers, all uniform iterations over the injected `FeatureRegistry`: **menu** (`SettingsScreen`), **nav**
(`AppNavGraph.appDestinations`: `features.features.forEach { it.register(this, nav) }`), **DI**
(`featureModules(...)` installs each feature's own module), **deep-links** (`AppShell`). The core shell
destinations (chat/chats/search/custom-instruction/settings) stay in `appDestinations`; everything
pluggable/gate-able is a `Feature`.

### Targets and hosts — five platforms, three targets

The plan is Android, Linux, macOS, Windows and iOS. That is **three Kotlin targets**, and confusing the two
counts is how the wrong mechanism gets picked:

| Kotlin target | Hosts it serves | What varies per host |
|---|---|---|
| `android` | Android | — one host |
| `jvm("desktop")` | **Linux, macOS, Windows** | app data dir, secret vault, tray vs menu bar, autostart |
| `ios*` | iOS, iPadOS | — one host |

Adding macOS and Windows adds **no source set and no module**: the same JVM binary already runs there.
Adding iOS adds a target every shared module must satisfy. So gating happens at two moments, and the rule is
that they share one grammar:

- **Compile-time gate — the module graph.** What a *target* can possibly do. A module absent from a target's
  dependency list is never compiled there. This is the gating rule below.
- **Startup gate — the binding.** What *this host*, on *this machine*, with *this configuration* can do.
  Detect once, then choose which implementation to install.

Both say "absent" identically: nothing is bound, the port resolves to null, the affordance is not drawn.
Neither is ever a stub, and neither is ever an `if (isMac)` buried inside an implementation — per-host
difference is **injected data or a chosen binding**, exactly like `SpeechResolutionPolicy` and
`EngineLoadPolicy`.

**Where desktop state lives is a host decision, resolved once.** `DesktopAppDirs` (`:desktopApp`
`desktop/storage/`) maps `DesktopHost` → the directories that host actually uses — XDG on Linux,
`Application Support` on macOS, `%APPDATA%` on Windows — and `desktopModules(dirs)` takes it, so the app
root decides and the graph is told. Nothing else may ask where the data dir is: `desktopDatabaseBuilder`
takes the directory as a parameter, and `PlatformPaths` / `SecureStore` / the connector cache take
`DesktopAppDirs`. This replaced an `aideAppDataDir()` that hard-coded `~/.aide` and lived inside
`AideDatabase.desktop.kt`, which three unrelated desktop classes imported from `:data` — the layering
inverted, and the answer wrong on two of the three hosts.

**`commonMain` must stay reachable from every target.** Both targets are JVM today, so a stray
`java.util.UUID` in shared code compiles and ships and is invisible until Kotlin/Native arrives.
`./gradlew portabilityCheck` (in `make check`) fails the build on a `java.*`/`javax.*`/`android.*` import
inside a `commonMain`/`commonTest` source set. JVM-only code goes to `src/jvmShared` or a platform module.

### The gating rule — an application composes what it can do

**Two applications, and each one HOLDS the code only it can run.** `:app` is Android-only and `:desktopApp`
is desktop-only, so a module boundary drawn around single-platform code gates nothing the application does
not already gate. That was the mistake this replaced: nine Android-only modules whose isolation bought
nothing, because the only thing that ever depended on them was the Android app.

A module is for one of exactly three reasons:

1. **Both applications compile it** — `:ui`, `:data`, `:core:*`, `:di`.
2. **A second consumer needs it** — `:data:llm`, `:data:speech`, `:data:tools`, `:data:connector`,
   `:platform:android`, `:feature:tasks`. Each would otherwise force that consumer to compile a whole layer
   to reach a few symbols.
3. **It is an OS entry point with its own component lifecycle and manifest** — `:platform:android:surface:ime`.

Everything else is a package inside the application that owns it. Each app still composes its own graph:

```kotlin
val appModules     = commonModules + androidPlatformModule + featureModules(commonFeatures + androidFeatures)
val desktopModules = commonModules + desktopPlatformModule + featureModules(commonFeatures)
```

**Do NOT reintroduce `expect val platformFeatures`.** `expect`/`actual` expresses variation, so a platform
with nothing to contribute is forced to declare `emptyList()` — absence modelled as a declaration, and the
same pressure that produces stubs. Composition says it by omission.

**Split when a second consumer appears, not before.** The rule reads forward, not backward: the day desktop
grows a keyboard, the IME's standing/enable machine gets extracted; the day a toolset is needed on both, it
moves to `:data:tools`. Until then, one app owning its own code is the honest shape.

### A feature owns its WHOLE subtree, down to storage

Gating the feature must gate everything it owns — screens, VMs, domain, nested routes, **and its persistence**.
An android-only feature with Room tables gets its **own android-only DB**, not entries in the shared
`AideDatabase` (else desktop still compiles the tables). Reference: `:feature:tasks`
`:feature:tasks` `data/TaskDatabase.kt` — **classic android Room** (`Room.databaseBuilder(ctx, X::class.java, name)`, no
KMP `@ConstructedBy`, which is impossible in an android-only module), with its schema exported to the
feature's own `schemas/` dir. The DB builder single lives in `:app` (needs `androidContext()`,
koin-android-only); the DAOs/repo/VMs live in the feature's `koinModule`, resolving `get<XDatabase>()`
cross-module. Its route types are **standalone `@Serializable`** (like `ModelRoute`/`ConnectorRoute`,
`TasksGraph`), **not** members of the common `navigation.Route`.

Inline *preference* rows (a local value + a sheet, e.g. Color theme / Sign-in redirect) are **not** features —
they stay declared in `SettingsScreen`, slotted into their section's list.

## Async state — resolve at the component, render with the kit

**The data-loading pattern — one recipe, every surface.** A first frame paints from what is already in
memory; it never draws a default, an empty list or a skeleton for data the process already has.

| Source | Data layer | ViewModel seed | Warm it |
|---|---|---|---|
| Expensive snapshot (disk, decrypt, N-flow fold) | `flow.snapshotCache(appScope, name)` (`:core:domain` `cache/`) | `stateInUiCached(scope)` / `stateInUiSeeded(scope, cache.value)` | add to `:di` `CacheWarmup` if a first open is one tap away |
| Preferences | `PreferenceStore` (one snapshot per commit) | `prefs.selectState(scope) { p -> State(p[KeyA], p[KeyB]) }` — ONE builder seeds from `prefs.current` and derives every update; with non-pref inputs, `combine(prefs.select(::build), …)` seeded by `build(prefs.current)` | automatic: the store is `createdAtStart`, read during `startKoin` |
| First-frame state (model choice, theme) | startup document / `prefs.current` | theme + fonts: `AppAppearance(prefs) { … }` at every root (app, desktop, assistant) | each host holds its first draw on it: `MainActivity` pre-draw, desktop `main`, the IME's `onCreateInputView` (all capped) |
| Secrets (decrypted) | `SecureStore.observe` dedupes on ciphertext; multi-key changes go through `write(map)` (one commit) | cached as a `snapshotCache` when >1 consumer (`WebSearchCredentialsRepository.configured`) | — |
| "Not read yet" that would read as an answer | — | `UiState.Loading`, never a null/empty seed | — |

- `prefs.current`/`peek`, a cache's `.value` and a startup document's `state.value` are for PAINTING. Never
  the read half of a read-modify-write — and never a decision: a `.value` of an uncollected `WhileSubscribed`
  state is its seed (the imported-model delete read one and took the wrong branch). Read the store.
- A rule a seed and a flow both need is ONE function both call (`effectiveModelId`, `keyboardAppearance()`).
- Every surface that can be a process's first (app shell, IME, assistant) starts `DeferredBootstraps` AFTER
  its first show, never in `onCreate`.
- A scheduler/WorkManager status never gates a snapshot: `onStart { emit(Idle) }`; disk decides Completed.
- A list whose length is known before its content draws that many skeleton rows (no height jump mid-slide).
- `StatePane` reveals its loading visual only after `LoadingRevealDelayMs`; fast loads never flash.
- Cold costs are logged, not guessed: `snapshotCache` logs `first snapshot in N ms`, `CacheWarmup` logs
  `warm in N ms`. Read those before adding a warm-up.

**Startup mounts the shell and nothing else.** Only structural shell state (sidebar open, window class) may
load at the app root / `AppNav`. Everything a surface needs (chats list, active model, connectors, catalogs)
resolves **when that component mounts**, atomically, with its own loading visual. Never add a startup gate
that blocks the first frame; heavier deferred bootstraps are registered in `:di`'s `DeferredBootstraps`
(fired by `AppShell` AFTER the first frame — keep each `start()` idempotent). Flows whose emissions do I/O (disk stats, WorkManager)
get `flowOn(Dispatchers.Default)` at the repo, never rely on the collector's dispatcher.

One refinement for animation-facing surfaces: data an animated panel needs at OPEN time is collected **at
the app root, once for the session**, so the panel is never composing or querying mid-slide. The chats list
is the worked example — `AppViewModel.chats` is `stateInUi(viewModelScope)` and `AppShell` collects it, so
the drawer sheet (which Material keeps composed offscreen) already holds current data and opening it is
pure translation.

The kit (`:core:designsystem` `state/` + `:core:designsystem`), one look and one code shape for every consumer:

- **Model** → `UiState<T>` (Loading / Ready / Failed) — not a nullable, not a bare boolean. Produce with
  `flow.stateInUi(viewModelScope)`; `Ready(emptyList())` IS the empty state.
- **A snapshot that is expensive to rebuild is cached at the repository, once per app.** A repo whose flow
  stats the disk, decrypts a store, or folds an N-flow combine per emission exposes
  `StateFlow<T?> = flow.stateIn(appScope, WhileSubscribed(5s), null)` (null = never resolved this session)
  instead of minting a cold flow per call; the ViewModel consumes it with `stateInUiCached(viewModelScope)`,
  which seeds Ready synchronously from the cache — a re-opened surface composes with content on the first
  frame while the restarted upstream refreshes behind it (stale-while-revalidate). A flow *derived* from
  the cache (a refresh trigger `flatMapLatest`-ing over it) seeds via `stateInUiSeeded(scope, cache.value)`.
  Only the session's first-ever open shows the skeleton — unless its caches are listed in `:di`'s
  `CacheWarmup`, which resolves them once after the first frame.
  References: `ModelRegistryRepository.models`,
  `SpeechAssetRepository.assets`, `McpServerRepository.servers`, `ChatRepository.chats`.
  A decision made right after a mutation (pick-a-fallback after delete) reads a FRESH query
  (`chatsSnapshot()`), never the replay cache. This refines, not replaces,
  resolve-at-the-component: nothing runs ahead of the first frame, and a cache warms on first use or in a
  deferred bootstrap.
  **Never read a replay cache as the read half of a read-modify-write** — mutate via `PreferenceStore.update`
  or a direct store read (`McpServerRepositoryImpl.mutate`, `PreferenceStore.get` are the worked examples).
- **An aggregate is unresolved until EVERY input has answered, and "answered" is not "emitted".**
  A snapshot merged from N sources (the model registry over its providers) must publish `null` while any
  source is still working, because the instant it goes non-null every consumer commits — the chat header
  names a model or offers "Set up a model to begin", the IME swaps its bar, the assistant draws a setup
  chip. So each source needs a settled/unsettled predicate of its own, and a source that is *mid-first-fetch
  with nothing cached* is UNSETTLED however eagerly it emits: it knows exactly as much as one that has not
  started. `RemoteCatalogState.isSettled` is the worked example (`Refreshing(previous = emptyList())` →
  false; `Failed` → **true**, because a dead network must end the skeleton, not extend it). Pinned by
  `ModelRegistrySettlementTest`.
- **A "not yet" state is a member of the type, never the absence of one.** `ModelGateState.Unresolved`
  and `ModelResolution.Unresolved` exist because a boolean or a `NoModel` seed cannot say "still looking",
  so every surface read the seed as an answer. A call to action belongs to a SETTLED negative and to
  nothing else.
- **Rendering a name is not acting on it — so record the name with the choice.** Where the authoritative
  answer is slow (providers, crypto, disk) and the surface only needs a label, store the label beside the
  user's choice and read it back on the first frame: `ModelSelection.cards` + `ModelResolution.Cached`, which
  paints the model pill from one small file while `hasModel` stays false until the chosen model resolves.
  The label may be stale (the model can be gone), which is why it may only paint.
- **Startup documents** — the one thing read at process start. A `PersistedDocument` (`:core:common`
  `persist/`) is a `@Serializable` class — its schema, versioned in `schemas/documents/<name>/N.json` —
  held by a `DocumentStore` (typed DataStore over okio: atomic writes, one writer, corruption policy by
  `Durability`). Binding its store `createdAtStart` starts the read inside `startKoin`, so it is in memory
  before any surface (the IME included) draws; `MainActivity` holds the first draw on it (capped) and
  desktop `main` awaits it. Reserve this for small, local, crypto-free state the FIRST frame needs —
  today only `ModelDocuments.Selection`. Everything else still resolves at the component.
- **Resolve the one thing being acted on, not the aggregate.** `ModelGateState` and
  `ModelRegistryRepository.resolve(id)` consult only the chosen model's source — the disk for an on-device
  model, its own provider for a remote one — so a local pick never waits on (or falls back to) a cloud
  catalog. "Nothing chosen" is settled the moment the choice document is read (nothing picks on the user's
  behalf); a chosen model that is unusable is `ModelGateState.Missing(card)`, named to the user, never
  silently replaced. "The chosen model" has ONE definition, `ModelSelection.chosenFor(modality)` — chat,
  keyboard, assistant and voice all ask it. The only substitution is the user's own `ModelFallback` setting
  (`Ready.reroutedFrom`): it never rewrites the choice (a rerouted send is not recorded) and every surface
  names the stand-in for as long as it lasts.
- **A default that reads as an answer must not stand in for an unreadable file.** A document whose default
  means "the user chose nothing" sets `defaultWhileUnreadable = false` and stays `DocState.Loading` until a
  read succeeds; one whose default is a harmless starting point (no overrides, nothing imported) may use it.
- **Full-pane surfaces** → `StatePane(state, loading = { … }) { value -> … }` — the one crossfade motion
  (`StateMotionMs`) between loading/failed/ready. Defaults: centered spinner + placeholder-shaped failure.
  The loading visual appears only after `LoadingRevealDelayMs`, so a fast load never flashes a skeleton.
- **List/row/pill skeletons** → `SkeletonListRow` / `SkeletonTextBar` / `SkeletonCapsule` (`SkeletonShapes.kt`)
  over ONE `rememberSkeletonShimmer()` per surface. Don't hand-roll shimmer boxes; rows read
  `LocalAppListItemStyle` so lists don't jump when data lands. References: drawer chats (`AppShell`),
  connector browse (`ConnectorCatalogContent`), chat model pill (`ModelSelectorPill`).
- **Inline notices** → `AppNotice(text, severity = Info|Warning|Error)`; transient (snackbar-like) →
  `AutoDismissNotice(text, onDismiss)`. One severity scale: Info = secondaryContainer, Warning =
  tertiaryContainer (WriteConfirmGate WARN accent), Error = errorContainer. Per-field validation stays on
  `AppTextField` errorText; app-wide outage/update notices in the global `AppBanner`.

## Collections — search, filter, select and act on anything

Every list a user searches, filters, multi-selects or acts on (models, connections, chats, connectors) runs on
ONE framework. Do not hand-roll a filter menu, a selection mode or a per-row action builder again.

- **Engine** — `:core:domain` `browse/`: a `BrowseSpec<T>` (key, searchable text, `Facet`s, order, grouping)
  and `spec.run(items, query)`: relevance-ranked text search (every token must match; the name ranks
  highest), facets with faceted-search counts (OR within a facet, AND across), exclusive facets with a default
  (a VIEW such as chats' All/Starred/Archived), sections. Pure — runs over a local snapshot or the page a
  remote search returned.
- **Kit** — `:core:designsystem` `browse/`: `rememberBrowseState` + `rememberBrowseResult`;
  **`collectionBar(title, browse, placeholder, facets, actions, select)`** — the ONE header of every collection
  page and sheet: Search, the page's own actions, Select; past the button budget the tail folds into More.
  Tapping Search turns the band into the `SearchField` (`search/`) in place — focused, keyboard up, never a
  navigation — and Filter (only when there are facets) takes the trailing slot: **filtering exists only inside
  search**, on every collection, page or sheet. Back leaves search and drops the text AND the filters; a page
  opened pre-filtered starts searching. The header swaps its band by ARGUMENT (`AppHeader(titleContent = …)`),
  never by branching into two `AppHeader` calls — two calls are two composables, so the slots snap instead of
  crossfading (the bug sheets had while pages were smooth). Search and Filter are
  never controls parked in the list, so the list stays plain. Wrap it in `collectionHeader(...)` for selection
  mode (Done, "N selected", select-all, bulk actions) and hand the result's `title` / `leadingAction` /
  `trailingActions` / `titleContent` to `PageScaffold`, `AppScaffold` or `AppDialog`. A tabbed page keeps ONE
  `BrowseState` and changes `actions` with the tab (add-model: Import on On-device, the connection's actions
  on its tab). A search that asks something slow (an API, a directory) feeds the same text to
  `search/rememberSearchResults(text) { … }` and runs the spec over what came back (connector catalog).
  Also `BrowseFilterSheet` (hosted by the bar; no pills in the body — re-tap a chosen option to drop it),
  `BrowseNoMatches`; `CollectionAction<T>` (one definition for a row's long-press sheet, a header menu AND a
  selection; `ActionScope`, `toggleAction`, built-in `Confirmation`, `leavesSheet`) run through
  `rememberActionRunner`. A page's own actions (Add, Connections, Tags, Refresh) are tiles above its list,
  `AppMenu(layout = AppMenuLayout.actions())` — one horizontal strip, never behind More; one item's actions in its
  sheet are the same strip, `ActionRail(runner, item)`. The header keeps only Search, Select (and Pin); Filter appears while searching.
- **Choosing** — every picker (filters, tags, text size, font, fallback) is plain `AppMenu` rows with
  `AppMenuEntry.selected`: a chosen row is brightened, never ticked or badged, so choosing never shifts text.
  Re-tapping a chosen option un-chooses it where that makes sense. Never add a picker component.
- **Rounded or flat, never both** — a list inside a page or sheet is rounded segmented sections: `AppMenu` when
  bounded, `LazyListScope.appMenuSection(items, key, title) { row }` when lazy (each section one block, rows
  are `AppListItem`s). Never full-width flat rows next to a rounded block.
- **Labels** — `:core:domain` `label/`: the user's name, tags and pin for ANY subject (`LabelSubject`
  `model:` / `connection:` / `chat:` / `connector:`), one `labels` document with a first-class tag vocabulary.
  `:ui` `labels/`: `LabelsViewModel`, `rememberLabelEditor` (rename / tag sheets), `labelActions(...)`
  (Rename, Tags, Pin as `CollectionAction`s), `TagsPage`, and `Labels.tagFacet` for filtering. A new labelled
  thing mints a subject kind; it never grows a `customName` column. Model aliases are applied where the
  registry builds rows (`ChatModelSpec.named`), so the chat pill and the keyboard paint the alias too.

## Reuse these shared components (mostly `:core:designsystem`)

- **Any grouped list / menu / tile block** → **`AppMenu(items, layout)`** — ONE component, ONE data model
  (`AppMenuEntry`), ONE look (rounded only on the block's OUTER corners, square inside, a transparent
  seam — never a drawn divider). Shape is an **argument**, not another component: `AppMenuLayout.Rows`
  (default) · `Rail(tileWidth, tileHeight)` · `Grid(columns, tileHeight)`; the layout also picks the cell
  (rows vs centered tiles), and per-layout knobs live on the layout, never on `AppMenu`'s signature.
  **There is no content slot** — a row needing more than title/subtitle/toggle describes it as DATA
  (`AppMenuEntry.action` = an icon button, optionally badged, left of the switch). The engine, cells and
  corner rules are `internal`: you cannot hand-roll a variant. Sibling, not an exception: `AppMenuCard`
  (same card, arbitrary content — expandable bodies, license text, empty states). Unbounded/dynamic lists
  are the one exception: `AppListItem` inside a `LazyColumn`. Section label → `AppMenuSectionTitle`.
- **A name that does not fit** → `MarqueeText` (or `Modifier.marquee()` on any one-line `Text`) — the ONLY
  way a cut-off name is shown; never a bare `maxLines`/`Ellipsis` title or subtitle. At rest it is an
  ellipsis and costs nothing. Each text runs its OWN clock: fully in view for 1.2s (+ a per-text stagger),
  then Museo's endless loop — name and copy slide by width + gap, rest 3s as the ellipsis, repeat. Leaving
  view (or a covering modal via `CoverMarquees`) kills that one loop — scrolling never pauses rows still in view; coming back
  restarts its clock. Per frame it is one draw-phase float, clipped to the slot. Hosts: `MarqueeHost`
  (`AppShell` root; `AppDialog` its own). A text with more lines never walks.
- **Empty states** → `Placeholder(iconRes?, title?, subtitle?, actions)`. It is **top-anchored** (looks right
  full-screen and stays visible at a sheet's peek detent). Don't build one-off centered empties; don't pass
  extra `.padding()` — it owns its own. An empty state that needs richer content than icon/title/subtitle/
  actions (the chat greeting's hero line + animated model prompt) goes in **`PlaceholderLayout { }`** — the
  frame `Placeholder` itself draws in — so every empty state sits in the same place. Position is
  `LocalPlaceholderStyle` (default: the shared top inset); only the chat home provides
  `PlaceholderStyle.Hero` (≥ 2× the inset, or 25% down the pane) — never pad a placeholder by hand.
- **Destructive confirm** → `DeleteConfirmDialog(title, message, onConfirm, onDismiss)`. Generic confirm →
  `ConfirmDialog`. Single-line input → `TextInputDialog`.
- **Bottom sheets / dialogs** → `AppDialog` (custom `AnchoredDraggable`, **not** `ModalBottomSheet`).
  Multi-page → `AppDialog(backStack) { page<T>{ } }` + `rememberNavDialogBackStack`; every page is a
  `PageScaffold`. **Sheet vs dialog is never decided at a call site**: each application provides a
  `ModalPolicy` (`LocalModalPolicy` — Android the default `Adaptive`, desktop `Dialog`) and `AppShell`
  resolves it against the live window into `LocalModalPresentation`. `Adaptive` = centered dialog only when
  the window is ≥ 600dp wide AND ≥ 480dp tall (Material's medium bounds), so a phone in landscape stays a
  sheet. Never branch on device type or width alone. Pinned by `ModalPresentationTest`.
- **Every header is ONE component, `AppHeader`**: the screen top bar (`AppScaffold`/`AppPage`), a flow page
  (`PageScaffold`) and every sheet/dialog (`AppDialog`) draw it. Its buttons are DATA, `HeaderAction` (icon,
  label, enabled, destructive, `onClick` XOR a `HeaderMenu` action sheet), in two slots that are nullable and
  null by default: `leadingAction: HeaderAction?`, `trailingActions: List<HeaderAction>?`. An empty slot draws
  nothing and reserves no width; a slot crossfades when its labels change. Never hand-roll an `IconButton` in a
  header or a Box + open-state for a header menu. Spacing is ONE spec, `HeaderBandStyle`
  (`LocalHeaderBandStyle`): each side of the band sits at the menu text inset (`AppMenuTextInset`) or clear of
  an occupied slot, whichever is wider; `HeaderPlacement.Modal` centers the band, `.Page` starts it. A walking
  name clips to the band. Custom band content (the chat model picker) uses the `titleContent` overload.
  Pinned by `HeaderBandTest`.
- **Scrolling is inferred — never add a `verticalScroll` to a body** (`ScrollOwner`). Every host scrolls its
  body under a pinned header by default: a leaf `AppDialog` always, `PageScaffold` unless told otherwise,
  `AppPage` always, `PlaceholderLayout` whenever its height is bounded. The ONLY opt-out is a body that is
  itself a scroller (`LazyColumn`, a pager of lists): `PageScaffold(scroll = ScrollOwner.Content)`, which
  hands it a bounded fill-height slot. A second same-axis scroller inside a scrolled body throws, and a
  vertical `weight()` inside one collapses to 0 — so neither goes in a default body. Raw `AppScaffold` is
  for fill-height list screens only. Pinned by `ScrollContractTest` (`:core:designsystem` desktopTest).
- **Models, any kind** → a `LibraryItem` list (`:ui` `ui/models/Library.kt`: chat, voice, cloud speech/image,
  built-in engines as ONE row type) rendered by `LibraryRow` in `AvailabilityGrouping` (Pinned · In use ·
  Installed · Built in · Ready to use · Downloading · Available to download), acted on through ONE
  `libraryActions(...)` list, and opened — never navigated to — in the ONE model sheet (`rememberModelSheets` +
  `ModelSheetHost`; body `ModelDetails`: actions rail, download progress, tags, then the kind's facts). A page
  that lists models adds nothing else. Byte sizes → `humanBytes()`.
- **Progress bars** → `AppLinearProgress` — never raw `LinearProgressIndicator` (its M3 default draws a
  stray "stop indicator" dot past the fill and a gap before it; `AppLinearProgress` turns both off once).
- **Preferences** → declare a `PrefKey` (the *type* lives in `:core:common` `prefs/`; the named key objects
  live next to the feature that owns them, in `:core:domain` or `:ui` — ONE line each. See
  `SearchPrefs`, `ToolPrefs`, `ModelPrefs`, `SpeechPrefs`, `KeyboardPrefs`, `ConnectorPrefs`, `ShellKeys`)
  and read via `PreferenceStore`. There is no preferences repository any more — `UserPreferencesRepository`
  was deleted once every key moved (ARCHITECTURE.md §18); do not reintroduce a god interface for prefs.
  Behaviour over a key (a toggle, a decode) is an extension on `PreferenceStore` beside the key. A pref
  whose absence is meaningful uses `nullableStringKey`/`nullableEnumKey` (writing null removes it). View
  state (sidebar/pane/last-x) = `Tier.UiState` + `ui.` prefix; user intent = `Tier.Settings`. Tests use
  `FakePreferenceStore(Key to value)`.
- **Attachments / gated inputs** → the part→capability rule lives ONCE in `:core:domain`
  (`model/InputModality.kt`).
  A new gated part type extends that mapping (+ each wire codec); never hand-branch on part types at a gate.
  Recording UI → `VoiceRecordButton`/`VoiceBars` (`:core:designsystem`) — don't build new pulse/blink affordances.

## UI conventions

- **`:ui` is commonMain and stays that way.** It has no `androidMain`, no `desktopMain`, and no
  `expect`/`actual` — every one it had was deleted, because `expect` forces a NEW target to supply an
  `actual` before anything compiles, so a target with nothing to offer is compelled to write an empty one.
  That is not theoretical: the desktop actuals for the folder and model-file pickers were literally `= {}`,
  so "Add folder" was a drawn button that did nothing on a target whose filesystem toolset is fully wired.
- **A host capability the shared UI needs is a nullable member of `PlatformAffordances`**
  (`:ui` `ui/platform/`), provided once per application through `LocalPlatformAffordances`. Null means
  "this host cannot do that", and the screen omits the affordance — Android provides all seven, desktop
  provides five (no camera, no contacts). A new target provides what it has and grows into the rest.
  Its `@Composable fun rememberLauncher(...)` shape exists because Android's launchers are
  `rememberLauncherForActivityResult`, which must run in the composition that consumes the result.
  **A portable library is not a platform fork**: FileKit's file dialog is called straight from commonMain.
- **Theme tokens only** — `MaterialTheme.colorScheme`, `AppSpacing`, app fonts. Never hardcode `Color` or
  raw dp soup.
- Bundle per-call layout knobs into a CompositionLocal style, not loose params on a shared component.
- User-typed text = AppSans; assistant reply = AppSerif.
- **Menus are lean**: row titles, tile labels and section labels are regular weight (theme `titleMedium` /
  `titleSmall`), subtitles always the gray `onSurfaceVariant` — a chosen row is told by its brighter fill alone.
  Only headings (`titleLarge`) are bold. A rail tile is as wide as its own label needs (measured, like CSS
  `max-content`), clamped between square and `RailTileMaxWidth`; only a label past that walks.
- **Copy is direct and descriptive.** Titles are a few words; a subtitle says what the option DOES in one
  plain sentence. No arrows or symbols as grammar (`→`, `->`), no "tap X → Y" step chains, no hedging.
- **Every row's leading visual sits in ONE fixed-width slot, sized by KIND** (`AppListItemStyle`): the slot
  (`leadingSlot` + `leadingGap`) is reserved whatever leads, so every headline starts at the same x; a glyph
  (`leadingIconRes`/`leading`, status badges) is a bare tinted `glyphSize` (24dp) icon, media (`leadingMedia`:
  logos, avatars) is `mediaSize` (40dp) clipped to `LeadingMediaShape`. The two sizes are independent fixed dp —
  a glyph never takes the media size, so glyph rows stay menu-compact (pinned by `LeadingSlotTest`). Skeletons
  pass the same kind: `SkeletonListRow(leading = SkeletonLeading.Glyph|Media)`. Rows and sheet headers are one
  line of title + one line of subtitle.
- Overlay/IME hosts: never cast `LocalContext` → `Activity` (use `LocalActivity`, guard null) — the same UI
  runs in `MainActivity` and the voice-overlay window.

## Build

`make check` typechecks both targets, resolves the desktop Koin graph, runs Android Lint and detekt, and
enforces the structural invariants (package↔module mirroring, dependency direction, shared-source
portability, Room + persisted-document schema versions);
`make test` runs every host test task in the build — discovered, not listed, so a module's first test is
picked up automatically. Both are single Gradle aggregates (`aideCheck` / `aideTest`) that the Makefile, the
CI workflow and the release workflow all call, so the three cannot drift. Directly: `./gradlew
:app:compileDebugKotlin` for Android,
`:desktopApp:compileKotlin` for desktop, `:app:installDebug` to deploy (downgrade allowed in dev). `:app`
itself has no unit tests — every test lives in the module that owns its subject.

**Never hand-write target/SDK/Compose setup in a module build file.** `build-logic` holds the convention
plugins — `aide.kmp.library` (android + desktop), `aide.kmp.compose` (+ Compose Multiplatform),
`aide.android.library` / `aide.android.compose` (Android only), `aide.android.application` (`:app`). A new
module is one `plugins { id("aide.…") }`
line plus its dependencies; SDK levels, JVM target, the Compose baseline and the namespace (derived from the
Gradle path) come from the plugin. Versions live in `gradle/libs.versions.toml`, no exceptions.
