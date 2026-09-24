# TODO — `:aisdk`

A Kotlin Multiplatform port of the Vercel AI SDK (`ai@7.0.85`, vendored read-only at
`third_party/vercel-ai`) that AIDE consumes in place of Koog. `:aisdk` knows nothing about AIDE. Design,
porting rules, every recorded divergence and every settled refusal: `aisdk/DESIGN.md`. This file holds
only open work.

**Status 2026-09-17 — the port is complete and current with upstream `662d7e5` (ai 7.0.102).** Every
phase, every parity batch, every provider, runtime and spec gap is landed and fixture-pinned, and the
2026-09-16 upstream delta (242 commits) is ported — decisions in `DESIGN.md`. Koog is gone from the build
(only `third_party/koog` remains, as reference). `make check` and `make test` are green: 2,612 tests across the four `:aisdk` suites, 5,983 across the build,
detekt at zero, ABI dumps committed.

## Open — needs a live key or hardware

- [ ] **Device gate (the one the port exists for):** a tool-calling conversation with extended thinking,
      ≥2 rounds and ≥2 turns across a session rebind, against Anthropic and Gemini plus one
      OpenAI-compatible endpoint. No `"Invalid signature in thinking block"`, no
      `"Function call is missing a thought_signature"`. Covers `AiSdkChatSession` over `streamText`.
- [ ] **Cloud speech and image on device:** dictation and read-aloud through OpenAI and ElevenLabs
      (endpointer hangover in a real room; 24 kHz PCM on both players); `GenerateImage` on OpenAI and Gemini.
- [ ] `AndroidKoinGraphTest` (`make device-test`).
- [ ] **Anthropic beta headers:** `a30f54e` dropped eight `anthropic-beta` values the tool reference marks
      `None`; one live request per tool says whether the old names were retired (a 400) or redundant.
- [ ] **Doc-derived wires, no recording yet (2026-09-16 additions):** OpenAI Live (`gpt-live-1`, server
      WebSocket session config and the 14 server frames — fixtures are the reference's inline tables, not
      a recorded session); Anthropic files `getFileMetadata`/`downloadFile`/`deleteFile` (from Anthropic's
      Files API reference — the vendored TS has no Anthropic counterpart); Bedrock partition endpoints
      (`bedrockBaseUrl`) and Mantle's refused web-search include; Gemini 3 `functionResponse.parts`
      inline files; Anthropic mid-conversation system messages and the `thinking-*` betas on Fable 5.1.
- [ ] **Doc-derived wires, no recording yet:** Perplexity Agent API (`/v1/agent`; retires the old wire
      2026-09-27 — AIDE side in `DEFERRED.md` §9); xAI batch field names and `/files` `purpose`; DeepSeek
      files under `/beta`; OpenAI skills endpoint and batch JSONL lines; OpenAI speech-translation
      subprotocol auth; Cartesia `cartesia_version` (`2026-08-14` documented, `2026-03-01` sent);
      Google Interactions `GET …?stream=true`/`last_event_id` and cancel; Gemini 2.5 penalties (we send,
      reference drops); `openai-compatible` speech/transcription (ours-only, no reference).

## Open — follow-ups the lanes surfaced (none blocks a consumer)

- [ ] Anthropic `mcp_toolset` entry: we send `name` + `mcp_servers` inside the tool entry; the MCP connector
      docs put `mcp_server_name` + `default_config` on the entry and `mcp_servers` at the top level.
      Bytes were frozen by the ABI-only lane; fix with a fixture.
- [ ] Anthropic web tools: `use_cache`, `response_inclusion`, `allowed_callers` are documented but not
      forwarded; `web_search_20260209+` defaults `allowed_callers` to code execution, so a model without
      programmatic tool calling 400s unless `["direct"]` is sent.
- [ ] `azure.deepseek`: real code in the reference (`azure-openai-provider.ts:258-268`), not configuration —
      an `AzureProvider.deepSeek(deployment)` composing `deepseek/` with Azure's `urlFor` + `api-key`.
- [ ] OpenAI function tools: `defer_loading` / `allowed_callers` / `output_schema` / `namespace` grouping
      (`prepareFunctionTool`) is not in `prepareTools`.
- [ ] DeepSeek per-part `imageDetail` / `fileData` — needs part-level `providerOptions` in the compat prompt
      converter.
- [ ] Google: `sharedRequestType` / `requestType` silently ignored where the reference warns; Vertex MaaS E5
      embeddings exist but their endpoint is undocumented, so none is declared.
- [ ] `util`: `ProviderHttp.getSse` (deletes the private GET-SSE copy in `GoogleInteractionsBackground.kt`);
      batch result downloads now stream through `getLines` but still refuse a self-hosted private-address
      server (the trusted-origin guard) — an opt-out is the fix when someone hits it; `MediaType.kt`'s PDF
      signature is five bytes where the reference's is four.
- [ ] GPT-6's effort vocabulary is parsed twice: `openaicompatible` `defaultCapabilities`
      (`supportedReasoningEfforts`) and `openai/OpenAIReasoningModels.enforcesEffortLevels` — one rule,
      two parsers; fold the compat side onto the OpenAI facts.
- [ ] `FileDownloadResult.mediaType` is null on OpenAI/xAI because `content` is a cold flow and the
      `Content-Type` arrives only on collection; produce the result after the response opens, or defer the
      media type (`DESIGN.md`).
- [ ] Anthropic `useJsonInstructionForStructuredOutput` (a system-prompt JSON instruction plus text
      extraction for models without strict tools) was never built; the JSON tool remains the fallback.
- [ ] `RunInclude` sheds request/response bodies AFTER every provider has serialized them; carrying the
      body lazily on `RequestInfo`/`ResponseInfo` (or threading `include` into `CallOptions`) would skip a
      second multi-megabyte string per round on image-heavy prompts.
- [ ] Spec: a `RealtimeClientFrame` (`Json`/`Text`/`Binary`/`None`) return type for `serializeClientEvent`
      (Cartesia's frames are binary and bare text); a `ResponsesQuirks.mapOutputItem` knob to fold
      `PerplexityLanguageModel` into a quirks value; `azure/AzureTools.kt` re-exporting `OpenAITools`.

## Invariants

`:aisdk*` depends on nothing of AIDE's (`:data:llm → :aisdk`, never back); commonMain only
(`portabilityCheck`); package mirrors module (`codeMapCheck`); detekt at zero, not baselined; `third_party/`
never compiled; every new behaviour pinned by a `TestServer` test with fixtures byte-for-byte from the
reference — or from the vendor's docs when the reference has no recording, and the fixture's KDoc says which.

```
make check     # both targets, desktop Koin graph, Lint, detekt, structural invariants, ABI check
make test      # every host test task in the build
make api-dump  # after a deliberate public-API change; commit the regenerated api/ dumps
```
