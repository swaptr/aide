# DEFERRED — the single register of deferred behavior

Every deliberate "not yet" in the codebase, each with a verdict from the 2026-08-05 sweep (refreshed
2026-08-18 after the capability-pattern refactor). Three verdicts:
**REMOVED** (dead weight, deleted per the CLAUDE.md no-placeholders policy), **TRACKED** (legitimately
deferred; the entry here is the plan), **IMPLEMENTED** (promoted during the sweep). Supersedes the old
`TODO.md`. The Koog-era `TODO_koog.md` is gone with Koog: its entries are closed under "Formerly
upstream-blocked" below, and the `:aisdk` port's own remaining work lives in the root `TODO.md`.

## Removed by the sweep (dead placeholders)

- **"Research" row** in the Add-to-chat sheet — disabled toggle labeled "Not available yet". Re-add with
  the feature.
- **`shared/SharedModule.kt`** — "real shared code moves here in Phase 2+" marker; the migration shipped.
- Stale comments: `AnthropicApi` "ToolChoice … later slice" (it's wired), `KoogChatCodec` topK/topP
  claims (now wired).

## Implemented by the sweep

- **Remote topP/topK** — `SamplerSheet` exposed them for every model while Koog codecs dropped them.
  Now ride `GoogleParams`/`OpenAIChatParams`, gated by `disabledParams`, with the LiteRT defaults
  (40/0.95) treated as "unset" so untouched sheets leave server defaults in charge.
- **Desktop photo picking** — `rememberPhotoPicker` was a `{}` stub; now FileKit's native image dialog.
- **Desktop image staging** — the store was a PASSTHROUGH whose `delete()` removed the user's original
  file; now copies into `filesDir/attachments`.
- **Desktop clip playback** — `ClipAudioPlayer` was all no-ops (recorded clips were unplayable); now
  `javax.sound.sampled` (WAV — the recorder's format; non-WAV degrades silently like Android).
- **Prefs migration EXEMPLAR (phase 3 begun)** — theme + fontScale + chatFontStyle moved to typed keys
  (`AppearanceKeys`/`FontKeys`), the six legacy members DELETED from
  `UserPreferencesRepository`/impl/fake. This is the pattern for the rest.

## Done since the sweep

- **The prefs migration** — every key moved to a typed `PrefKey`; `UserPreferencesRepository`, its impl and
  its fake are deleted. (The cache tier is now `PersistedDocument`s with `Durability.Cache`; only a user-facing "clear cache" remains.)
- **The `:shared` split** — `:shared` is gone; `:ui`, `:di`, `:core:*`, `:data*` are the modules now.
- **E6 `ModelDescriptor`** — landed with the model-layer generalization: `ModelSpec` is open, chat facts sit
  on `ChatModelSpec`, and `ModelSpec`/`SpeechAssetSpec` share the thin `ModelDescriptor` identity.
- **Image generation** — shipped as the governed `GenerateImage` tool, per
  `docs/image-and-cloud-speech-providers.md` Part 2 path (A). Edit / variations / streaming partials and the
  dedicated image surface (path B) remain deferred, still waiting on a consumer.

## Tracked — do next (highest value first)

1. **Emulator job in CI** — `connectedDebugAndroidTest` (incl. `AndroidKoinGraphTest`) runs only on a
   plugged-in device. The 2026-08-20 Koin multi-binding crash is the concrete argument: the desktop graph
   test cannot see it by construction (`:desktopApp` contributes no second implementation of anything), so
   the only check that catches an Android-graph collision never runs in CI. Fix = a `macos`/`ubuntu` runner
   with the emulator action running `:app:connectedDebugAndroidTest` on PRs.
2. **Interleaved-thinking replay fidelity** — thinking parts replay grouped before tool_use; docs require
   the generated sequence. Becomes real when adaptive + multi-tool rounds reject replays; fix = order
   parts by content-block index (noted in `AnthropicMessageMapping`).
3. **Desktop distributables** — `.deb`/`.msi`/`.dmg` need Sherpa natives bundled via
   `appResourcesRootDir` + `$APPDIR` library path (dev `run` fully wired; packaging path commented in
   `desktopApp/build.gradle.kts`).
4. **Wire `DesktopAudioPlayer`** — bound but unconsumed; desktop has no `VoiceOutputChannel`/
   `VoiceTurnLoop` (`:app`-only), so no TTS output sounds on desktop — on-device Sherpa voices and the
   cloud voices (OpenAI, ElevenLabs, Gemini over `:aisdk`) are both selectable there and both unheard
   until this lands. A follow-up worth doing with it: a nullable `PlatformAffordances.spokenReplies`
   member the Add-model wizard reads, so the Speech group is only offered where something plays it.
5. **Per-provider TTS voice** — `SpeechPrefs.PreferredTtsVoiceId` is one pref for the ACTIVE engine (a
   Sherpa speaker number, an Android `Voice.getName()`), so the cloud engines ignore it and speak with the
   vendor default (`alloy`, ElevenLabs' default voice, `Kore`). Keying the pref by provider is the fix;
   it ships with a voice picker for the cloud vendors.
6. **Surface `ModelWarning`s** — the `:aisdk` runtime now reports what a provider ignored or changed
   (a dropped `topP`, a raised `max_tokens`) on `ChatStreamEvent.Completed.warnings`, and
   `SendChatMessageUseCase` only logs them. An `AppNotice` on the turn is the consumer; until then a
   user cannot see why a sampler setting had no effect.
7. **Provider-native tools** — `ChatCapabilities.toolsNative` was deleted (written as `emptySet()`
   everywhere, read nowhere). When a toolset wants a vendor-run tool (`anthropic.web_search`,
   `google_search`), it is one `Tool.ProviderDefined` in `AiSdkCallOptions.activeWireTools` plus a
   capability the catalog can actually populate — not a field that pretends to.
8. **Anthropic audio attachments** — the `:aisdk` Anthropic mapper has no arm for `audio/*` and would
   send one as a `document` (a vendor 400). Unreachable today: the `audioIn` gate drops the part first and
   no Claude entry sets `audioIn`. If one ever does, the mapper should warn and drop rather than fail.

9. **Perplexity needs its own `ProviderId` before 2026-09-27.** Perplexity retired Sonar Chat Completions
   in favour of the Agent API (`POST /v1/agent`, a Responses-shaped wire), and `:aisdk` now serves it as
   `PerplexityProvider.languageModel` — a different wire from the OpenAI-compatible one that
   `ProviderId.OPENAI` stands for. Until AIDE grows a Perplexity provider descriptor over that class,
   `CompatVendors` maps `api.perplexity.ai` to a plain custom compat row (chat only, no vendor usage
   arithmetic), which keeps working exactly as long as the legacy endpoint does. After the date the row
   answers a dead endpoint, so the descriptor is the fix, not a flag.

## Tracked — needs its platform/feature moment

- **Desktop on-device models** — storage and the download stack are shared code now (`ModelStorageImpl`,
  `CoroutineDownloadScheduler`, `ModelAssetSource`), so what is left is a real `ModelCatalog` for desktop
  plus an engine to run the weights; `DesktopModelImportRepository`/`DesktopResidencyManager` stay inert.
  With it come `rememberModelFilePicker` (FileKit makes it trivial) and imports.
- **Desktop device tools** — the portable toolsets (time, math, web, filesystem) run on desktop; the
  device-bound ones (clock, phone, calendar, contacts, clipboard) are Android-only by module placement and
  would each need a desktop peer. `rememberFolderPicker` desktop stub rides with this.
- **Desktop camera / contact picker** — no desktop hardware abstraction; stubs stay (`onResult(null)`),
  Attach-sheet tiles gate correctly.
- **iOS / additional platforms** — not a target; the 8 `expect/actual` seams + a Koin module + an
  entrypoint is the recipe. `SecureRandom` notes its iOS actual (`SecRandomCopyBytes`).
- **Connectors**: CIMD OAuth path (metadata already parsed; needs a hosted client-metadata doc) + manual
  `client_id` entry UI for servers with neither DCR nor CIMD.

## Tracked — policy-deferred (ship WITH the first consumer, never speculatively)

Specs + guardrails: `docs/architecture-analysis.md` §E3–E7 + §F.
- **E3** content/control split + media bytes-vs-url — lands with the first URL/media consumer.
- **E4** `providerOptions` namespaced vendor-knob hatch — lands with the first vendor knob
  (note: `reasoning_effort` shipped 2026-08-05 as a NEUTRAL concept instead; E4 is for knobs that stay
  vendor-private, e.g. Anthropic `cache_control`).
- **E7** capability-gated param drop — generalizes today's manual drops (`disabledParams` handling in
  the codecs is the seed; see `ModelWarning`).

## Formerly upstream-blocked — closed by the `:aisdk` port

The Koog-era losses (the Anthropic streaming signature, Gemini's thought signature, the Completions-path
`reasoning_content`, OpenRouter→Gemini-3 `thought_signature`) are all carried as `providerMetadata` /
`ReasoningDelta` through `:aisdk` and pinned by `AiSdkSeamSignatureTest`. What remains is the on-device
gate in `TODO.md` §2.
