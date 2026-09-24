# Configuration, UI state & cache — design

Status: **the migration is complete** — every key is a typed `PrefKey` declared beside its feature
(`SearchPrefs`, `ToolPrefs`, `ModelPrefs`, `SpeechPrefs`, `KeyboardPrefs`, `ConnectorPrefs`,
`AppearanceKeys`/`FontKeys`/`ShellKeys`/`WindowKeys`, …), and `UserPreferencesRepository`, its impl and
its fake are **deleted** (see DEFERRED.md). The one open item is §5's phase 5: formalize the cache tier
behind a `CacheStore`. The rest of this document is the design rationale and the tier rules, which remain
in force; it covers where a key/value lives, how it is declared, and how it is read — on every platform we
ship or will ship (Android, desktop/JVM, iOS, Windows).

---

## 1. The problem (as it stood before the migration)

Two things were broken, and they pulled in opposite directions.

**a) Adding a setting cost six edits.** `UserPreferencesRepository` was one interface with **65 members**
(one `Flow` + one `suspend fun` per setting); a new key meant touching the interface, its implementation
and the shared fake in six places. Tolerable at 30 settings, absurd at 300 — and it centralized what
CLAUDE.md deliberately decentralizes everywhere else: a feature owns its whole subtree, but its *keys*
lived in a shared god-interface every platform compiled.

**b) There was nowhere to put view state at all.** The desktop sidebar was
`rememberSaveable { mutableStateOf(false) }`, which survives a config change but **not** process death —
and on desktop there is no saved-state host across launches at all, so the sidebar was closed on every
single start. The same gap applied to: last open chat, window size/position, panel widths, scroll anchors,
"did I dismiss that banner". The only place to put those was the settings store, which is the wrong place
(see below); phase 2 (`ui.sidebar_open`, `ui.last_chat_id`, `ui.window_bounds`) closed the gap.

The instinct — "just add `sidebarOpenFlow` to `UserPreferencesRepository`" — is what every mature app has
learned not to do.

---

## 2. What other applications actually do

Four independent ecosystems converged on the **same split**, which is the strongest signal available:

| Product | User settings | Per-device view state | Cache |
|---|---|---|---|
| **VS Code** | `settings.json` — public, user-editable, syncs | `Memento` (`globalState` / `workspaceState`), private to the extension, backed by SQLite | `globalStorageUri` files |
| **IntelliJ / JetBrains** | `@Storage` components, `RoamingType.DEFAULT` — exportable, Settings-Repository-syncable | `workspace.xml` + `RoamingType.DISABLED` (also `PER_OS`) — explicitly *not* exported | index/system dir |
| **Chrome extensions** | `storage.sync` — ~100 KB, 8 KB/item, syncs across profiles | `storage.local` — 10 MB, device-local | `storage.session` (in-memory) |
| **Zed / Obsidian** | JSON settings file | SQLite workspace DB / `workspace.json` | separate cache dir |

The rules they all encode:

1. **Settings are user intent.** Semantic, few, meaningful to a human, worth syncing/exporting, worth
   documenting. "Dark theme." "Chat font = Serif."
2. **View state is a side effect of using the app.** Positional, numerous, per-device, meaningless to a
   human, must **never** sync (a 27" desktop's sidebar width has no business on a phone), and losing it is
   an annoyance rather than a bug.
3. **Cache is derived.** Refetchable, evictable, TTL'd, never authoritative. Deleting it must be safe.

VS Code states the rule bluntly: settings are *public and user-configurable*, Memento is *private runtime
state*; "only use settings for what they are intended for." JetBrains enforces it in the type system — putting
state in `workspace.xml` disables roaming for that component.

Notably, **all three use the same storage engine for tiers 1 and 2** and separate them by *scope and policy*,
not by technology. We should do the same.

---

## 3. The design

### 3.1 Three tiers

```
┌─ Settings ──────────────┐  user intent · semantic · roamable · low write rate
│  Tier.Settings          │  → theme, font scale/style, tool permissions, provider prefs
├─ UI state ──────────────┤  view state · positional · device-local · high write rate
│  Tier.UiState  ("ui.*") │  → sidebar open, window bounds, last chat, panel widths
│  …both in user_prefs    │
├─ Cache ─────────────────┤  derived · evictable · TTL
│  cacheDir/… (files/Room)│  → connector directory JSON, model catalog, icons
└─────────────────────────┘
```

Tiers 1 and 2 share **one** Preferences DataStore — the existing `user_prefs` instance behind the existing
`USER_PREFS` Koin qualifier (`datastore = 1.2.1`, `datastore-preferences-core` in `:core:common`, already
multiplatform). No second store, no second qualifier, no new storage engine.

The tier is therefore **a property of the key, not a file**:

```kotlin
val SidebarOpen = boolKey("ui.sidebar_open", default = false, tier = Tier.UiState)
val Scale       = floatKey("font_scale", default = 1f, tier = Tier.Settings)
```

That still buys the three things the file split was for, via a predicate instead of a path:

- **export / sync later** — serialize `tier == Tier.Settings` only;
- **"reset my layout"** — `store.clear(Tier.UiState)`;
- **splitting later is free** — every call site goes through `PreferenceStore`, so moving tier 2 into its own
  file (or a Room table) is a DI change, not a refactor.

What one file costs: a corrupt/deleted prefs file takes both tiers with it, and the "these never roam"
boundary is a convention (`tier` + the `ui.` name prefix) rather than a physical fact. Acceptable — the
convention is checkable in one place, and the escape hatch above stays open.

Tier 3 stays out of the preferences file — DataStore rewrites the whole file on every commit, so a blob
gets a file of its own: a `PersistedDocument` (`:core:common` `persist/`) with `Durability.Cache`, which
lands under `cacheDir/documents/` with an atomic write and a versioned schema ledger
(`<module>/schemas/documents/`). `remote_catalogs` and `connector_directories` are the worked examples; a
Room table is still the answer once indexed lookup is needed.

### 3.2 Typed keys, declared once

The god-interface dies. A key becomes **one declaration**, owned by the feature that uses it:

```kotlin
// ui/settings/fonts/FontKeys.kt — lives WITH the feature, like its Feature/koinModule
object FontKeys {
    val Scale = floatKey("font_scale", default = 1f, range = 0.8f..1.4f)
    val Style = enumKey("chat_font_style", default = ChatFontStyle.Serif)
}

// ui/app/AppShellKeys.kt
object ShellKeys {
    val SidebarOpen = boolKey("sidebar_open", default = false)
    val LastChatId  = stringKey("last_chat_id", default = "")
}
```

Backed by a small typed-key abstraction (~120 lines, `core/prefs/` — a port, so `ui` can declare keys without importing `data`):

```kotlin
/** A typed preference key: name + default + codec. Declaring one is the whole cost of a new setting. */
class PrefKey<T> internal constructor(
    val name: String,
    val default: T,
    internal val codec: PrefCodec<T>,
)

interface PreferenceStore {
    fun <T> flow(key: PrefKey<T>): Flow<T>            // always emits; falls back to key.default
    suspend fun <T> get(key: PrefKey<T>): T
    suspend fun <T> set(key: PrefKey<T>, value: T)
    suspend fun <T> update(key: PrefKey<T>, block: (T) -> T)
    suspend fun remove(key: PrefKey<*>)
    suspend fun clear()                                // "reset my layout" / "reset settings"
}
```

Codecs cover the primitives natively and everything else through kotlinx-serialization; `enumKey` stores
`enum.name` and falls back to the default on an unknown value — the forward-resilience rule CLAUDE.md already
mandates. Clamping (`range`) moves into the key, so `fontScale` can't be clamped in one place and not another.

One injected instance, over the DataStore that already exists:

```kotlin
single<PreferenceStore> { DataStorePreferenceStore(get(USER_PREFS)) }
```

**Cost of a new setting: 1 line + 1 read site. No interface, no impl, no fake.** Tests use an in-memory
`FakePreferenceStore` (a `MutableMap` + `MutableStateFlow`) that needs no edit per key — which alone deletes
the sixth edit above and a whole class of "forgot to update the fake" compile breaks.

### 3.3 Reading it

Three access shapes, matching how the code already reads prefs:

```kotlin
// 1. In a ViewModel — same as today
val sidebarOpen: StateFlow<Boolean> = store.flow(ShellKeys.SidebarOpen)
    .stateIn(scope, WhileSubscribed(5_000), ShellKeys.SidebarOpen.default)

// 2. Outside composition (desktop window geometry, read before the window exists)
val bounds = runBlocking { store.get(WindowKeys.Bounds) }

// 3. App-root theme locals — unchanged (hosts already provide LocalFontScale etc.)
```

A `rememberPreference(key)` composable handle — a drop-in for `rememberSaveable` — was considered and **not**
built. The store reads asynchronously, so the first frame would render `key.default` and then snap to the
stored value: exactly the flash the sidebar needed to avoid. Where that matters, seed through a ViewModel
before the screen is allowed to render (`AppViewModel` reads the sidebar before `initialChatId` unblocks the
shell). Worth adding later only for state whose first frame genuinely doesn't matter.

### 3.4 Cross-platform, without per-platform code

The store construction is the only platform-touching part, and the hook already exists — `PlatformPaths`
(`core/storage/PlatformPaths.kt`) hands out `filesDir`/`cacheDir` as okio `Path`s per platform. So the factory
moves **out** of each host's Koin module and into `commonMain`:

```kotlin
// commonMain — one definition, every target
fun preferenceStore(paths: PlatformPaths, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath { paths.filesDir / "$name.preferences_pb" }
```

This used to be duplicated per host (an Android `preferencesDataStoreFile` call and a desktop hard-coded
path); phase 1 made the move, deleting both host copies. So **adding
iOS or Windows requires zero storage code** — a new target supplies `PlatformPaths` (which it must anyway for
downloads) and both stores exist. DataStore's KMP artifact already resolves the iOS document directory
through okio, so this is a supported path, not a workaround.

### 3.5 Write-frequency discipline

Preferences DataStore serializes the **entire file** per commit. That is fine for settings (a tap) and wrong
for a window-resize drag (60 writes/second). Tier 2 therefore gets a debounce at the call site:

```kotlin
// window bounds, panel widths, scroll anchors — coalesce, then commit
LaunchedEffect(bounds) { delay(300); store.set(ShellKeys.WindowBounds, bounds) }
```

Rule of thumb: **if a value changes while a finger/mouse is down, debounce it.** If we ever get enough hot
keys that this stops being sufficient, tier 2 moves to a Room table with per-row writes — which is exactly
why VS Code's Memento is SQLite — without any call-site change, because `PreferenceStore` is an interface.

---

## 4. Where our existing keys land

| Key | Tier | Why |
|---|---|---|
| `theme_mode`, `font_scale`, `chat_font_style` | Settings | user intent, worth syncing |
| tool permissions (`always_allowed_tools`, `denied_tools`), `ask_before_each_tool` | Settings | intent + security posture |
| speech provider/voice, `voice_turn_policy_*`, dictation toggles | Settings | intent |
| `oauth_redirect_strategy`, keyboard prefs | Settings (JetBrains would say `PER_OS`) | intent, but platform-shaped |
| `model_selection` document (active per modality, last used, per-tier, the drawn cards) | Intent document, per-device | read at process start so every surface paints the choice on its first frame; per-device (a phone can't run the desktop's local model) |
| `ime_page` | UI state | positional |
| sidebar open, window bounds, last chat, panel widths | UI state (new) | the ask |
| `imported_models` / `sampler_overrides` documents | Intent document | authored data: never auto-reset; an unreadable file is copied aside before the default replaces it |
| `fs_roots_json` | Settings | authored data — arguably a future Room table if it grows |
| `connector_directories` / `remote_catalogs` documents, connector icons | Cache | refetchable |

`active_model_by_modality` moving to tier 2 is the interesting one: it is currently a "setting", but it is
really "what I last used here", and syncing it to a device that lacks the model is actively harmful. This is
the same reasoning Chrome applies to `storage.sync`'s 8 KB/item limit — if it is per-device reality, it does
not roam.

---

## 5. Phasing

Each phase compiles and ships on its own; no phase is a big-bang rewrite.

1. ~~**Foundation**~~ — **done.** `PrefKey` / `Tier` / `PreferenceStore` / `DataStorePreferenceStore`, and the
   DataStore factory moved into `commonMain` over `PlatformPaths` (deleted from both host Koin modules).
   Binds to the existing `USER_PREFS` instance; `UserPreferencesRepository` keeps working untouched beside it
   — same file, same keys, so the two coexist by construction. Covered by `PrefKeyTest` (6 cases).
2. ~~**The ask**~~ — **done.** `ui.sidebar_open` (survives restart; on desktop `rememberSaveable` never did),
   `ui.last_chat_id` (reopens the last chat, falls back if it was deleted, never records incognito), and
   desktop `ui.window_bounds` (debounced 500 ms, restored before the window opens).
3. ~~**Migrate settings feature-by-feature**~~ — **done.** Every key moved next to the feature that owns it
   (`SearchPrefs`, `ToolPrefs`, `ModelPrefs`, `SpeechPrefs`, `KeyboardPrefs`, `ConnectorPrefs`,
   `NotificationPrefs`, plus the existing `AppearanceKeys`/`FontKeys`/`ShellKeys`/`WindowKeys`). Keys kept
   their existing names and defaults, so stored data carried over with no migration code (CLAUDE.md).
   Behaviour that lived on the interface became extensions on `PreferenceStore` beside the key.
4. ~~**Delete `UserPreferencesRepository` and its fake**~~ — **done.** The interface (102 lines), its
   implementation (360) and `FakeUserPreferencesRepository` are gone; tests use `FakePreferenceStore`,
   seeding only the keys under test. The typed layer gained `nullableStringKey`/`nullableEnumKey` for the
   prefs whose absence is meaningful ("auto" / "not set"), where writing null removes the key.
5. **Cache tier** — *mostly done.* Cached blobs are `PersistedDocument`s with `Durability.Cache` (typed,
   atomic, ledgered; TTL from a recorded fetch time, not file mtimes). What remains is a user-facing "clear
   cache" that resets every Cache-durability document.

Phases 1–4 have shipped; phase 5 lacks only the "clear cache" action.

---

## 6. Decisions worth confirming

- **One file vs. two.** ~~Recommendation: two.~~ **Settled: one** — reuse the existing `user_prefs` store and
  `USER_PREFS` qualifier, with `Tier` on the key carrying the policy. Every product surveyed splits the two
  tiers, but they split them because their tiers have different *engines* or *sync transports*; ours don't yet.
  Keeping one store means the new layer adds no storage surface at all, and `PreferenceStore` keeps the split
  available as a DI change if sync ever lands.
- **`active_model_by_modality` → UI state.** Correct by the roaming rule, but it *is* a behavior change the
  day we add sync. Flagging rather than assuming.
- **Room for tier 2 instead of DataStore.** Only if key count or write rate grows past debouncing. The
  `PreferenceStore` interface keeps this a swap, not a refactor.
- **Sync is out of scope here.** This design only makes sync *possible* later (tier 1 is a single file of
  semantic keys). No sync code is proposed now.

---

## Sources

- [VS Code extension storage options — Elio Struyf](https://www.eliostruyf.com/devhack-code-extension-storage-options/)
- [VS Code Extension Storage Explained](https://medium.com/@krithikanithyanandam/vs-code-extension-storage-explained-the-what-where-and-how-3a0846a632ea)
- [Persisting State of Components — IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)
- [What does the RoamingType parameter do? — JetBrains support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206751465-What-does-the-RoamingType-parameter-of-the-Storage-annotation-do-)
- [chrome.storage — Chrome for Developers](https://developer.chrome.com/docs/extensions/reference/api/storage)
- [Local vs Sync vs Session: Which Chrome Extension Storage Should You Use?](https://dev.to/notearthian/local-vs-sync-vs-session-which-chrome-extension-storage-should-you-use-5ec8)
- [Set up DataStore for KMP — Android Developers](https://developer.android.com/kotlin/multiplatform/datastore)
- [multiplatform-settings (russhwolf)](https://github.com/russhwolf/multiplatform-settings)
- [Implementing DataStore in Kotlin Multiplatform Projects — Carrion.dev](https://carrion.dev/en/posts/datastore-in-kmp/)
