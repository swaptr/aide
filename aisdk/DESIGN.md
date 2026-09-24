# `:aisdk` — design

A Kotlin Multiplatform port of the Vercel AI SDK's provider layer. **This module knows nothing about
AIDE**: no `ProviderId`, no `ChatProvider`, no `ModelSpec`, no Koin, no `:core:*`. That is not
fastidiousness — it is the property that makes "runs anywhere" true, and it makes AIDE merely the first
consumer rather than the owner.

The dependency arrow is `:data:llm → :aisdk`, never back. `dependencyDirectionCheck` enforces it.

## Why port at all

Koog types reasoning as a display string. Anything a vendor emits that Koog did not anticipate is
therefore dropped on the floor, and everything AIDE cares about lives in that gap:

| Symptom | Upstream |
|---|---|
| Anthropic streaming drops the thinking `signature` | [koog#1959](https://github.com/JetBrains/koog/issues/1959), open since 2026-06-02; fix [#2010](https://github.com/JetBrains/koog/pull/2010) unmerged 13 weeks |
| Gemini `thoughtSignature` never surfaces; replay survives only on an injected magic string | verified in 1.1.1 bytecode |
| OpenAI-compat drops `reasoning_content` entirely | hence AIDE's `ReasoningTagSplitter` |
| OpenAI-compat drops Gemini 3 `thought_signature` → HTTP 400 | [koog#2113](https://github.com/JetBrains/koog/issues/2113) |

This is a modelling failure, not a bug backlog, and every LLM abstraction in every language has an open
issue for the same thing — [vercel/ai#11602](https://github.com/vercel/ai/issues/11602),
[pydantic-ai#2293](https://github.com/pydantic/pydantic-ai/issues/2293),
[openai-agents-js#770](https://github.com/openai/openai-agents-js/issues/770), Bifrost #3688. Signed
reasoning replayed across tool rounds is the industry's hardest edge.

## The one idea worth importing

`ProviderMetadata` — `Map<String, JsonObject>`, keyed by provider id — carried on **every content part
and every stream part**.

The specification models no vendor payload at all. A provider attaches whatever it likes under its own
namespace; the runtime carries it back verbatim. Nothing in between understands it, which is exactly why
nothing in between can lose it.

```kotlin
Content.Reasoning(
    text = "Let me check the calendar…",
    providerMetadata = mapOf("anthropic" to buildJsonObject { put("signature", "Er4BCkYIBRgCKkA…") }),
)
```

The second idea, nearly as important: **blocks are delimited, not inferred.** Text, reasoning and tool
input each stream as `*Start` / `*Delta`… / `*End` sharing an `id`. AIDE's current seam hangs a signature
off a text event and treats empty text as a terminator by convention — which cannot express two
interleaved blocks, and has nowhere to put a payload that arrives after the text it belongs to.

## Porting rules

| AI SDK (TS) | Kotlin | Why |
|---|---|---|
| `LanguageModelV4*` prefix | dropped | Kotlin has packages; `SPECIFICATION_VERSION` keeps the version |
| `{ type: 'text' }` union | `sealed interface` + `@SerialName` | exhaustive `when`, no string compare |
| `Record<string, JSONObject>` | `Map<String, JsonObject>` | the load-bearing type |
| `JSONSchema7` | `JsonObject`, passed through | never parsed, never validated — a schema is cargo |
| `doStream(): ReadableStream` | `Flow<StreamPart>` | cold; collection issues the request |
| `PromiseLike<T>` | `suspend fun` | — |
| `AbortSignal` | coroutine cancellation | parameter deleted; cancelling the collector cancels the call |
| `Uint8Array` | `ByteArray` | with `contentEquals`/`contentHashCode` on data classes |
| `Date` | `Long` epoch millis | avoids a datetime dependency this module does not otherwise need |
| `undefined` vs absent | nullable, default `null` | **null means omit the field**, never "send the default" |

Two deliberate divergences, both to avoid shadowing inside one package: `ToolOutput.content` is
`ToolOutput.Multipart`, and the reference's bare `source` variants are `Content.Source.Url` /
`Content.Source.Document`.

## What is serializable, and why the split

`Prompt` and `Content` (and `Usage` / `FinishReason`) are `@Serializable` with the reference's own
discriminators (`reasoning`, `tool-call`, `error-text`, …). `StreamPart` and the result types are not —
they hold a `Throwable` and a `Flow`, and they are in-memory only.

The line is not aesthetic. A consumer must be able to persist an assistant turn and replay it verbatim
several turns later, because that is exactly what Anthropic requires of a signed thinking block. The
types that get stored are serializable; the types that only ever exist mid-call are not. The reference
does not serialize its spec types at all — TS keeps them purely in-memory — so this is a deliberate
divergence in the port's favour.

`SpecSerializationTest` is the guard: it asserts a signature, a `redactedData` payload, assistant part
order, and a payload from a provider this module has never heard of all survive a round trip unchanged.

## Divergence: absence is `null`, not a throwing stub

`ProviderV4` declares `languageModel`, `embeddingModel` and `imageModel` as required members, so a vendor
with no image models must supply one that throws. That is a stub whose only job is to fail, and it makes
"does this provider do images?" unanswerable without calling it and catching.

Here every modality defaults to `null` — *this provider offers none of that kind* — and the two failure
modes stay distinct:

| | meaning |
|---|---|
| `imageModel("anything") == null` | this provider does not do images. Not an error. |
| `languageModel("typo-4o")` throws `NoSuchModelError` | it does language models; that is not one. |

A provider that offers a modality must therefore throw rather than return null for an unknown id.
`ProviderContractTest` pins both halves. This also matches the repo's own standing rule: a capability a
target lacks is bound nowhere, never a stub that throws.

The error hierarchy drops the reference's `Symbol.for(...)` markers and static `isInstance` — a JS hack
for `instanceof` failing across two copies of a package at different versions. Kotlin has one class and
`is` works. `errorName` is kept, matching the reference's strings, so telemetry stays comparable.

## Gotcha: `encodeDefaults = false` silently drops required fields

`ProviderJson` sets `encodeDefaults = false`, which is right for optional fields — several vendors reject
an explicit `null` where they accept an absent key. It is a trap for REQUIRED ones: a field left at its
Kotlin default is omitted from the wire entirely.

`AnthropicThinking.type` had a default of `"enabled"`, so every extended-thinking request would have gone
out as `{"budget_tokens": …, "display": "summarized"}` with no `type` — a 400 on every call. AIDE's
original wire model was fine because it serialized with `encodeDefaults = true`; the bug appeared only
when the model moved under this module's stricter JSON.

**Rule: a request wire model must never rely on a Kotlin default for a field the server requires.** Give
it no default and let the call site state it. `AnthropicThinkingConfigTest` catches this class of bug by
asserting against the serialized body rather than the Kotlin object.

## Verified against Anthropic's docs, not against assumptions

Re-checked on 2026-08-26 against the [per-model configuration
table](https://platform.claude.com/docs/en/build-with-claude/thinking-troubleshooting). Three defects in
the logic carried over from AIDE, each of which is a 400 or a lost capability:

| Finding | Was | Now |
|---|---|---|
| Opus 4.5 is the ONE extended-only model that supports `output_config.effort`; the docs say to set it alongside `budget_tokens` | effort gated on `!extended`, so Opus 4.5 lost its only control beyond the budget | `supportsEffortInExtendedMode()` |
| Thinking between tool calls on 4.5-and-earlier needs the `interleaved-thinking-2025-05-14` beta header | never sent; a 4.5-era model thought once per turn and never reasoned about a tool result | sent when extended **and** tools are present |
| `usage.output_tokens_details.thinking_tokens`, final `message_delta` only | not parsed; `Usage.outputTokens.reasoning` always null | parsed, with the text share derived |

Confirmed correct as carried over: the 1024 `budget_tokens` minimum and the below-`max_tokens` rule;
`effort: "high"` being the API default and therefore droppable; `display: "summarized"` being necessary
because newer models default to `"omitted"` and return signatures with no text; the always-on family
(Fable 5, Mythos 5, Mythos Preview) rejecting `"disabled"`; 4.7+ rejecting `"enabled"`; ≤4.5 rejecting
`"adaptive"`.

## Gotcha: Kotlin block comments nest

A literal slash-star inside KDoc — writing a wildcard media type like `image` slash star — opens a
nested comment that the closing delimiter then fails to balance, and the file stops compiling several
declarations later with a misleading `Missing '}'`. Spell such patterns out in prose. This cost one
build; it is documented so it costs no more.

## What is deliberately NOT ported, and why

Every omission here is a case where a guessed wire model would compile and then fail against the live
service — which is strictly worse than an absent provider, because the failure arrives at runtime in
someone's app rather than at the type level in ours.

**Vercel AI Gateway's native protocol.** Gateway does not speak Chat Completions. It posts the AI SDK's
own call options as the request body, with the model in an `ai-language-model-id` header, and streams
back the SDK's own stream parts — its wire *is* the specification, serialized in Vercel's private JSON
shape. Reproducing that means byte-matching a format no public document pins down, and it cannot be
verified without a live Gateway account. Its documented OpenAI-compatible endpoint is used instead
(`Vendors.vercelGateway`), which is reachable and testable.

That the spec is transportable at all is worth noting: it is the strongest evidence that
`providerMetadata` and the id-correlated stream parts are a real interchange format rather than an
internal convenience.

**`prodia`** — *resolved, and worth recording why the reasoning was wrong.* This entry said the job
endpoint's MULTIPART response needed a parser "worth building only when a second vendor needs it too".
Two things were off. The parser is ~80 lines of byte scanning over a boundary (`MultipartResponse.kt`),
not a transport feature — it needs nothing from Ktor, because `postBytesForBytes` already hands back the
body and the `content-type`. And the second-consumer rule is for *shared abstractions*, not for the one
concrete thing standing between a vendored reference and a working provider. All three models are
ported; the language and video models' image INPUT rides a multipart request (`postMultipartForBytes`,
2026-09-02), which was the last piece.

`bytedance` and `gmicloud` turned out NOT to need their own wire models. Reading the reference rather
than guessing showed gmicloud simply extends its OpenAI-compatible chat model and ByteDance's image
endpoint is `/images/generations` returning `data[].b64_json` — both are table entries in `Vendors.kt`.
MiniMax likewise serves an Anthropic-shaped API, so it is `AnthropicProvider` pointed at another base
URL. Three vendors that looked like new wires were three configurations of wires already here.

**Realtime / WebSocket models** — *resolved.* `ProviderSocket` is the transport, and the `RealtimeModel`
contract is served by xAI, OpenAI, Google and Cartesia. What the port learned is in the 2026-09-02
decisions below: the contract's "no session state" is a default, not a law, and its `JsonElement` client
frame is a carrier for the two vendors whose frames are not JSON.

**Azure's `azure.deepseek` variant** is real code, not configuration: the reference builds a DeepSeek
chat model over Azure's URL and `api-key` auth with thinking disabled
(`azure-openai-provider.ts:258-268`, four fixture-backed tests). Unported; it would be an
`AzureProvider.deepSeek(deployment)` composing `deepseek/` with Azure's `urlFor`, and it is tracked in
`TODO.md`.

## Documented divergences that are decisions, not drift

Each of these was flagged by the 2026-08-31 parity audit and then deliberately kept. They are the
answer to "why doesn't this match the reference?" — re-aligning any of them is a regression, not a
conformance fix.

- **Retries live in the transport, not the runtime.** `ProviderHttp` retries retryable failures
  (2 by default, `Retry-After` honoured) inside every provider; `streamText`/`generateText` and the
  modality wrappers therefore default to `RetryPolicy.None`. The reference puts its default-2 retry
  in the runtime and none in the providers — same end-to-end behaviour, opposite layer. Making the
  runtime default 2 as well would compound to up to nine attempts per call.
- **`Agent.stopWhen` defaults to one round**, not the reference's twenty. An unbounded tool loop is
  not something to opt out of; a saved-prompt agent that needs more rounds says so.
- **`IdGenerator`** uses a 35-char lowercase alphabet and no separator (the reference: 62 mixed-case
  plus `-`). Ids here are correlation keys, not security tokens; the shorter alphabet keeps them
  copy-paste-safe in logs.
- **`getErrorMessage` prefers `message` over `toString()`**, and `JsonParseError` truncates the
  offending body to 500 chars — an error that quotes a 2 MB response is its own failure.
- **`MistralProvider`'s embedding model stays native** rather than folding into the compat one: it
  deliberately does NOT re-sort `data[]` (Mistral returns submission order; the reference does not
  sort either), caps at 32 per call, reports `supportsParallelCalls() = false`, and reads camelCase
  option names. The compat model gets none of that without growing four knobs.
- **Kling serves video only.** The reference explicitly refuses an image model
  (`klingai-provider.ts:122`); the one this port briefly carried was written from documentation
  alone and was removed as an unverifiable surface. It returns only against a verified live wire.
- **Anthropic-family options namespaces are OUR provider ids** (`amazon-bedrock`, `google-vertex`,
  `minimax`), not the reference's literals (`bedrock`, `googleVertex`): a caller files options under
  the id the provider it constructed reports. Canonical `anthropic` is always read; the custom key
  wins field-by-field.
- **`jsonSchemaInstruction` merges into an existing leading system message** with the caller's prose
  first — the reference's ordering — rather than prepending a second system turn.

## Decisions from the 2026-09-01 parity sweep

- **One Responses model, parameterized — not one class per vendor.** Azure, xAI, HuggingFace and
  `open-responses` all speak OpenAI's Responses wire with small deltas, so provider id, options
  namespace, endpoint, error structure and a `ResponsesQuirks` value are constructor parameters. The
  namespace rule is the Anthropic-family rule from above: the vendor's map overlaid field-by-field on
  the canonical `openai` map, custom winning. Plain OpenAI's bytes are unchanged, which is what the
  untouched OpenAI tests pin.
- **Approval is a runtime concern with a wire-visible record.** `Tool.Function.needsApproval` declares
  a tool's own default; a caller's `ToolApprovalPolicy` outranks it. Policy deciding for itself still
  writes a request/response pair (`isAutomatic = true`) so a transcript shows the gate ran rather than
  showing tools that silently did not.
- **An approval request is an `AssistantPart`, because approvals outlive processes.** A human takes
  minutes; the conversation is persisted and the answer arrives against a rebuilt runtime. Without a
  prompt-side part, a stored conversation comes back holding a call, no result and no record that
  anything was asked — indistinguishable from a truncated turn, and rejected as one. `standardizePrompt`
  therefore exempts a call whose approval is unsettled, and the loop settles what it can before the
  first model call, because the model already asked and is owed the result rather than another turn.
  Four outcomes, and the last two are what keep a run from dead-ending: answered yes runs the call;
  answered no writes the denial; unanswered but this run HAS a handler asks it (a host that stored a
  question and came back wired up should be consulted, not handed its own question back forever); and
  unanswered with a later user turn is treated as abandoned — the human answered by doing something
  else, so the call is denied and the new message gets processed. Only what is genuinely still out
  stops the run, which makes bare re-entry idempotent rather than destructive.
- **Approval signatures are HMAC over canonical JSON.** Between the ask and the answer the conversation
  sits in storage this library does not control, so a `yes` that has been moved onto a different call is
  the failure to detect. Keys are sorted before digesting so a re-serialization does not invalidate a
  genuine approval; verification failure DENIES rather than throwing, because a refusal the model can
  read beats an exception that ends the conversation.
- **Compaction is a caller's decision applied at a step boundary.** `pruneMessages` is a pure function;
  `StepPlan.messages` is where its output takes effect, and the rewrite is re-validated exactly like a
  caller's prompt — a prune that drops half of a call/result pair should fail at the round that made it,
  not as a vendor 400. `PruneReasoning.BeforeLastMessage` exists because the newest reasoning is the
  half a still-open tool round requires replayed.
- **A vendor tool reaches the wire through a dialect, never a branch.** Two seams, one rule:
  `ProviderToolDialect` on the compat chat model and `ResponsesQuirks.providerToolNames` /
  `providerToolBodies` on the Responses model. A vendor that declares its OWN tool table is
  authoritative for it — xAI serves `xai.*` and nothing of OpenAI's, so inheriting OpenAI's table
  would send `local_shell` to an endpoint that has never heard of it, a 400 in place of the warning
  the caller should have got. A vendor that declares NO table is hosting OpenAI's own surface (Azure),
  and gets OpenAI's. Argument renames are per-tool tables rather than a recursive camelCase pass,
  because an MCP tool's `headers` keys are HTTP header names the caller chose and a generic pass sends
  `contentType` where the server expects `Content-Type`.
- **A refined error is a SUBTYPE, never a sibling.** `NoImageGeneratedError` and the rest extend
  `NoContentGeneratedError`, as do the runtime's `NoTranscriptGeneratedError` and
  `NoTranslationGeneratedError`. The distinct type is what lets a caller catch one modality's silence
  specifically; subclassing is what stops the refinement from silently un-matching a `catch` that was
  already correct. The reference makes them siblings, which is safe there only because it never threw
  the base from those paths.
- **A tool sees the conversation, not a context bag.** `ToolCallContext` carries the round's messages
  and index. The reference also threads an abort signal and a caller-supplied context object; the first
  is coroutine cancellation, and the second is what a Kotlin closure already captures, with types.

## Decisions from the 2026-09-02 parity lanes

Thirteen file-disjoint lanes closed the batch plan (`TODO.md` §3–§6). Each divergence below was checked
against the vendor's live documentation where a page existed; "reference" means `ai@7.0.85`.

**Runtime**

- **`allowSystemInMessages` defaults `true`.** A stored conversation arrives with its system turn in the
  message list; the reference's `false` is for prompts built from scratch. `standardizePrompt` takes it
  as a parameter, so a caller that wants the strict rule says so.
- **`RunInclude` bodies default off** (request body, response body, raw chunks) — reference parity, and
  nothing in `:data:llm` reads `Step.request`/`response`.
- **`getBatchResults` is a cold flow with no retry policy.** A retry wrapped around a partially delivered
  flow re-emits the items already handed out; the caller retries the whole collection if it wants to.
- **`WarningLogger` is a value handed to the run**, not the reference's process-global `console.warn`
  switch; `RunTimeouts.tools` is keyed by tool name, not the reference's `{name}Ms` spelling.
- **`onAbort` fires for any collector cancellation**, including a downstream `take()`/`first()` — it
  means "the collector went away", which is the only thing the runtime can observe.
- **Stream error normalization is true by construction.** Every provider emits `APICallError` (status,
  retryability, data) on `StreamPart.Error`, so the reference's `normalize-stream-provider-error` has
  nothing to rewrite. A mid-stream error is not retried by the loop unless the caller sets `streamRetries`
  (ported from upstream `802af1e` on 2026-09-16: isolated per-round attempts, at most one
  callback-directed recovery, results from the successful attempt only); by default the reference and
  this port both retry only the call that opens the stream, pinned by `StreamErrorNormalizationTest`
  and `StreamRetriesTest`.

**Tool tables and the Responses wire**

- **A vendor's tool table is derived from its factories.** `OpenAITools`, `AnthropicTools` and
  `GoogleTools` are `public object`s of `ProviderToolFactory` values; `prepareTools` reads the id→wire
  table FROM them, so there is one place a tool's id, wire name, betas and argument spellings live. The
  bytes on the wire did not change (the existing request tests pin them). The `registered` + `register()`
  shape in `AnthropicTools` — object init order is the registration order — is the one to copy.
- **An Open Responses extension is consulted before any vendor table.** A namespaced `ns:kind` item or
  event is decoded only to `{type, id, status}` / `{type, sequence_number}` plus the raw object, because
  an extension's `result` object collides with the typed `OpenAIOutputItem.result: String` and one such
  element would fail the whole `output` array. Extension callbacks are nullable suspend properties
  (presence = capability), the reference's own shape.
- **`code_interpreter` with no container sends `{"container":{"type":"auto"}}`.** OpenAI's reference
  lists `container` as required and the reference SDK always sends it; the old omission was a live 400,
  so this is a fix rather than a wire change to a working request.
- **Batch results decode through the live response mapper** (OpenAI, Google), because `Batches.kt`
  promises "exactly what a live `doGenerate` would have returned" and the Anthropic batch already kept
  it; the reference fails tool calls and provider-run items in a batch as `unsupported_content`. xAI is
  the exception by evidence: it stores every text-batch result as a **Chat Completions** document even
  for `/v1/responses` lines (its batch guide says so), so its results go through a small Chat
  Completions reader while the request half reuses the Responses builder.
- **Perplexity is a package on the Agent API** (`POST /v1/agent`; `/v1/responses` is documented as an
  alias for the OpenAI SDK's hard-coded path), built on the parameterized Responses model, with
  `PerplexityTools` as its dialect, `preset` in place of `model` when the id names one, vendor output
  items kept whole as `Content.Custom("perplexity.<type>")` plus de-duplicated `Source.Url`s, and
  `usage.cost` under `providerMetadata.perplexity` and `Usage.raw`. Its fixtures are **doc-derived, not
  recorded** — the KDoc says so — because the reference still speaks the Chat Completions wire that
  retires on 2026-09-27. The Chat-Completions-side `images`/`cost` reads were never added: three weeks
  of life is not worth a wire.
- **Groq follows Groq's docs where the reference guesses:** `Minimal` effort becomes `low` (Groq
  documents no `minimal`), `json_schema` goes only to the four models its structured-outputs page lists,
  and `none` is never sent. Root `usage` is read as well as `x_groq.usage`.
- **The Completion modality reports provider id `<id>.completion`** and is enabled on exactly the five
  rows whose reference serves it (openai, azure, fireworks, togetherai, deepinfra). An error frame before
  any output is a `StreamPart.Error` plus an error finish, not the reference's fabricated 500.

**Realtime**

- **A realtime mapper may hold session state when the protocol forces it** — Gemini frames carry no
  ids, so response and item ids come from a per-socket counter; Cartesia's chosen endpoint decides the
  commit frame and whether a `connected` frame will ever arrive. One instance per socket, said in the
  KDoc of each. The spec's "no session state" is the default, not a law.
- **`serializeClientEvent`'s `JsonElement` is a carrier**, not a claim that every client frame is JSON:
  Cartesia's audio is a binary frame and its commit is the bare word `finalize`, so the provider keeps a
  typed `CartesiaClientFrame` as the truth and renders the neutral method from it. A spec-level
  `RealtimeClientFrame` (`Json` / `Text` / `Binary` / `None`) is the eventual fix; it waits for a second
  vendor with the same need.
- **Socket URLs derive from the configured base** (`{base}/realtime`, `{base minus version}/ws/…`)
  rather than the reference's hard-coded hosts — identical bytes on the default base, and a gateway's
  path prefix survives. Realtime `providerOptions` are namespaced under the provider id like every other
  option here; the one documented exception is Google's Live setup, onto which non-`google` keys are
  spread raw because the reference pins exactly that.
- **OpenAI's realtime subprotocols are `realtime` and `openai-insecure-api-key.<token>` only** — the
  `openai-beta.realtime-v1` entry was beta-era and is not sent.

**Subproviders and the compat engine**

- **A Vertex bearer token is resolved per request by wrapping an immutable compat provider**
  (`VertexBearerCompat`): the token rotates hourly, `OpenAICompatibleProvider` takes static headers by
  design, and a suspend-header knob in the engine would have been a second way to do one thing. The
  Vertex xAI usage arithmetic (`completion_tokens` + `reasoning_tokens`) matches Google's own Grok
  reasoning page, which is what validated the `convertUsage` seam's shape. MaaS extracts `<think>`
  inline reasoning because Google documents that DeepSeek R1 on MaaS puts it in `content`.
- **Bedrock Mantle is a plain OpenAI-compatible wire** (`https://bedrock-mantle.{region}.api.aws/v1`,
  Bearer API key or SigV4 in the `bedrock-mantle` service scope) — a wrapper class over the compat
  provider whose SigV4 is a Ktor `HttpSend` interceptor, never a `Vendors` row; the auth is an explicit
  sealed choice rather than the reference's key-then-credentials fallback.
- **Bedrock Nova Canvas sends `seed: 0`** (the reference drops falsy seeds; Nova documents 0 as valid),
  reads options under `amazon-bedrock` then the legacy `bedrock`, and surfaces the rerank `nextToken`
  on `providerMetadata`.
- **DeepSeek file parts are flat** (`{"type":"file","file_id":…}`, per DeepSeek's docs), applied through
  the compat engine's `transformRequestBody` seam rather than a converter branch; DeepSeek's images are
  pre-flighted (media type, `image_url` length) the way its reference converter does.
- **Files, skills and batches report the provider's own id** (`openai`, `xai`, `deepseek`), not the
  reference's `openai.files`-style sub-ids; a `FileData.Reference` is keyed by the id the language
  model files its metadata under.

**Google Interactions**

- **The background POST is cold** — it happens on collection, because a built-but-uncollected stream
  must not start (and bill) an agent run; the reference posts eagerly inside `doStream`.
- **`doGenerate` polls for agents AND for `background: true` model calls**; the reference polls only
  for agents and would hand back an `in_progress` shell for the other case.
- **The neutral `ReasoningEffort` drives `thinking_level`** and, when it does, switches
  `thinking_summaries` to `auto` unless the caller chose; `None` warns, since `minimal` is Google's
  floor. `provider` is `google.interactions`; options and metadata file under `google`, as the reference
  does. Thought blocks are resent exactly as received — the same rule the classic model keeps.
- **A multi-megabyte fixture is truncated at a valid prefix, everything else verbatim** (the
  `image-output` pair: a 24-char JPEG header, a 64-char signature). The rule keeps the byte-for-byte
  discipline honest without a 6 MB test file.

**Spec**

- **Prompt-side `AssistantPart.ToolCall.input` stays a `String`** where the reference holds the parsed
  value (`ToolCallPart.input: unknown`) and a string only on the output side. Ours is the same string on
  both sides: Chat Completions `function.arguments` and Responses `function_call.arguments` ARE strings,
  and replaying the model's own bytes verbatim is the only way to be certain the model sees what it
  said; the object wires parse at their boundary (Anthropic's `input` with a `{}` fallback, Google's
  `args` omitted when it will not parse), and the runtime replays an `invalid` call's arguments as `{}`
  before either sees them. A parsed type could not carry the raw text of an unparseable call back to
  the model. The porter's trap is named in the KDoc.
- **`TypeValidationError` carries a `TypeValidationContext`** (`field`, `entityName`, `entityId`) with
  the reference's prefix rule, and `wrap` returns the cause unchanged when it is already the same
  error for the same value and context — so a runtime that re-validates does not nest messages.
- **Video webhooks:** `generateVideo(webhook = …)` prefers the async start/status flow only when the
  factory is given AND `model.supportsWebhooks`; a model that does not support them gets the
  reference's warning and polls, and a synchronous model answers directly with the factory never
  invoked (the reference would still take the async path). A webhook that never arrives throws the
  polling flow's `JobTimeoutError` rather than a plain error, so a caller has one timeout type.

**From the review pass (2026-09-03)**

- **Batch result files stream line by line** (`ProviderHttp.getLines`, the streaming half of
  `getBytes` under the same redirect and origin guard): a results file is one response per line and
  can run to hundreds of megabytes, and reading it whole held three copies before the first item.
- **A collector's exception is never a transport error.** Both GET-stream verbs (`getLines`, the
  Interactions `getSse`) mark the frames they hand downstream, so a failure raised by the consumer
  passes through untouched instead of being wrapped as a retryable `APICallError` and reconnected —
  which re-delivered frames to a consumer that had already failed. The Interactions reconnect loop
  also retries only a retryable failure now, and never a 401 or 404.
- **An Interactions frame is parsed once**, in the reconnect loop that reads its event id, and handed
  on parsed; an image delta is megabytes of base64 and was being tree-parsed twice.
- **A provider-executed tool call is not persisted as a client call** (`AideStepMapping`): it was run
  and answered by the vendor inside the turn, and stored unanswered it would be given a synthetic
  failure and replayed as a tool round the vendor never asked for.
- **The Responses stream uses the streaming decoder first** and the tree-building transforming decoder
  only for an extension frame (a namespaced `type`, or an object-valued `delta`), so a text delta no
  longer builds a `JsonElement` it never reads.
- Smaller: `generateVideo` validates the clip count before standing up the caller's webhook listener;
  the metrics recorder publishes nothing when a delta changed nothing; Cartesia renders the base64 an
  audio event already carries instead of decoding and re-encoding it; DeepSeek's body transform returns
  the original body when no file part needed flattening; Gemini grounding metadata is decoded once per
  distinct object; the Interactions prompt merges adjacent text in one builder; Google's batch size
  check counts UTF-8 without allocating the bytes.

**Prodia**

- A prompt-supplied image URL goes through the SSRF guard (the reference's language model fetched it
  bare); a second image in one turn is kept-first with a warning; `frameImages` warns and runs
  text-to-video rather than throwing.

## Decisions from the 2026-09-16 upstream update (`e1bfe50` → `662d7e5`)

Two hundred and forty-two upstream commits (ai 7.0.85 → 7.0.102, provider 4.0.9 → 4.0.15) ported by six
file-disjoint lanes. What is recorded here is where the port decided something the diff did not.

**Spec and utilities**

- **`BatchLanguageModel` keeps its name** where upstream renamed the interface to `BatchV4`; `modelId`
  stays the per-request DEFAULT (a `BatchRequest` may name its own), and batch-wide warnings are
  attributed to that model rather than to nothing. `BatchRequest` is a sealed `Text` | `Image` union
  whose base `options` getter throws `UnsupportedFunctionalityError("batch request type: image")`, so
  every text-only provider refuses an image request before a byte is sent — one rule in the spec instead
  of one check per provider. `FileOperationOptions` collapses the reference's three identical per-call
  option types into one, as `BatchOperationOptions` already did.
- **A streaming upload is a variant, not a new method.** `FileUploadOptions.content` is
  `Inline(FileData)` | `Stream(Flow<ByteArray>, byteSize?)`, and `options.data` — what every existing
  store reads — throws for a stream, so a store that never learned to stream refuses one before any
  request rather than buffering it silently. Anthropic's store accepts streams where the reference's
  throws: same multipart wire, only the byte source differs.
- **`FileDownloadResult.content` is a cold flow, so `mediaType` is null on OpenAI and xAI**: the
  `Content-Type` the reference reads before returning does not exist until the flow is collected. Either
  the result is produced after the response opens or the media type is deferred — recorded, not solved.
- **The realtime client-event union grew five continuous-session variants** (`SessionStart`,
  `SessionClose`, `InputAudioMute`, `InputAudioUnmute`, `ContextAppend`) for OpenAI Live; xAI, Google
  and Cartesia refuse them explicitly in their exhaustive `when`s rather than take an `else`, so the
  next variant is a compile error in every mapper, not a silent drop. WebRTC (`5c0054d`) and the
  `ai/src/realtime` browser session runtime are not ported: browser transport and microphone/playback
  concerns with no Kotlin counterpart.
- **`postMultipartStream` really streams** (Ktor `ChannelProvider` + `writer`, parts in list order,
  retry only until the first chunk is pulled); `getByteStream` and `getLines` share `getBytes`'s redirect
  and origin guard, and the BMP signature now needs the size and zeroed reserved bytes (`813bb36`).

**Runtime**

- **`streamRetries`** closes the recorded "a mid-stream error is not retried" divergence on the caller's
  say-so only: per-round isolated attempts, at most one callback-directed recovery, `RunEvent.Error`
  only for the final unrecovered error. No new `RunEvent` subtype, because `AiSdkChatSession`'s `when`
  is exhaustive by design.
- **`ToolChoiceViolationError` carries the content the model produced instead of the tool call**, and
  `GenerateObject`/`StreamObject` read the prose back out of it — the field exists in the reference for
  exactly that recovery.
- **Empty image results are retried under ONE budget shared with transport failures** (`Retry.kt` has no
  second-error hook); `NoImageGeneratedError.calls` keeps every attempt's full result so the warnings
  and the retryability classification survive the failure. Google and Vertex mark prompt-blocked results
  `isRetryable = false`; every other result stays unclassified.
- **Video webhook receiver rejections need no observer**: a failed `Deferred` is inert until awaited, so
  the reference's unhandled-rejection guard (`ef3bac4`) has nothing to guard here — pinned rather than
  ported.

**Providers**

- **Google preserves JSON Schema** (`2a32459`): the 262-line OpenAPI converter is deleted; tool
  parameters go out verbatim under `parametersJsonSchema`, response schemas under `responseJsonSchema`
  with only `const` → single-value `enum` sanitised. Wire bytes changed and every Google request pin was
  re-pinned deliberately.
- **Gemini 3 tool results carry byte files as `functionResponse.parts[].inlineData`**, and a tool-result
  URL is downloaded (7 MiB cap, no headers, origin-guarded) before the request — the reference's
  download (`f88c7dc`) would have been discarded without the parts, which our converter never emitted.
- **`Vendors.xai` stays** although upstream removed xAI's Chat Completions API (`1f20dba`): the vendor
  still serves the wire, `CompatVendors` in `:data:llm` resolves `api.x.ai` to it, and `XaiProvider`
  already speaks Responses. Removing the row would be a change to AIDE, not a port.
- **Azure Foundry and Cognitive Services hosts** resolve through one rule in `openaicompatible/`
  (`azureBaseUrlInfo` / `azureRequestUrl`, the port of `getAzureOpenAIBaseURLInfo`); the OpenAI lane's
  duplicate in `azure/` was deleted at merge. Likewise one `normalizeOpenAIJsonSchema` (drops
  `propertyNames`, `d5e3024`) lives in `openai/` and the compat engine imports it — applied only on
  OpenAI-served rows, because a self-hosted server may honour the keyword.
- **GPT-6's effort vocabulary is enforced on the compat engine too** (`supportedReasoningEfforts`,
  `prompt_cache_retention` warning): a raw `reasoning_effort` a caller files under the vendor key is
  judged by the same list, since this engine spreads vendor options verbatim. The Responses side keeps
  its own parser (`OpenAIReasoningModels.enforcesEffortLevels`) — two parsers, one rule; a dedupe
  candidate in `TODO.md`.
- **Bedrock's endpoint override is a constructor argument** (`baseUrl`, `agentRuntimeBaseUrl`): the
  reference reads `AWS_ENDPOINT_URL*`, and commonMain has no environment. Its `structuredOutputMode`
  table (`bd74b49`) now gates `output_config.format` on the native path too — it previously reached
  models Bedrock 400s on.
- **OpenAI batch results still decode through the live mapper**, so the reference's "unconvertible tool
  result" warning has nothing to fire on; xAI's batch transcripts now yield `ToolResult`s marked
  provider-executed. Both put the uploaded input file on `status.providerMetadata.<provider>`
  (`inputFileId`, `inputFileExpiresAt`), because `BatchStartResult` itself carries no metadata.
- **Video vendors forward `webhookUrl` as `callback_url`** (MiniMax, Kling, ByteDance) and poll through
  `getBytes(trustedOrigin)` so a redirect is validated before it is followed; our guard validates the
  FIRST hop too, so a loopback base URL is refused where the reference trusts it.
- **Live and Realtime models report provider `openai`**, not `openai.live` / `openai.realtime`, per the
  sub-id rule above; `OpenAIProvider.realtimeModel(modelId, api)` routes known Live ids to Live and keeps
  the legacy default for unknown ids (`resolveOpenAIRealtimeApi`).
- **Mid-conversation system messages** (Anthropic `mid-conversation-system-*` betas) were ported whole
  with `convert-to-anthropic-prompt.ts`'s delta, since they did not exist here; controls on the initial
  prompt are dropped with the reference's warnings.
- Not ported by decision: `useJsonInstructionForStructuredOutput` (a pre-existing gap the delta only
  guarded — the JSON tool stays the fallback), Bedrock `citationsContent` text (lives only on the
  non-streaming wire this port never reads), Mistral/DeepSeek id-union additions (ids are strings),
  the `WORKFLOW_*` deserialization hooks, `runtimeContext` telemetry attribution, and every UI/workflow/
  harness changeset.

## Settled refusals — do not reopen

Each was decided against with a reason; re-deriving them is the waste this section prevents. Moved here
from `TODO.md` on 2026-09-03 so that file holds only open work.

- **`metadataExtractor`** — dead surface upstream: zero of the reference's fifteen vendor packages use it.
- **The camel→snake request-body pass** — it repairs a bug only the reference has (it spreads camelCase
  options then fixes them; we spread verbatim), so porting it would rename keys a caller spelled correctly.
- **The Fireworks `xhigh`→`high` effort clamp** — Fireworks accepts `xhigh`, so the reference's clamp is a
  silent downgrade. Only `minimal` moves.
- **Moonshot's `prefixItems`** — the reference emits it and the MFJS spec prohibits it. The spec wins.
- **LM Studio `stream_options.include_usage`** — stays off. Its docs are silent, and an unverified flag
  that can fail a request is not worth a token count. (Ollama and llama.cpp document it, so they are on.)
- **Gateway's native wire** — it posts the SDK's own call options as the body with the model in an
  `ai-language-model-id` header, an unpinned private format. Byte-matching an undocumented shape that
  cannot be verified without a live account produces a provider that compiles and fails in someone else's
  app. Gateway is served over its documented OpenAI-compatible endpoint instead; that leaves its speech,
  transcription, video, reranking, realtime, batch, model-discovery and four search tools unported **by
  decision**.
- **Perplexity's silent tool drop** — we warn where the reference drops silently. Keep the warning.
- **Perplexity's Chat-Completions `images`/`cost` reads** — never added; the wire retires 2026-09-27 and
  the Agent API model carries `cost`.
- **Subclassing the compat model** — it is `internal` and final on purpose.
- **A `Vendors` row for a wrapped vendor** — the tier rule: a vendor stays a row when its divergence is
  configuration plus at most a pure body/usage function; it earns a package when it must see or rewrite
  the neutral `CallOptions` (tools, schemas, prompt) or remap the finish vocabulary. `zai` drew that line
  and has no row; `perplexity` left the table when its wire changed. `Vendors.custom` stays.
- **`ToolChoiceDialect` / `ProviderToolDialect` / `ImageRequestDialect` stay enums** — they name finite
  shared vocabularies and enumerable tool tables, which is what makes a foreign tool id *refused with a
  warning* rather than sent under a type the endpoint never heard of. Lambdas would delete that argument.
- **Prompt-side `ToolCall.input` stays a `String`** where the reference holds the parsed value. Two of
  the three wires (Chat Completions, Responses) carry it as a string and replay the model's bytes
  verbatim; the object wires parse at their boundary (Anthropic `{}`, Google omit) and the runtime replays
  an `invalid` call's arguments as `{}`; and only a string can carry the raw text of an unparseable call
  back to the model. The porter's trap is named in the KDoc.
- **Cerebras / Mistral / Fireworks / Baseten error structures** — `defaultErrorMessage` already reads both
  the flat and union shapes.
- **`create-provider-stream-error.ts`** and **`normalize-stream-provider-error.ts`** — not applicable:
  Kotlin providers throw `APICallError`, which already carries `statusCode`/`isRetryable`/`data`, and
  the runtime's `StreamErrorNormalizationTest` pins that nothing is lost.
- **`serializeModelOptions`** and **`WORKFLOW_SERIALIZE` / `WORKFLOW_DESERIALIZE`** — not applicable: they
  serve the reference's durable-execution boundary by reflecting over JS config objects. No such feature,
  no Kotlin equivalent.
- **`experimental_toolCaller` and the sandboxed bash `execute`** — runtime mechanisms the reference
  attaches to OpenAI's `programmaticToolCalling` and Anthropic's code-execution / bash factories; our
  runtime has no tool-caller concept and no sandbox, so the factories carry the tools' wire shape only.
- **The zod/valibot half of `schema` (292)** and the per-factory args validation — no Kotlin
  counterpart. `JsonSchema` is a carrier, never a validator; tool args are cargo.
- **`secureJsonParse`'s `__proto__` rejection** — JS prototype pollution has no Kotlin analogue.
- **Vertex `edge/`, `*-provider-node.ts`, `*-provider-edge.ts`** — JS-runtime auth splits, N/A for
  Kotlin/Ktor. Vertex takes a `suspend () -> String` and signs nothing: minting a service-account JWT
  inside a chat library would mean holding a private key in a component with no business holding one, and
  would still be the wrong mechanism on GCE.
- **`safe-node-fetch` (208), `connect-to-websocket` (132), `websocket` (97)** — Node/browser transports;
  Ktor is the equivalent.
- **A cosmetic unification pass over providers with no behaviour to fix.**

## Pinned references

Vendored at `third_party/`, **reference only** — never compiled, never a dependency, never imported.
They are not Gradle subprojects, so `codeMapCheck`, `portabilityCheck`, `dependencyDirectionCheck` and
detekt cannot see them.

| Reference | Commit | Notes |
|---|---|---|
| `third_party/vercel-ai` | `662d7e5` (`ai@7.0.102`, `@ai-sdk/provider@4.0.15`, `@ai-sdk/provider-utils@5.0.41`; was `e1bfe50` / `ai@7.0.85` until 2026-09-16) | Apache-2.0. Spec lives at `packages/provider/src/language-model/v4` |
| `third_party/koog` | `f3a8aa5` (`develop`) | what we are replacing; useful for the quirks it already encodes |

A spec bump is its own scheduled task, never an incidental one.

## Module layout

```
:aisdk              rank 5    the spec. kotlinx-serialization-json + coroutines. Nothing else.
:aisdk:util         rank 15   Ktor HTTP, SSE framing, JSON, retry, ids.
:aisdk:providers    rank 25   every vendor, one package each.
:aisdk:runtime      rank 35   generateText/streamText, the step loop.
```

`:data:llm` is rank 60, so every edge points down. Rank alone does not state the invariant this module
exists for — `:core:common` is rank 0, so `:aisdk:providers -> :core:domain` would read as an ordinary
descent — so `dependencyDirectionCheck` also enforces a CLOSURE: an `:aisdk*` module may depend on
`:aisdk*` and on nothing else of ours (`closedTrees` in the root `build.gradle.kts`). One `ProviderId`
import is all it would take to end the portability quietly, and nothing else would have noticed.

### Deliberately not ported

`safe-node-fetch` (208), `connect-to-websocket` (132) and `websocket` (97) are Node and browser
transports. Ktor's client is the equivalent and is already the one every provider uses, so porting them
would mean carrying a second transport that no provider calls. `validate-download-url` and
`fetch-with-validated-redirects` were on this list and should not have been — they are SSRF defences with
no platform dependency, and they now live in `DownloadUrl.kt`.

`create-provider-stream-error` (provider-utils, Aug-31 addition) is likewise not ported: it exists so a
JS provider package can attach status/retry metadata to a stream-error payload *without importing core*
— a Symbol-tagged plain object standing in for a class it cannot depend on. Kotlin providers already
throw `APICallError`, which carries the same fields (`statusCode`, `isRetryable`, `data`) as a real
type, so the helper's semantics are the fields our stream errors have had all along.

## Verification

```
./gradlew :aisdk:compileKotlinDesktop :aisdk:compileAndroidMain
./gradlew :aisdk:detekt codeMapCheck portabilityCheck dependencyDirectionCheck
```

Note the Android task is `compileAndroidMain` — the AGP 9 KMP-library plugin does not produce a
`compileDebugKotlinAndroid`.

Open work — the live-key gates and the follow-ups the lanes surfaced — lives in the repo-root `TODO.md`;
the decisions that closed everything else are the sections above.
