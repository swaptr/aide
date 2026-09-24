# Cloud speech and image providers — as built

Status: **shipped 2026-09-02.** This file used to be the ElevenLabs build plan (and, before 2026-08-18,
the image-generation plan). Both shipped on the `:aisdk` port, and the plan's hand-rolled Ktor clients,
`AuthScheme.XiApiKey` and `TtsOptions.modelId` were never needed — the SDK's provider already knows its
header, and the model comes from the same `activeModelFor(modality)` preference every modality uses. The
as-built description lives in [ARCHITECTURE.md](../ARCHITECTURE.md) §8 (speech) and §21 (image); this
page is the map from the plan's vocabulary to the code.

| Plan said | As built |
|---|---|
| ElevenLabs is a `SpeechProvider` with `vad = null`, no-op `load()`, availability = key present | Yes — `ElevenLabsAudioProvider` (`:data:llm/aisdk/AiSdkModalityProviders.kt`) contributes `SpeechProvider` and `AudioProvider` from one instance. Availability also checks the vendor row *serves* the modality, so an OpenAI-compatible base URL that lacks `/audio/*` is never picked for STT. |
| A hand-rolled ElevenLabs Ktor client | The `:aisdk` `ElevenLabsProvider`; the engines are `AiSdkSpeechEngine` / `AiSdkTranscriptionEngine` over the runtime's `generateSpeech` / `transcribe` wrappers. |
| Scribe realtime over WebSocket, batch as fallback | Batch only. `CloudSttEngine` (`:data:speech/cloud/`) buffers the 16 kHz mic, endpoints on an energy VAD (`Endpointer` over `VoiceActivityDetector`, 800 ms hangover, 30 s cap — Silero's numbers), and posts one WAV. It rethrows cancellation without a network call: the mic `SharedFlow` never completes and a stop-tap cancels the collector, so a NonCancellable upload would be billed and undeliverable. |
| `pcm_24000` TTS decoded to `TtsStreamEvent.AudioChunk` | Yes — `CloudTtsEngine` with a vendor-decided `CloudTtsFormat` (OpenAI `pcm`, ElevenLabs `pcm_24000`, Gemini `pcm`, all 24 kHz s16le), chunked at 40 ms for the same `AudioPlayer` path Sherpa uses. |
| Residency `NONE` via `ProviderDescriptor.local = false` | Yes, unchanged mechanism. |
| Key via `ProviderConfigRepository`, catalogs NOT on `SpeechProvider` | Yes. `CloudSpeechCatalog` and `ImageModelCatalog` are `:core:domain` ports over static tables, bound in `:di`, so the Add-model wizard lists them and `SpeechEngineRepositoryImpl` asks who owns the active model. |
| `ProviderId` constant + `ProviderDescriptor` row | `ProviderId.ELEVENLABS`, row "ElevenLabs". |

Also built beyond the plan: OpenAI and Gemini speech through the same adapters (each vendor's one
provider class contributes every capability it serves), Gemini image (`gemini-2.5-flash-image`) beside
OpenAI's, and the Auto-resolution rule — pinned provider wins; otherwise the vendor that owns the active
cloud model for a role goes first, then the platform ladder. A cloud vendor is never a silent fallback.

Deferred with reasons in [DEFERRED.md](../DEFERRED.md): desktop voice output (nothing plays TTS there
yet), a per-provider voice preference (the cloud engines speak with the vendor default until then).
