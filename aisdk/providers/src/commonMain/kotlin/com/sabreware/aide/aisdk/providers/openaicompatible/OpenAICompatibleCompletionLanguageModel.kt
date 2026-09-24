package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.mergedFor
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.providers.options.optObject
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The legacy Completions endpoint — `POST /completions` — for the vendors that still serve it.
 *
 * A single prompt string in, a single `text` out. No messages, no tools, no structured output: the
 * conversation is flattened by [toCompletionPrompt] and the model is told to stop before it writes the
 * user's next line. It exists because `gpt-3.5-turbo-instruct` and the base models Fireworks, Together
 * and DeepInfra host answer nowhere else, and because a caller who wants raw continuation — a code
 * completion with a [`suffix`], an `echo` of the prompt — has no other wire that offers it.
 *
 * The shape is the chat model's, deliberately: the same [ProviderHttp], the same mid-stream error
 * frames, the same `stream_options` opt-in, the same verbatim spread of the vendor's own options, the
 * same `convertUsage` and `transformRequestBody` seams. What differs is the prompt and the response —
 * one text block, keyed `0` as the reference keys it — and the option set, which is the reference's:
 * `echo`, `logitBias`, `logprobs`, `suffix` and `user`, read from the canonical `openai` namespace and
 * the vendor's own on top of it.
 *
 * Two deliberate departures from the reference, both in the direction the chat model already took. An
 * error frame that arrives before any output is emitted as a stream error rather than thrown out of
 * `doStream`, because the runtime — not the transport — decides whether an error ends a turn. And a
 * stream that closes with no `finish_reason` is reported as an error rather than `other`, because a
 * dropped socket presenting as a complete answer is exactly the defect that rule exists to catch.
 */
internal class OpenAICompatibleCompletionLanguageModel(
    override val provider: String,
    override val modelId: String,
    private val http: ProviderHttp,
    /** The COMPLETE endpoint, for the same reason the chat model takes one: Azure's path and query. */
    private val completionsUrl: String,
    private val headers: Map<String, String> = emptyMap(),
    /**
     * Where this vendor's options are read from and its logprobs are filed: the provider id (`openai`,
     * `azure`, `fireworks`), with canonical `openai` always read underneath it. The model's own
     * [provider] is `<id>.completion`, which is not a namespace anyone files options under.
     */
    private val namespace: String,
    /** Replaces the token arithmetic — see [completionUsage] for the default. */
    private val convertUsage: ((JsonObject) -> Usage)? = null,
    /** Last word on the body, after the caller's options — see the chat model's parameter. */
    private val transformRequestBody: ((JsonObject) -> JsonObject)? = null,
    /** Some servers reject `stream_options`; turn it off and lose only the token counts. */
    private val includeUsage: Boolean = true,
    /** How this vendor spells an error, for the `data: {"error":…}` frames that arrive mid-stream. */
    private val errorStructure: ProviderErrorStructure = ProviderErrorStructure.Default,
) : LanguageModel {

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options, stream = false)
        val result = http.postJson(completionsUrl, built.body, built.headers)
        val response = runCatching {
            ProviderJson.decodeFromJsonElement(OpenAICompletionResponse.serializer(), result.value)
        }.getOrElse {
            throw InvalidResponseDataError("Malformed completion response.", data = result.value, cause = it)
        }
        // The reference indexes `choices[0]` and would crash on an empty array; a typed error naming
        // the payload is what a caller can act on.
        val choice = response.choices.firstOrNull()
            ?: throw InvalidResponseDataError(NO_CHOICES_MESSAGE, data = result.value)

        return GenerateResult(
            content = listOf(Content.Text(choice.text)),
            finishReason = choice.finishReason.toCompletionFinishReason(),
            usage = usage(response.usage),
            warnings = built.warnings,
            providerMetadata = logprobsMetadata(choice.logprobs),
            request = RequestInfo(built.bodyText),
            response = ResponseInfo(
                metadata = response.metadata(),
                headers = result.headers,
                body = result.value.toString(),
            ),
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options, stream = true)
        return StreamResult(stream = streamParts(built, options), request = RequestInfo(built.bodyText))
    }

    private fun streamParts(built: BuiltCompletionRequest, options: CallOptions): Flow<StreamPart> = flow {
        emit(StreamPart.StreamStart(built.warnings))
        var opened = false
        var sawChoice = false
        var failed = false
        var finish: FinishReason? = null
        var usage = Usage()
        var logprobs: JsonElement? = null

        suspend fun fail(error: Throwable) {
            // Latched, as on the chat model: a `finish_reason: "stop"` replayed after an injected error
            // page must not talk the stream back into success.
            failed = true
            finish = FinishReason(FinishReason.Unified.Error)
            emit(StreamPart.Error(error))
        }

        http.postSse(completionsUrl, built.body, built.headers).collect { sse ->
            val frame = parseJsonElementOrNull(sse.data)
            if (frame == null) {
                fail(JsonParseError(text = sse.data))
                return@collect
            }
            if (options.includeRawChunks) emit(StreamPart.Raw(frame))

            val errorFrame = (frame as? JsonObject)?.get("error")?.takeIf { it != JsonNull }
            if (errorFrame != null) {
                fail(
                    APICallError(
                        message = errorStructure.extractMessage(frame)
                            ?: "The provider reported an error mid-stream.",
                        url = completionsUrl,
                        requestBodyValues = built.bodyText,
                        data = frame,
                        isRetryable = errorStructure.isRetryable(HTTP_OK, frame) ?: true,
                    ),
                )
                return@collect
            }

            val chunk = runCatching {
                ProviderJson.decodeFromJsonElement(OpenAICompletionResponse.serializer(), frame)
            }.getOrElse {
                fail(InvalidResponseDataError("Malformed chunk on the stream.", data = frame, cause = it))
                return@collect
            }

            // The reference opens the one text block on the first chunk whether or not it carries text,
            // and closes it at the end whether or not any arrived. Matching that keeps a stream with an
            // empty answer delimited the same way as one with words in it.
            if (!opened) {
                opened = true
                emit(StreamPart.ResponseMetadataPart(chunk.metadata()))
                emit(StreamPart.TextStart(TEXT_BLOCK_ID))
            }
            chunk.usage?.let { usage = usage(it) }

            val choice = chunk.choices.firstOrNull()
            if (choice != null) sawChoice = true
            if (!failed) choice?.finishReason?.let { finish = it.toFinishReason() }
            choice?.logprobs?.takeIf { it != JsonNull }?.let { logprobs = it }
            choice?.text?.takeIf { it.isNotEmpty() }?.let { emit(StreamPart.TextDelta(TEXT_BLOCK_ID, it)) }
        }

        if (opened) emit(StreamPart.TextEnd(TEXT_BLOCK_ID))
        if (finish == null && !failed) {
            fail(InvalidResponseDataError(if (sawChoice) NO_FINISH_MESSAGE else NO_CHOICES_MESSAGE))
        }
        emit(
            StreamPart.Finish(
                usage = usage,
                finishReason = finish ?: FinishReason(FinishReason.Unified.Error),
                providerMetadata = logprobsMetadata(logprobs),
            ),
        )
    }

    private fun buildRequest(options: CallOptions, stream: Boolean): BuiltCompletionRequest {
        val warnings = mutableListOf<Warning>()
        if (options.topK != null) warnings += Warning.Unsupported("topK")
        if (!options.tools.isNullOrEmpty()) warnings += Warning.Unsupported("tools")
        if (options.toolChoice != null) warnings += Warning.Unsupported("toolChoice")
        if (options.responseFormat != null && options.responseFormat !is ResponseFormat.Text) {
            warnings += Warning.Unsupported("responseFormat", "JSON response format is not supported.")
        }
        // The reference ignores the neutral effort outright; saying so costs a line and saves a caller
        // wondering why a reasoning setting did nothing on a wire that has no field for it.
        if (options.reasoning != ReasoningEffort.ProviderDefault) {
            warnings += Warning.Unsupported("reasoningEffort", "The legacy Completions API has no reasoning field.")
        }

        val converted = options.prompt.toCompletionPrompt()
        warnings += converted.warnings
        val stop = converted.stopSequences + options.stopSequences.orEmpty()

        val known = options.providerOptions.mergedFor(CANONICAL_NAMESPACE, namespace)
        val own = options.providerOptions?.get(namespace)

        val body = buildJsonObject {
            put("model", modelId)
            known.optBoolean("echo")?.let { put("echo", it) }
            known.optObject("logitBias")?.let { put("logit_bias", it) }
            logprobsOption(known)?.let { put("logprobs", it) }
            known.optString("suffix")?.let { put("suffix", it) }
            known.optString("user")?.let { put("user", it) }
            options.maxOutputTokens?.let { put("max_tokens", it) }
            options.temperature?.let { put("temperature", it) }
            options.topP?.let { put("top_p", it) }
            options.frequencyPenalty?.let { put("frequency_penalty", it) }
            options.presencePenalty?.let { put("presence_penalty", it) }
            options.seed?.let { put("seed", it) }
            put("prompt", converted.prompt)
            if (stop.isNotEmpty()) put("stop", JsonArray(stop.map { JsonPrimitive(it) }))
            if (stream) {
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
            }
            // The vendor's own options, verbatim, minus what was consumed above and the keys that carry
            // the call itself — the chat model's rule, which is what lets a knob the reference's option
            // schema never listed (`best_of`, `n`) reach the wire without a change here.
            own?.forEach { (key, value) ->
                if (key !in CONSUMED_OPTIONS && key !in RESERVED_KEYS) put(key, value)
            }
        }
        val finalBody = transformRequestBody?.invoke(body) ?: body
        return BuiltCompletionRequest(
            body = finalBody,
            bodyText = ProviderJson.encodeToString(JsonElement.serializer(), finalBody),
            headers = combineHeaders(headers, options.headers),
            warnings = warnings,
        )
    }

    /**
     * `logprobs`, which the reference accepts as a boolean OR a count: `true` asks for the chosen
     * token's probability alone (OpenAI's `0`), a number asks for that many alternatives, and `false`
     * is the same as saying nothing.
     */
    private fun logprobsOption(known: JsonObject): Int? = when (known.optBoolean("logprobs")) {
        true -> 0
        false -> null
        null -> known.optInt("logprobs")
    }

    private fun usage(raw: JsonObject?): Usage =
        if (raw == null) Usage() else (convertUsage ?: ::completionUsage)(raw)

    private fun logprobsMetadata(logprobs: JsonElement?): ProviderMetadata? = logprobs
        ?.takeIf { it != JsonNull }
        ?.let { mapOf(namespace to buildJsonObject { put("logprobs", it) }) }

    private data class BuiltCompletionRequest(
        val body: JsonObject,
        val bodyText: String,
        val headers: Map<String, String>,
        val warnings: List<Warning>,
    )

    private companion object {
        /** The reference's id for the single text block a completion produces. */
        const val TEXT_BLOCK_ID = "0"

        /** The status a mid-stream error actually arrived on: the response itself succeeded. */
        const val HTTP_OK = 200
    }
}

/**
 * The Completions wire's usage, exactly as the reference reads it.
 *
 * Note the asymmetry it keeps: `total` is null when the vendor omitted the count, but `noCache` and
 * `text` read as ZERO — the reference's `?? 0` — because this wire has no cache or reasoning split for
 * the difference to matter, and a caller summing a run's no-cache tokens wants a number.
 */
internal fun completionUsage(raw: JsonObject): Usage {
    val prompt = raw.intOrNull("prompt_tokens")
    val completion = raw.intOrNull("completion_tokens")
    return Usage(
        inputTokens = Usage.InputTokens(total = prompt, noCache = prompt ?: 0),
        outputTokens = Usage.OutputTokens(total = completion, text = completion ?: 0),
        raw = raw,
    )
}

private fun String?.toCompletionFinishReason(): FinishReason =
    this?.toFinishReason() ?: FinishReason(FinishReason.Unified.Other)

private fun OpenAICompletionResponse.metadata(): ResponseMetadata = ResponseMetadata(
    id = id,
    // `created` is epoch SECONDS on this wire and epoch MILLISECONDS in the contract.
    timestamp = created?.times(MILLIS_PER_SECOND),
    modelId = model,
)

private const val MILLIS_PER_SECOND = 1000L

/** The canonical namespace every vendor on this wire reads underneath its own. */
private const val CANONICAL_NAMESPACE = "openai"

/** The reference's option set, consumed above rather than spread. */
private val CONSUMED_OPTIONS = setOf("echo", "logitBias", "logprobs", "suffix", "user")

/** Keys that carry the call itself; a vendor option may not overwrite them. */
private val RESERVED_KEYS = setOf("model", "prompt", "stream", "stream_options")
