# aisdk — a Kotlin Multiplatform AI SDK

A Kotlin port of the [Vercel AI SDK](https://ai-sdk.dev)'s provider layer and runtime
(Apache-2.0, vendored reference at `third_party/vercel-ai`). One provider-agnostic contract —
[`LanguageModel`](src/commonMain/kotlin/com/sabreware/aide/aisdk/LanguageModel.kt) plus its seven
modality siblings — implemented by ~30 providers, driven by one tool loop, on every platform Kotlin
compiles to. The library knows nothing about the app that hosts it: no DI framework, no platform
types in shared code, `commonMain` only (enforced by `portabilityCheck`).

**Local and remote are the same API.** Ollama, LM Studio, llama.cpp and vLLM are entries in the same
vendor table as OpenAI, Anthropic and Google — a device app can route a prompt to an on-device
server or a cloud vendor by swapping one constructor, on Android, desktop, or any future target.

The one idea the whole design rests on: `providerMetadata`, an opaque provider-namespaced
`Map<String, JsonObject>` on every content part and every stream part. A vendor payload the neutral
layer never learned about — Anthropic's thinking `signature`, Gemini's `thoughtSignature` — is
carried back verbatim instead of being dropped, which is what makes signed reasoning replay across
tool rounds work. Rationale and porting rules: [`DESIGN.md`](DESIGN.md).

## Modules

| Module | What it holds |
|---|---|
| `:aisdk` | the specification: `LanguageModel`, `Content`, `StreamPart`, `CallOptions`, every modality contract, errors |
| `:aisdk:util` | transport and shared machinery: `ProviderHttp`, SSE, retries, SigV4, the AWS event stream, job polling |
| `:aisdk:providers` | the vendors — native wires (Anthropic, Google, OpenAI Responses, Cohere, Bedrock, Vertex, …) and the OpenAI-compatible table (`Vendors`) |
| `:aisdk:runtime` | `streamText`/`generateText` and the tool loop, structured output, embeddings, image/speech/transcription/video wrappers, middleware, `ToolLoopAgent` |

## Getting started

Everything runs over an injected Ktor `HttpClient` — the library never constructs its own transport,
so it shares your app's connection pool, proxy and timeouts.

```kotlin
val client = HttpClient() // your engine: OkHttp, CIO, Darwin, …

// A cloud vendor…
val anthropic = AnthropicProvider(client, apiKey = System.getenv("ANTHROPIC_API_KEY"))
// …or a local server, same contract.
val ollama = Vendors.ollama(client)

val model = anthropic.languageModel("claude-opus-4-5")

streamText(
    model = model,
    prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Say hi in five words.")))),
).textDeltas().collect(::print)
```

`generateText` is the same loop without streaming. Both put the prompt through
`standardizePrompt`, so vendor-shape rules (one tool turn per round, leading system message, no
unanswered tool call) are checked up front rather than surfacing as a 400.

### Tools

```kotlin
val weather = Tool.Function(
    name = "weather",
    description = "Current weather for a city.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
        put("required", buildJsonArray { add(JsonPrimitive("city")) })
    },
)

val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Weather in Oslo?"))))
val result = generateText(
    model = model,
    prompt = prompt,
    options = CallOptions(prompt = prompt, tools = listOf(weather)),
    toolExecutor = { call -> ToolOutput.Text("Sunny, 3°C") },
    stopWhen = stepCountIs(4),
)
println(result.text)
```

A tool that throws becomes an error result the model can read — it does not end the run. Reasoning
parts keep their order and their `providerMetadata` when the loop replays the assistant turn, which
is the correctness property everything else here exists to protect.

### Structured output

```kotlin
@Serializable data class City(val name: String, val population: Long)

val city = generateObject(
    model = model,
    prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Largest city in Norway?")))),
    schema = citySchema, // a JsonObject; passed to the vendor verbatim
    deserializer = City.serializer(),
).value
```

Vendors with constrained decoding enforce the schema at generation time; the rest get it as
guidance and the result is validated (and optionally repaired via `RepairText`) here.

### Provider options and metadata

Anything vendor-specific rides in `providerOptions`, keyed by provider id, and comes back in
`providerMetadata` the same way:

```kotlin
CallOptions(
    prompt = prompt,
    providerOptions = mapOf(
        "anthropic" to buildJsonObject { put("cacheControl", buildJsonObject { put("type", "ephemeral") }) },
    ),
)
```

Unknown keys are forwarded (snake-cased where the vendor's wire wants it), so an option a vendor
ships tomorrow is usable today.

## Verification

```
./gradlew :aisdk:desktopTest :aisdk:util:desktopTest :aisdk:providers:desktopTest :aisdk:runtime:desktopTest
make api-dump   # regenerate the committed .api dumps after a deliberate API change
make docs       # Dokka HTML → aisdk/build/dokka
```

The public surface is compiler-enforced (`explicitApi()`) and locked by Kotlin's built-in ABI
validation: `make check` fails on any undumped API change, and the committed `api/` diff is part of
the review.
