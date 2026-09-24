# Assistant architecture — logical layout of the abstractions

> **Terminology note (2026-08-18).** Written when the project used Hilt. Wherever this document says a
> "typed Hilt multibinding" (`Map<String, ChatProvider>` and friends) or `@IntoMap`, read: a **Koin
> contribution binding** collected into a named registry —
> `single { X() } bind ChatProvider::class` + `ChatProviderRegistry(getAll<ChatProvider>())`. The design it
> describes is what shipped; only the mechanism changed. `LayeringRulesTest` is likewise gone — the module
> graph enforces layering, and `./gradlew codeMapCheck` the package invariant. See ARCHITECTURE.md §2a.

North-star for Aide as a private-first, multi-provider, multi-modal Android **digital assistant**
where input, reasoning, and output are **independent, swappable options**. This lays out the
abstractions that already exist and the few non-redundant ones to add. Companions:
[ARCHITECTURE.md](../ARCHITECTURE.md) (the as-built reference) ·
[architecture-analysis.md](./architecture-analysis.md) (cross-modal/cross-provider bug audit + the
fundamental building blocks, benchmarked against Vercel/MS.E.AI/Spring/LangChain4j).

## The spine: two orthogonal classifiers

Everything keys off **`ProviderId`** (who serves a model) × **`Modality`** (what kind:
chat/asr/tts/vad/image/embedding), with **`CapabilitySet`** refining within a modality. A model is
`(provider, modality, id, capabilities)`. Input options, output options, the model layer, and tools
all hang off this one spine — both axes are open string value classes (Phases 1 & 3), so a new
provider or modality is additive, never an enum edit.

## The layered map

Everything in the map below exists today.

```
G  SURFACES        Chat · Keyboard(IME) · Assistant          each = a COMPOSITION of D+E+F
   ─────────────────────────────────────────────────────────────────────────────────────
F  TOOLS           AideTool · ToolBundle(Factory) · ToolDispatcher(idempotency·rate·confirm·verify·trace) · Surface
E  ORCHESTRATION   SendChatMessageUseCase · RunTaskUseCase · ChatSession loop · ChatTranscript
   ─────────────────────────────────────────────────────────────────────────────────────
D  IO CHANNELS     InputChannel{Text,Voice}   ·   OutputChannel{Text,Voice}   (the independence layer)
C  CONTENT IR      AideMessage/AidePart · ChatStreamEvent · GenerationConfig    (currency between D↔E)
   ─────────────────────────────────────────────────────────────────────────────────────
B  RUNTIME         Provider→{chat?,asr?,tts?,vad?} · ChatSession/Recognizer/Synthesizer/VadEngine
                   EngineRegistry + ResidencyManager (refcount across modalities)
A  CATALOG         ModelSpec(kind,caps,artifact) · CapabilitySet · ModelArtifact (speech via SpeechAssetSpec)
                   ProviderDescriptor · ModelRegistry · ModelGateState · activeModelFor(modality) prefs
   ─────────────────────────────────────────────────────────────────────────────────────
0  SPINE           ProviderId  ×  Modality          (open value classes)
```

## Independence = composition

A turn is three **independent, swappable** stages joined by the content IR:

```
  InputChannel<modality>     →     reason (model + tools)     →     OutputChannel<modality>
  ────────────────────────         ───────────────────────          ────────────────────────
  TextInput   typed → Text         activeModelFor(Chat)             TextOutput   stream → UI/transcript
  VoiceInput  mic→VAD→ASR          ChatSession + ToolDispatcher     VoiceOutput  sentence-split→TTS→AudioPlayer
              → Text/Audio part      → ChatStreamEvent
```

- Input option ⊥ model ⊥ output option. Voice-in+text-out, text-in+voice-out, audio-clip-in+
  voice-out — each just selects channels.
- The join is **`AidePart`**: `Text`, `ImageFile`, and `AudioFile` — audio-as-model-input now flows
  from `MicClipRecorder`/`VoiceInputChannel`; the `AudioBytes` raw variant still has no producer.
- Every stage resolves its engine through the SAME path: `activeModelFor(modality)` → `ModelSpec` →
  `Provider` → engine. Voice-in = the `Asr` slot, voice-out = the `Tts` slot, reasoning = the `Chat`
  slot. One resolution rule, every modality.

## Full reuse — existing types are load-bearing per layer

- **A Catalog** — `ModelSpec`(+`kind: Modality`), `CapabilitySet`, `ModelArtifact`, `DownloadableSpec`,
  `ProviderDescriptor`/`ProviderCatalog`, `ModelRegistryRepository`, `ModelGateState`(+`modality`),
  per-modality active-model pref slots.
- **B Runtime** — `Provider`, `LlmEngine`/`ChatSession`, `SpeechRecognizerEngine`/
  `SpeechSynthesizerEngine`/`VadEngine`, `LlmEngineRepository`/`SpeechEngineRepository`,
  `ProviderManagement`/`RemoteCatalogState`.
- **C Content IR** — `AideMessage`/`AidePart`/`AideRole`, `ChatStreamEvent`/`StopReason`,
  `GenerationConfig`, `ChatTranscript`/`ObservableChatTranscript`.
- **E Orchestration** — `SendChatMessageUseCase`, `RunTaskUseCase`, `ResolveActiveModelUseCase`,
  Load/Unload/Delete/SetDefault use cases.
- **F Tools** — `AideTool`(Function/ProviderNative), `ToolBundle(Factory)`, `ToolDispatcher`,
  `WriteConfirmGate`/`Verifier`/`IdempotencyCache`/`RateLimiter`, `Surface`(CHAT/IME/VOICE).
- **G Surfaces** — Chat = TextInput (+optional Voice dictation) + Chat model + TextOutput + tools(CHAT);
  Keyboard = field-Text-in + transform model + field-Text-out, no tools; Assistant = VoiceInput +
  Chat model + VoiceOutput + tools(VOICE). **Each surface is just a composition** of D+E+F.

No new model type, message type, tool type, or stream type is introduced.

## The only non-redundant additions

| Add | What | What it **consolidates** (why not redundant) |
|---|---|---|
| **`InputChannel`/`OutputChannel`** (D) | modality-typed, swappable IO stages | `VoicePipeline`'s hardcoded STT→LLM→TTS → `VoiceInputChannel → turn → VoiceOutputChannel`; `DictationController`+`DictationSink` → a `VoiceInputChannel` whose sink is a text field. Net **less** code. |
| **`ResidencyManager`** (B) — **DONE (Phase 5)** | one refcount manager across modalities | merges `LlmEngineRepository`+`SpeechEngineRepository` residency — a resident STT model and a resident LLM are the same RAM problem. |
| **`SpeechAssetSpec : DownloadableSpec`** (A) — **DONE (Phase 4)** | speech folded into the one catalog | kills the parallel speech spec/registry; ASR/TTS resolve through the same registry+gate as Chat. **Thin-core `ModelSpec`** (id/provider/kind/caps/artifact) + opaque per-modality `artifact` — do NOT widen the interface with LLM-only fields. |
| **Capability-interface `Provider`** (B) — **DONE (slice 4.2)** | base `Provider{id}` + typed role interfaces `ChatProvider{chat}` · `SpeechProvider{stt?,tts?,vad?,availability()}` · orthogonal `Manageable{management}`; SEPARATE typed Hilt multibindings `Map<String,ChatProvider>` / `Map<String,SpeechProvider>` / `Map<String,Manageable>` | Web-research-validated as the industry pattern (Vercel/Spring AI/LangChain4j/M.E.AI/SK all resolve per-modality typed interfaces BY TYPE; M.E.AI `OllamaApiClient : IChatClient, IEmbeddingGenerator` = the "one instance, several `@IntoMap` bindings" precedent). Only pattern giving BOTH compile-time type-safety AND Open/Closed. **Zero `filterIsInstance`/downcast** — each repo injects only its typed map. The fat-nullable merge was REJECTED. `NoOpProviderManagement` deleted (LocalProvider isn't `Manageable`). A multimodal vendor implements several capability interfaces (`by` delegation) + binds into several maps. |

## Gaps + patterns to borrow (from the OSS comparison), mapped to layers

- **MCP client** → F. **DONE** (`McpClientImpl`) — discovered MCP tools route **through** the governed
  `ToolDispatcher` (idempotency/confirm/verify), not around it.
- **`ToolChoice` policy** (auto/none/required/named) → F. **DONE** — neutral `ToolChoice` in
  `GenerationConfig`; OpenAI (`auto`/`none`/`required`/named) + Anthropic (`auto`/`none`/`any`/`tool`)
  map natively, Ollama enforces only `None` (drops the wire tools — its API has no `tool_choice`),
  LiteRT is auto-only. `Auto` omits the wire field, so tool-bearing requests are byte-for-byte unchanged.
- **`ToolCallDelta` accumulation** + raw-JSON-string tool-args → C. **DONE** — `RemoteToolCallAccumulator`
  assembles index-correlated SSE fragments (id/name on the first, `argumentsFragment`s accreting) into
  whole calls and tolerates compat-server quirks; pure + unit-tested without a socket.
- **Populate `Usage`** (declared, always-null today) + a **shared turn-runner** extracted from the
  duplicated per-provider tool loop → C/E. **DONE (Phase 6)** — `RemoteChatSession` is the single
  turn-runner behind all three remote codecs; `Usage` + `rawFinishReason` flow from every codec.
- **Per-message stats** → DONE — persisted on the message row (`MessageStats` → `messages` table); a benchmark surface was dropped as a non-goal.

Server-shaped gaps (RAG corpora, LiteLLM-style middleware/fallback, OTLP egress, eval harnesses)
are **deliberate** non-goals for the on-device privacy posture.

## Roadmap mapped onto the layout

| Phase | Layer(s) | Status |
|---|---|---|
| 1 open provider identity | 0 Spine, A | done |
| 2 registry folds over Provider.management | A | done |
| 3 domain Modality + per-modality slots | 0 Spine, A, C-gate | done |
| 4 speech joins the unified framework | A, B | done (Option-3: speech shares the spine — `ProviderId`×`Modality`×`DownloadableSpec` — not one fat spec/registry) |
| 5 ResidencyManager | B | done |
| 6 remote transport core + stream/tool extensions | C, E, F | done — turn-runner (`RemoteChatSession`), `ToolChoice`, `ToolCallDelta` accumulation, `Usage`+`rawFinishReason` all shipped across Ollama/OpenAI/Anthropic; `extras` + thinking-signature round-trip stay deferred (no consumer yet) |
| 7 first new provider end-to-end | 0–B | done (OpenAI-compatible — OpenAI/OpenRouter/Groq/vLLM/LM Studio; data-driven provider UI) |
| 8 IO Channels | D | done — `InputChannel`/`OutputChannel`{Text,Voice} in `domain/io` + `data/speech/io`; surfaces are compositions |
| MCP client | F | done — routed through the `ToolDispatcher` (`McpClientImpl`) |

## Forward-looking peer contracts (designed, not yet built)

The abstraction is **modality-first**: each modality is a peer capability interface resolved BY TYPE
(no fat interface, no downcast), with the mechanical engine (LiteRT `Conversation`, Ollama/OpenAI
OkHttp, Sherpa ONNX, Android `SpeechRecognizer`/`TextToSpeech`, future ElevenLabs HTTP/WS, future
image HTTP) **sealed behind it** and converted at a message/event seam (`LayeringRulesTest` keeps
`domain/` free of every mechanical type). Validated against Vercel AI SDK / Spring AI / LangChain4j /
Microsoft.Extensions.AI — all obey the same law: *transport-free contract ← mechanical adapter
(HTTP/runtime as a constructor dependency) ← thin aggregator (one provider → many modality bindings)
← write-once middleware*. **OpenAI (Phase 7) proved the cloud-chat path needs ZERO contract change** —
a remote engine implements the same `LlmEngine`/`ChatSession` as on-device LiteRT. Two peers are
designed but deferred until a real consumer lands (building speculative single-impl interfaces =
untested dead code; they ship WITH their first engine, exactly as OpenAI did):

> **Build plan:** [image-and-cloud-speech-providers.md](./image-and-cloud-speech-providers.md) — a
> concrete, slice-by-slice TODO for both, grounded in the contracts below (additive seams, file
> paths, the OpenAI/Sherpa precedents to copy, decision/risk tables).

### Image — a new peer of Chat/Speech (when an image provider lands)
`ImageProvider { image: ImageEngine }` bound into a typed `Map<String, ImageProvider>` (its own
`@IntoMap`, like `ChatProvider`/`SpeechProvider`). `ImageEngine.generate(prompt, opts): List<ImageResult>`
where **`ImageResult = sealed { Bytes(bytes, format) | Url(url) }`** (gpt-image-1 returns base64 bytes
only; DALL·E/others return URLs). `edit(images, prompt, mask)` + `variations` gated by a capability
flag (dall-e-2-only). MECHANICAL (per-engine): base64-decode vs URL-fetch, `multipart` (edits) vs JSON
(generate), `Authorization` injection, `size` (OpenAI) vs `aspect_ratio` (Stability), `partial_images`
streaming. `Modality.Image` + `CapabilitySet.visionIn` already exist; no spine change.

### Cloud speech — ElevenLabs ("cloud Ollama for voice"), the SAME `SpeechProvider`
The on-device speech contract (`SpeechRecognizerEngine`/`SpeechSynthesizerEngine`/`VadEngine` over
**16 kHz mono PCM-float** — the cross-engine audio currency) already hosts a cloud engine; a cloud
speech provider needs three ADDITIVE bits in the options, not a new interface: **opaque `voiceId`/
`modelId`** (remote catalog via `listVoices()`/`listModels()`, vs OS-enum / model-file), **auth +
`baseUrl`** (already in the keyed `ProviderConfig` — Phase 1), **sample-rate / streaming caps**.
`Flow`-of-chunks stays the streaming shape (batch engines — Android `TextToSpeech` whole-utterance,
ElevenLabs Scribe batch — degrade to a one-element flow). MECHANICAL: codec (mp3/opus/μ-law/base64),
HTTP-chunked vs WebSocket frames vs `AudioTrack`/`AudioRecord`, `optimize_streaming_latency`,
`RECORD_AUDIO`/`RecognizerIntent`, native init listeners. ElevenLabs = a `SpeechProvider` (tts +
Scribe stt) bound into `Map<String, SpeechProvider>` — **zero change to on-device Sherpa/Android**.
