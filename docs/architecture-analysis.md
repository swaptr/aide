# Architecture analysis — the cross-modal, cross-provider core

> **Terminology note (2026-08-18).** Written when the project used Hilt: read "typed Hilt multibinding" as
> a **Koin contribution binding** collected into a named registry (ARCHITECTURE.md §2a). Of the deferred
> items below, **E6 (`ModelDescriptor`) has landed**; E3, E4, E5 and E7 are still waiting on their first
> consumer, exactly as §F prescribes.

A deep review of how Aide takes *any* modality of input, runs *any* provider's model on it, and emits
*any* modality of output — hunting real bugs in the logical representation of data, then distilling the
**fundamental building blocks** that let the design transcend providers (Anthropic · OpenAI · ElevenLabs
· on-device Sherpa/LiteRT) and modalities (chat · ASR · TTS · image · embedding).

Grounded in a full read of `domain/` + `data/` and an adversarial pass over the message IR, the wire
mappers, the streaming contracts, the IO-channel layer, and the catalog/capability/config layer; and
benchmarked against the four best-engineered open frameworks (Vercel AI SDK v5, Microsoft.Extensions.AI,
Spring AI, LangChain4j) plus the gateway pattern (LiteLLM, OpenRouter). Companions:
[assistant-architecture.md](./assistant-architecture.md) (north-star), [ARCHITECTURE.md](../ARCHITECTURE.md)
(as-built), [image-and-cloud-speech-providers.md](./image-and-cloud-speech-providers.md) (next providers).

> **STATUS — IMPLEMENTED 2026-06-16 (device-verified).** Every finding below (A1–A9, B1–B7, C1–C8, D1) is
> FIXED in the codebase; this doc is retained as the analysis / rationale of record, not an open-issue list.
> Phase 1 (message-IR round-trip), Phase 2 (hygiene/renames), and Phase 3 (stream + IO seam — incl. the
> uniform `End(SpeechStreamOutcome)` terminal, neutral `ReasonEvent`, de-STT'd `InputChannel`) are all done
> and verified (`clean :app:testDebugUnitTest` — 144 tests, 0 failures — plus on-device voice + dictation).
> **Three deliberate deviations:** C6 keeps `SpeechEngineRepository.Role` (collapsing it loses enum
> exhaustiveness); B4 keeps the live `IdentityOutputChannel` (chat uses it); E2 leaves chat's
> `ChatStreamEvent.Completed` as the reference terminal (it already folds error in). The **E1 / E3–E7**
> building blocks remain deferred until their first consumer (image-gen, cloud speech) — see the **F**
> sequence. As-built summary + roadmap: `ARCHITECTURE.md §15` + §17.

---

## 0. Verdict first

**The architecture is fundamentally sound and externally validated — do not rebuild.** The two strongest
modern references are a near-line-for-line implementation of what Aide already does:

- **Vercel AI SDK v5** ships *five independent peer spec interfaces* (`LanguageModelV2`, `EmbeddingModelV2`,
  `ImageModelV2`, `SpeechModelV2`, `TranscriptionModelV2`) with **no shared `Model` supertype**, dispatched
  **by type**, a thin provider-as-factory, a content-parts message IR, and a provider-options escape hatch.
  That is Aide's `ChatProvider`/`SpeechProvider`/(designed)`ImageProvider` resolved by typed Hilt
  multibinding. The SDK's own rule — *"adding a modality = a new peer interface, never an edit to existing
  types"* — is **verbatim** the KDoc in `Provider.kt`.
- **LangChain4j** independently arrived at Aide's content-parts IR (`UserMessage.contents: List<Content>` ≡
  `AideMessage.parts: List<AidePart>`).
- **Spring AI** is the *one* framework that built the generic `Model<TReq,TResp>` umbrella Aide deliberately
  rejected — and Spring's own source shows that umbrella is **~90% nominal** (no registry/dispatcher consumes
  `Model<?,?>`; every caller holds the concrete type). Adopting it would be a regression.

So the modality-first spine is right. The work is **surgical**, in four buckets:

| Bucket | What | Severity |
|---|---|---|
| **A. Message-IR round-trip bugs** | a *cluster* of real correctness bugs where persist→reload→re-send changes meaning or emits invalid requests | several **ship-blockers** |
| **B. Stream + IO seam drift** | 3 streaming hierarchies with 3 incompatible terminal/error contracts; an IO layer that claims modality-agnosticism it doesn't have | high |
| **C. Dead & misnamed types** | confirmed dead fields/files; chat-shaped types named as if generic; 3 parallel "modality" taxonomies | hygiene |
| **D. Residency mis-estimate** | speech residents report 0 bytes → LRU trim defeated | med |

Then **E** distills the fundamental building blocks (what to abstract, validated against the frameworks),
and **F** sequences the work and lists what *not* to do.

The root cause threading most of bucket A together is one representational decision worth stating up front:

> **`AidePart` conflates content (Text/Image/Audio) with turn-structure (ToolCall/ToolResponse/Thinking)
> in a single flat `List<AidePart>`, and each of the four wire mappers independently re-derives (a) which
> parts are content vs control, (b) the strip-before-replay rule, and (c) tool-call↔result id pairing.**
> That independent re-derivation is where the asymmetries and the id/pairing bugs live.

---

## A. Message-IR round-trip bugs (correctness — ship-blockers)

These are real because the IR is **persisted** (`AidePart` is `@Serializable` → Room) and **provider-neutral**:
a chat is reloaded as `initialMessages` and re-sent, possibly under a *different* provider than it was
created with. Severity, file:line, the precondition, the failure, the fix.

### A1 · HIGH · LiteRT tool results are silently double-encoded on reload
`data/chat/LiteRtMessageMapping.kt:30` maps `AidePart.ToolResponse → Content.ToolResponse(name, json)`,
passing the raw **JSON string**. The live tool loop (`data/llm/litert/LiteRtLmChatSession.kt:148`) passes
`Content.ToolResponse(name, envelope.toAnyMap())` — a parsed **Map**. `Content.ToolResponse`'s payload
param is `Object`, so the type system can't catch the mismatch; the engine serializes a `Map` as a JSON
object but a `String` as an escaped JSON string literal. **Precondition:** reopen any LiteRT chat that used
tools. **Failure:** every past tool result the on-device model sees becomes a double-encoded blob — its view
of its own history silently changes across an app restart. **Fix:** parse before wrapping in the mapper
(`Content.ToolResponse(name, parse(json).toAnyMap())`), or store the structured map in `AidePart.ToolResponse`.

### A2 · HIGH · LiteRT reload keeps the tool *response* but drops the tool *call* → orphaned result
Same file: `AidePart.ToolCall -> null` (line 31, dropped because LiteRT carries calls on `Message.toolCalls`,
not as a `Content`) while `AidePart.ToolResponse` is kept (line 30). **Precondition:** reload a LiteRT chat
that used tools. **Failure:** the engine receives a tool *response* with no preceding tool *call* in the
replayed conversation — model-dependent confusion/error. **Fix:** replay tool calls via the engine's real
channel (`Message.toolCalls`) or drop the paired response too — never keep one side of the pair.

### A3 · HIGH · Synthesized `callId` counter resets every turn → cross-turn id collisions
`data/llm/remote/RemoteChatSession.kt:93` (`var callCounter = 0`, local to `send()`) and
`LiteRtLmChatSession.kt:113` mint `tc-1`, `tc-2`, … fresh **per turn**. The id is persisted on
`AidePart.ToolCall`/`ToolResponse`. **Precondition:** an Ollama/LiteRT-origin chat (no server ids) that used
tools across ≥2 turns, later resumed under OpenAI or Anthropic. **Failure:** the replayed history contains
duplicate `tc-1` pairs; OpenAI requires `tool_call_id` unique within a request and Anthropic requires unique
`tool_use` ids → ambiguous pairing → API rejects or mis-links. **Fix:** instance-level counter (survives
turns) or a UUID; ids must be unique within a chat, not a turn.

### A4 · HIGH · A `tool` message with a null id, or with no preceding assistant `tool_calls`, is an invalid OpenAI request
`data/chat/OpenAiMessageMapping.kt:22-27` emits `role:"tool"` with `tool_call_id = toolResponse.callId`
passed through raw. **Two preconditions:** (a) the process is killed after `appendToolResponse` but before
the assistant turn's `tool_calls` are persisted (`SendChatMessageUseCase` persist path) → a `tool` message
with no preceding `tool_calls`; (b) any non-OpenAI-origin transcript where `callId == null` → a `tool`
message with a null `tool_call_id`. **Failure:** OpenAI 400 (*"messages with role 'tool' must be a response
to a preceding message with 'tool_calls'"*); continuation of that chat is bricked. **Fix:** a list-level
validate/repair pass (below) that guarantees pairing and never emits a null `tool_call_id`.

### A5 · HIGH · Anthropic `tool_use_id` falls back to the tool *name*
`data/chat/AnthropicMessageMapping.kt:~97` does `put("tool_use_id", tr.callId ?: tr.name)`, while the
matching block uses the assistant `tool_use` `id`. **Precondition:** `callId == null` (LiteRT/cross-provider
origin). **Failure:** the `tool_result` id (`name`) ≠ the `tool_use` id → Anthropic 400. **Fix:** never fall
back to `name`; carry a guaranteed-non-null id on the call/response pair at creation.

### A6 · MED · Anthropic same-role coalescing is done only for `Tool`, not `User`/`Model`
`AnthropicMessageMapping.kt:~36-63` merges consecutive `Tool` turns but not consecutive `User` or `Model`.
Anthropic requires strict user/assistant alternation. **Precondition:** a mid-conversation `System` message
(hoisted out, leaving neighbors adjacent), two back-to-back user inputs, or a tool-result-user-turn
immediately followed by a real user turn. **Failure:** Anthropic 400 (*"roles must alternate"*). **Fix:**
generalize the `Tool` coalesce to all roles (concat content blocks of any consecutive same-role run).

### A7 · MED · `firstOrNull()` drops multi-result tool turns on OpenAI/Ollama; Anthropic keeps them
`OpenAiMessageMapping.kt:22` and `OllamaMessageMapping.kt:~26` take `filterIsInstance<ToolResponse>().firstOrNull()`;
`AnthropicMessageMapping` fans out *all* tool_result blocks. **Failure:** the three providers disagree on the
meaning of the same IR the moment a `Tool` message holds >1 response (hand-built history, future batching) —
silent data loss on two of three. **Fix:** pick the invariant ("exactly one ToolResponse per Tool message")
and enforce it in the normalizer, or map all parts on every provider.

### A8 · MED · `ImageBytes`/`ImageFile` carry no MIME → sniffed from base64 magic, mislabels HEIC/AVIF/BMP
`AideMessage.kt` image parts have no `mediaType`. OpenAI (`imageMimeFromBase64`, `OpenAiMessageMapping.kt:86`)
and Anthropic both **guess** the mime and default to `image/jpeg`. **Precondition:** attach a HEIC/AVIF/BMP
image (common on modern phones). **Failure:** Anthropic requires an accurate `media_type` and rejects a
mismatch → 400 or corruption. **Fix:** capture `mediaType` at attach time (you own `ImageStore`) and thread
it through instead of sniffing. (See building block **E3**.)

### A9 · MED · Image files are re-read and re-base64-encoded on *every* tool round; no size cap; silent drop
`OpenAiMessageMapping.kt:44` (`readFileAsBase64`) reads the file inside `history.map { it.toOpenAi() }`, which
runs once per round of a multi-round tool turn (the whole history is re-mapped each `streamRound`). It is on
`Dispatchers.IO` (not main — `RemoteChatSession` `.flowOn(ioDispatcher)`), so not an ANR, but a multi-MB
image in a 4-round tool conversation is read+encoded 4× (~1.33× blowup each), with no size guard and a silent
`getOrNull()` drop on failure. **Fix:** encode once and cache (hoist out of the per-round mapper); add a size
cap; log on failure instead of dropping silently.

### The cluster's single highest-leverage fix
A4·A5·A3·A6·A7 are all "each mapper re-derives pairing/coalescing independently." Introduce **one list-level
`normalizeForWire(history): List<AideMessage>` pass that runs before any codec** and:
1. assigns globally-unique, stable tool-call ids (fixes A3, A4, A5),
2. asserts every `ToolResponse` has a matching preceding `ToolCall`, repairing/dropping orphans (A2, A4),
3. coalesces consecutive same-role messages (A6),
4. enforces one-ToolResponse-per-Tool-message or fans out (A7),
5. attaches `mediaType` (A8).

Every codec then maps a *validated* history and stops carrying repair logic. This is the **adapter-layer
normalization** that LiteLLM (`transform_request`) and the Vercel SDK both do; Aide already has the seam
(`*MessageMapping.kt`) — it just needs to run once, before the per-provider map, instead of N divergent times.

---

## B. Stream + IO seam — terminal/error/cancellation drift

The three modality streams diverge in their *terminal contract* for no modality-driven reason; the divergence
leaks into every consumer.

### B1 · HIGH · Three streams, three incompatible terminal contracts
| | Chat (`ChatStreamEvent`) | STT (`SttStreamEvent`) | TTS (`TtsStreamEvent`) |
|---|---|---|---|
| terminal | `Completed(stopReason, usage, …)` | `Completed` (object) | `Completed` (object) |
| error | folded into `Completed(Error)` **and** rethrown | `Error` event, **non-terminal**, then `Completed` | `Error` event, **terminal**, no `Completed` |
| always a terminal? | yes | yes | **no** (error path emits only `Error`) |
| extra terminal | — | `Endpoint` (repeatable, per-utterance) | — |
| carries usage | yes | no | no |

There is no uniform "is this terminal / did it fail?" predicate. A generic consumer of any of the three must
special-case the modality. This is the strongest evidence the split leaks. **Fix:** one uniform terminal —
`End(outcome: Done | Error(cause) | Cancelled, final: F?)` — with error folded into it (as chat already does),
deleting the standalone `Error` events. Keep modality-specific *payloads* (deltas/usage/pcm); unify only the
*terminal*. (Building block **E2**.)

### B2 · MED · Dead/redundant cases inside the "modality-specific" streams
`TtsStreamEvent.Completed` is consumed by nobody (`VoiceOutputChannel` relies on flow completion;
`Completed -> {}`); `TtsStreamEvent.Boundary.offsetChars` is emitted only by System TTS and ignored by the
sole consumer; `SttStreamEvent.Endpoint` is redundant with `Final` in every producer. Three of the
"modality-specific" cases are dead or redundant — the diversity is partly noise, which weakens the
"split is justified by clarity" argument.

### B3 · HIGH · The "modality-agnostic" `InputChannel` is actually voice-only
`domain/io/InputChannel.kt`: `InputEvent.Partial(text: String, rmsDb: Float?)` hardcodes a transcript and a
**microphone RMS level**; `InputOptions` carries `stt: SttOptions` + `micAllowed` ("Ignored by text channels").
An image-input channel has no "live cumulative transcript" and no mic level — it can implement `capture()`
only by smuggling data through `Final(parts)` and emitting zero partials. The abstraction claims a generality
it lacks. **Fix:** `Partial(parts: List<AidePart>, progress: Float? = null)`; move `rmsDb`/`SttOptions` out of
the shared type into the voice channel.

### B4 · MED · `OutputChannel<E,S>`'s second type-param is justified by a usage that doesn't exist
`OutputChannel.kt`'s KDoc cites *"the IME via `RunTaskUseCase.Event`"* — but `TransformController` (the IME)
**never references `OutputChannel`** (0 grep hits). The only real `OutputChannel` is Voice; the only other is
`IdentityOutputChannel` (a no-op the chat ViewModel then bypasses). The generic carries zero weight, and
`render()` has **no terminal/error contract** (each `S` invents its own: `Idle` vs `Done` vs nothing-on-cancel).
**Fix:** correct the KDoc; consider collapsing to a concrete `VoiceOutputChannel` and deleting
`IdentityOutputChannel` until a second transforming output exists; if kept, give `S` a terminal bound.

### B5 · HIGH · `domain.io` depends on `domain.usecase` — the speech layer is welded to the chat use case
`domain/io/OutputChannel.kt` *imports* `SendChatMessageUseCase` (for a KDoc ref), and
`data/speech/io/VoiceOutputChannel` / `domain/io/VoiceReasoner` are typed on `SendChatMessageUseCase.Event` —
a fat chat-orchestration union carrying `assistantMessageId`, tool-confirm prompts, severities. **Failure:**
you cannot drive `VoiceOutputChannel` from a non-chat reasoner (a pure read-aloud, a different agent loop)
without manufacturing chat-only `Event`s. The architecture's "independent middle reason stage" is, at the
seam, the chat use case. **Fix:** a thin neutral `ReasonEvent` (TextDelta · ToolAnnounce(name) · Done(text) ·
Error · Warming) in `domain/io`; `VoiceReasoner`/`OutputChannel` speak *that*; adapt
`SendChatMessageUseCase.Event → ReasonEvent` once. Removes the `domain.io → domain.usecase` edge.

### B6 · MED · The chat text delta is reconstructed by string-stripping in the voice path
`VoiceOutputChannel` consumes `Event.Streaming.text` (cumulative) and recovers the delta via
`event.text.removePrefix(accumulated)`. **Failure:** if a provider ever revises earlier text (non-monotonic
update), `removePrefix` no-ops and the *entire* reply is re-queued to TTS → double-speak. Correct only under
the unstated "strictly append-only" assumption. **Fix:** carry the real delta to the voice surface instead of
re-deriving it (related to B5's neutral `ReasonEvent`).

### B7 · MED · TTS synthesis failure is swallowed to a log line
`VoiceOutputChannel.drainTts` downgrades `TtsStreamEvent.Error` and non-cancellation throwables to
`AideLog.w`; `VoiceLoopState.Error` is reachable only from the *reason* stream. **Failure:** if synthesis dies
every sentence (engine unload, audio-focus loss), the user sees `Speaking…` → `Idle` with silence and no
error. **Fix:** surface persistent synthesis failure as `VoiceLoopState.Error(recoverable=true)`.

*(Positive note from the audit: cancellation hygiene is correct everywhere — no `CancellationException` is
swallowed across `RemoteChatSession`, `LiteRtLmChatSession`, the speech repos, `VoiceOutputChannel`,
`VoiceTurnLoop`. Structured concurrency is well-respected; the issue is purely the terminal/error *shape*.)*

---

## C. Dead & misnamed types (logical-representation hygiene)

| id | finding | action |
|---|---|---|
| C1 | `ModelGateState.modality` (+ its `Ready`/`Downloading`/`NoModel` overrides) is **written, never read** — confirmed dead since Phase-4 "Option 3" moved per-modality readiness to `SpeechEngineRepository` | delete the property + overrides |
| C2 | `domain/model/ModelSpecExtensions.kt` — all four `@Deprecated` shims (`supportsVision`/`supportsAudio`/`supportsTools`/`maxTokens`) have **no remaining callers** (the look-alike hits are local vals / `ChatHeader` fields) | delete the file |
| C3 | `GenerationConfig.randomSeed` — **never read by any engine** (LiteRT sampler uses topK/topP/temp only; no remote codec sends it) | delete the field |
| C4 | `CapabilitySet.embeddings` (write-only; embeddings are filtered out before becoming specs), `maxContext`/`maxOutput` (assigned, never read for truncation/windowing — `maxTokens` comes from `defaultConfig`), `ModelSpec.runtimeType` (stored, never branched on — no AICore engine) | drop or document as catalog-only metadata |
| C5 | **`CapabilitySet` is structurally `ChatCapabilities`** — every field is a chat concept, zero speech/image fields, referenced only on chat specs. The generic name is a lie of omission that will force bloat when image lands (`maxResolution`/`negativePrompt` on a chat struct) | rename `CapabilitySet → ChatCapabilities`; when image/embedding need caps, make it a `sealed interface` with per-modality variants (or keep caps on the modality-specific spec, as speech already does on `SpeechAssetFamily`) |
| C6 | **Three parallel "modality" taxonomies**: `domain.model.Modality` (value class, 6: Chat/Asr/Tts/Vad/Image/Embedding), `ui.models.Modality` (enum, 3: LANGUAGE/VOICE/IMAGE), `SpeechEngineRepository.Role` (3: STT/TTS/VAD). They collide so hard that `ModelFiltering.kt` aliases `Modality as ModelModality`. "Modality" means two things by layer | rename the UI enum `→ ModalityGroup`; collapse `Role` into domain `Modality` (1:1 subset) |
| C7 | `AidePart.AudioBytes` (raw variant) has **no producer**; mapped only by LiteRT. **`AidePart.AudioFile` is LIVE** — produced `ChatViewModel.kt:513`, read `SendChatMessageUseCase.kt:160` (audio-in shipped *after* the first audit pass; the agent's grep was stale, and `assistant-architecture.md:52` already documents this) | wire an `AudioBytes` producer, or delete only that variant — keep `AudioFile` |
| C8 | `GenerationConfig.applyingSampler` silently drops `ModelDefaultConfig.maxContextLength` (copies only maxTokens/topK/topP/temp) | document or thread it |

`GenerationConfig` is itself chat-shaped (`responseSchema`/`toolChoice`/`thinking`/`stopSequences`) and is the
only options type on `LlmEngine.load`/`newChatSession`. It does not *currently* leak onto a non-chat engine
(none exists), but it **already leaks within chat**: `LiteRtLmEngine.warnUnsupportedConfig` logs that
`responseSchema`/`stopSequences` are **silently dropped** on-device. Rename `GenerationConfig → ChatGenerationConfig`
when image/embedding land; keep `SttOptions`/`TtsOptions` split (ASR-decode and LLM-sampling are genuinely
unrelated — forcing a unified "model options" type would be the same anemic move Spring AI proves doesn't pay).

---

## D. Residency mis-estimate (real bug)

`data/speech/SpeechEngineRepositoryImpl` builds a `SpeechResidentModel` that **does not override
`memoryEstimateBytes()`** → inherits the `0L` default, even though every `SpeechAssetSpec` has a real
`sizeBytes` (e.g. Whisper-large ≈ 483 MB). The chat adapter passes `spec.sizeBytes`. **Failure:** the LRU
trim tie-break (`compareBy(touchSeq, -estimateBytes)`, "evict larger first on ties",
`ResidencyManagerImpl.kt:139`) treats a 483 MB Sherpa STT model as 0 bytes — exactly the heavy native
resident you'd want evicted first. **Fix:** `override fun memoryEstimateBytes() = spec.sizeBytes ?: 0L`
(thread the resolved `SpeechAssetSpec` into the adapter). *(Cloud residency is a non-issue:
`Residency.NONE` short-circuits before any estimate, so cloud specs are correctly never tracked.)*

---

## E. The fundamental building blocks

What to abstract so the core *transcends* providers and modalities — each grounded in the framework
convergence and mapped onto Aide's existing types. The meta-principle, confirmed by 3 of 4 frameworks:
**modality-first with peer typed interfaces is correct; the building blocks below are the cross-cutting
*currency* those peers exchange, not a unifying supertype over the peers themselves.**

### E1 · Keep the spine, keep rejecting the umbrella
`ProviderId × Modality`, peer capability interfaces (`ChatProvider`/`SpeechProvider`/`ImageProvider`) resolved
by typed multibinding, provider-as-thin-factory. **Validated** by Vercel/Mastra (line-for-line) and by Spring
AI's counter-example (its `Model<Req,Resp>` is ~90% inert). **Do not** introduce a generic `Model<Req,Resp>`;
**do not** merge `ModelSpec`/`SpeechAssetSpec` bodies.

### E2 · One streaming contract: modality-specific deltas, a single uniform terminal *(highest value)*
Borrow Vercel's **id-scoped single stream union** (one `Flow` of `start`/`delta`/`end` blocks correlated by
id + a single terminal `finish{usage, finishReason, warnings}`) and Microsoft's **lossless fold** (streaming
updates reuse the buffered content types; `stream().fold()` reconstructs the response → write streaming once).
Apply minimally to Aide: give all three streams a uniform terminal and fold error+cancellation into it.

```kotlin
// the cross-modal currency — payload generic, terminal uniform
sealed interface ModelStream<out D, out F> {
    data class Delta(val delta: D) : ModelStream<D, Nothing>          // text token · stt partial · pcm chunk
    data class Item(val item: Any) : ModelStream<Nothing, Nothing>   // tool-call · boundary · endpoint (non-terminal)
    data class End(val outcome: Outcome, val final: F? = null,
                   val warnings: List<ModelWarning> = emptyList()) : ModelStream<Nothing, F>
}
sealed interface Outcome { object Done : Outcome; data class Error(val cause: Throwable?) : Outcome; object Cancelled : Outcome }
// Chat:  D = text/reasoning delta, F = Usage + StopReason
// STT:   D = Partial,              F = Final transcript (+confidence); Endpoint = Item
// TTS:   D = AudioChunk,           F = Unit;                          Boundary = Item
```
This fixes B1/B2/B7 and gives the `OutputChannel` seam (B4) a terminal it can express. **Keep** the
modality-specific payloads — that part of the 3-way split *is* justified; only the terminal/error/cancellation
shape is gratuitously divergent.

### E3 · Content IR: keep typed sealed parts, but split content vs control, add MIME, model bytes-vs-url
Aide's typed sealed `AidePart` is **better** than Spring's `Object data` and LangChain4j's url-XOR-base64
carrier — keep it. Three upgrades, all validated by Microsoft's `AIContent` hierarchy:

1. **Separate turn-structure from content** (the root cause of bucket A). Either two lists on `AideMessage`
   (`content: List<ContentPart>` + `control: List<ControlPart>`) or a marker interface, so a mapper can't
   silently mis-handle `ToolCall`/`Thinking` and every mapper shares one strip rule.
2. **Add `mediaType`** to image/audio parts (fixes A8); model the bytes-vs-url split explicitly, à la
   Microsoft `DataContent` (always exposes a `data:` URI, self-contained) vs `UriContent` (remote pointer,
   never fetched):
   ```kotlin
   sealed interface ContentPart {
       data class Text(val text: String) : ContentPart
       data class Data(val bytes: ByteArray, val mediaType: String, val name: String? = null) : ContentPart // on-device: Sherpa/LiteRT
       data class Uri(val url: String, val mediaType: String) : ContentPart                                  // cloud: OpenAI/ElevenLabs accept URLs
   }
   ```
3. **`supportedUrls` per engine** (Vercel's pattern): a normalizer downloads a `Uri` into `Data` for engines
   that can't fetch it (Sherpa/LiteRT need raw bytes), passes it through for those that can. This is the
   future-proof answer to **both** generated-image bytes-vs-url (`image-and-cloud-speech-providers.md` §2.1)
   and cloud-asset input. Generated images normalize to a `Data`/`Uri` part — **`AidePart` still needs no
   `ImageUrl` variant.**

*(One-field future option from LangChain4j: multimodal tool *results* — `ToolResponse.content: List<ContentPart>`
instead of `json: String` only — if image-returning tools ever matter. Not now.)*

### E4 · `providerOptions` / `providerMetadata`: the namespaced escape hatch
The cleanest way (Vercel's, beating Spring's down-cast and LangChain4j's CRTP) to carry vendor-specific knobs
**in** and vendor data **out** without a neutral field per vendor:
```kotlin
typealias ProviderOptions = Map<String, JsonObject>   // keyed by ProviderId.value: "anthropic" → {cache_control…}
```
Add to `GenerationConfig` (IN) and the terminal `End`/response (OUT). Each codec reads only its own key:
Anthropic `cache_control`, ElevenLabs `voice_settings`/`optimize_streaming_latency`, LiteRT sampler extras,
OpenAI `reasoning_effort`. **Adopt when the first real provider-specific knob lands** — not speculatively.
Pair with a `rawRepresentation: Any?` (`@Transient`) for the rare native down-cast (Microsoft's pattern).

### E5 · A warnings channel — "didn't fail, but couldn't honor X"
Today LiteRT silently drops `responseSchema`/`stopSequences` and Ollama silently degrades `ToolChoice`
(Required/Named → Auto). Make it honest (Vercel's `CallWarning`; Microsoft notably *lacks* this — a gap):
```kotlin
sealed interface ModelWarning {
    data class UnsupportedSetting(val setting: String, val details: String? = null) : ModelWarning
    data class UnsupportedTool(val tool: String, val details: String? = null) : ModelWarning
    data class Other(val message: String) : ModelWarning
}
```
Carry `warnings: List<ModelWarning>` on the terminal `End` (and a stream-start). Each adapter populates it
when it drops a knob. Keeps the neutral options permissive while staying truthful about what each backend
actually applied.

### E6 · A thin `ModelDescriptor` over both spec hierarchies
`DownloadableSpec` is anemic (its only consumer is `DownloadController`). Lift the *thin* identity both specs
already have — **without** merging their bodies (a merge would resurrect the stray-nulls the sealed `ModelSpec`
split just killed):
```kotlin
interface ModelDescriptor : DownloadableSpec {
    val provider: ProviderId
    val modality: Modality
    val requiresDownload: Boolean get() = downloadUrl != null   // speech lacks this today
}
```
Implement on `ModelSpec` and `SpeechAssetSpec`; route `acquire`/availability/tier/`activeModelFor` through it.
Collapses the parallel resolution logic (the dual-spec asymmetry) and gives speech a `requiresDownload` it
currently can't express — while keeping `LocalLlmModel`/`RemoteLlmModel`/`SpeechAssetSpec` bodies split.
**Validated:** every framework shares a thin identity, none merges bodies.

### E7 · Capability-gated parameter drop
As remote providers multiply with uneven support, add a `ChatCapabilities`-driven "drop-or-warn unsupported
`GenerationConfig` fields" step before the codec (LiteLLM `drop_params` / OpenRouter `require_parameters`).
Pairs with E5: drop the field, emit an `UnsupportedSetting` warning. Aide already does the spirit of this by
hand in the `ToolChoice` KDoc; this generalizes it.

### What Aide already does better than all four frameworks (keep these)
- **Typed sealed media parts** (`ImageBytes`/`ImageFile`) — makes the bytes/path confusion *uncompilable*,
  vs Spring's `Object data` and LangChain4j's url-XOR-base64-in-one-type.
- **A richer `ToolChoice`** (`Auto`/`None`/`Required`/`Named`) with documented per-provider fidelity — vs
  LangChain4j's 2-value `{AUTO, REQUIRED}`.
- **`Flow<ChatStreamEvent>`** sealed-event streaming with a delta-only invariant — the idiomatic-Kotlin shape,
  cleaner than callback handlers.
- **`rawFinishReason` beside the normalized `StopReason`** — the "raw beside normalized" honesty Spring's
  `getNativeUsage()` and OpenRouter's `native_finish_reason` also keep.

---

## F. Sequenced plan + what not to do

**Phase 1 — correctness (do first; these are bugs, not design).**
`normalizeForWire` pass (collapses A3·A4·A5·A6·A7) · LiteRT String→Map + tool-call replay (A1·A2) · capture
image `mediaType` (A8) · cache image encode + size cap (A9). Pure correctness; ship-blockers.

**Phase 2 — hygiene (low-risk, clean-slate-friendly).**
Delete C1/C2/C3/C7 (confirmed dead) · rename `CapabilitySet → ChatCapabilities`, `GenerationConfig →
ChatGenerationConfig` (C5) · end the `Modality` name collision (C6) · fix the residency estimate (D1).

**Phase 3 — seam unification.**
Uniform stream terminal `End(outcome, final, warnings)` across the three streams (E2, B1·B2·B7) · neutral
`ReasonEvent` to break `domain.io → domain.usecase` (B5·B6) · de-STT `InputChannel` (B3) · fix the
`OutputChannel` KDoc / reconsider `IdentityOutputChannel` (B4).

**Phase 4 — fold in *with* the next provider/modality (never speculatively).**
`providerOptions` hatch (E4) when the first vendor knob lands · `warnings` channel (E5) · `ModelDescriptor`
(E6) when image-gen needs `activeModelFor(Image)` · `Data`/`Uri` + `supportedUrls` (E3) with the first cloud
image/asset consumer · capability-gated drop (E7) as remote providers multiply.

**Do not:**
- build a generic `Model<TReq,TResp>` umbrella (Spring proves it inert);
- merge `ModelSpec` and `SpeechAssetSpec` bodies (resurrects stray-nulls);
- make the gateway "chat-is-the-canonical-wire-shape" model the core (it privileges one modality; Aide's
  spine makes all six co-equal — image-gen is a *peer* `ImageProvider`, never a chat extension);
- add E3–E7 before their first consumer (ship-with-the-engine, exactly as OpenAI did in Phase 7).

The north-star was already right. Phase 1 fixes real round-trip corruption; Phases 2–3 pay down drift; Phase 4
is the modality-first design being *exercised*, not extended.
