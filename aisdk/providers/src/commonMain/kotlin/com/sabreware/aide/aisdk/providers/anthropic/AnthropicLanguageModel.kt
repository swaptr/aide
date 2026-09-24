package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElementOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Anthropic Messages API, streaming.
 *
 * Ported from AIDE's own native codec, which is the implementation already known to round-trip signed
 * thinking correctly. What changed is only the edges: transport is `:aisdk:util`, and the output is the
 * spec's id-correlated [StreamPart] stream instead of AIDE's flatter event type.
 *
 * The signature handling is the reason this provider exists:
 *
 * - `signature_delta` is buffered per block and emitted on [StreamPart.ReasoningEnd] in
 *   `providerMetadata`, because the signed payload arrives when the block CLOSES rather than with its
 *   text. A stream shape that hangs metadata off text events has nowhere to put it.
 * - `redacted_thinking` arrives complete, with no deltas, and is surfaced as a reasoning block whose
 *   metadata carries the encrypted payload — preserved in arrival order among the other blocks.
 *
 * Both must come back byte-for-byte on the next request or Anthropic rejects it. See
 * [com.sabreware.aide.aisdk.providers.anthropic.toAnthropic] for the replay half.
 *
 * Sampling parameters are dropped rather than forwarded where the model or the request rejects them, and
 * the caller is told which and why — see `AnthropicRequestBuilder.resolveSamplers`.
 */
internal class AnthropicLanguageModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = ANTHROPIC_DEFAULT_BASE_URL,
    private val headers: Map<String, String> = emptyMap(),
    /**
     * Whether this deployment can serve `output_config.format`. False on a host that will not forward
     * the structured-output beta — Vertex — where the JSON tool is the working fallback.
     */
    private val supportsNativeStructuredOutput: Boolean = true,
    /**
     * Extra headers computed from the request itself, for a deployment that signs its requests.
     *
     * The AWS-hosted Claude Platform is the reason: SigV4 signs the exact bytes of the body, so the
     * signature cannot be produced until the payload exists. Taking a hook here is what lets that
     * deployment be a provider rather than a second copy of this model.
     */
    private val signRequest: (suspend (url: String, payload: String, headers: Map<String, String>) ->
    Map<String, String>)? = null,
    /**
     * A second `providerOptions` key read alongside `anthropic`, for a vendor serving this wire under
     * its own name — MiniMax files under `minimax`. See [AnthropicOptions.of].
     */
    private val optionsNamespace: String? = null,
) : LanguageModel {

    override val provider: String = ANTHROPIC_PROVIDER_ID

    private val http = http.withErrorStructure(AnthropicErrorStructure)

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(built.payload),
        )
    }

    /**
     * Folds the stream rather than calling the non-streaming endpoint.
     *
     * Anthropic's non-streaming response is a second wire shape with its own mapper to keep correct, and
     * every consumer here streams anyway. One wire path that is right beats two that drift.
     */
    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options)
        // The response metadata only exists once the stream has been opened, so it is captured on the way
        // past and read after the fold — which is also why `doStream` cannot fill its own `response`.
        var meta: HttpResult<Unit>? = null
        val result = assembleGenerateResult(streamParts(built, options) { meta = it })
        return result.copy(
            request = RequestInfo(built.payload),
            response = meta?.responseInfo(
                modelId = modelId,
                id = result.response?.metadata?.id,
            ) ?: result.response,
        )
    }

    /**
     * Decodes the SSE stream into Anthropic events and hands them to the shared mapper.
     *
     * A frame that fails to decode is SKIPPED rather than fatal — proxies inject keep-alives and error
     * pages mid-stream, and one unparseable frame should not end a generation that is otherwise fine.
     */
    private fun streamParts(
        built: BuiltRequest,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> {
        val url = "$baseUrl/messages"
        val events = flow {
            val signed = signRequest?.invoke(url, built.payload, built.headers).orEmpty()
            emitAll(http.postSse(url, built.request.body, built.headers + signed, onResponse))
        }.map { sse ->
            val raw = if (options.includeRawChunks) parseJsonElementOrNull(sse.data) else null
            val event = runCatching {
                ProviderJson.decodeFromString(AnthropicStreamEvent.serializer(), sse.data)
            }.getOrNull()
            event?.let { AnthropicFrame(it, raw) }
        }
        return AnthropicStreamMapper(
            sourceUrl = url,
            toolNames = built.request.toolNames,
            usesJsonResponseTool = built.request.usesJsonResponseTool,
            markCodeExecutionDynamic = built.request.markCodeExecutionDynamic,
        ).map(events.filterNotNullFrames(), built.request.warnings)
    }

    private fun buildRequest(options: CallOptions): BuiltRequest {
        val request = AnthropicRequestBuilder.build(modelId, options, supportsNativeStructuredOutput, optionsNamespace)
        return BuiltRequest(
            request = request,
            headers = requestHeaders(options, request),
            payload = ProviderJson.encodeToString(JsonElement.serializer(), request.body),
        )
    }

    /**
     * The headers for one call: this model's, the caller's, and one merged `anthropic-beta`.
     *
     * `options.headers` had no reader in any provider, which dropped proxy tokens, trace ids — and, for
     * Anthropic specifically, the documented escape hatch: a caller enables an unreleased feature by
     * naming its beta in a header. Merging rather than overwriting is the whole point. A caller asking
     * for `files-api-2025-04-14` must not silently cancel the `computer-use` beta a tool needs.
     */
    private fun requestHeaders(options: CallOptions, request: BuiltAnthropicRequest): Map<String, String> {
        val betas = request.betas +
            headers.betas("anthropic-beta") +
            options.headers.orEmpty().betas("anthropic-beta")
        return combineHeaders(
            headers,
            options.headers,
            if (betas.isEmpty()) emptyMap() else mapOf("anthropic-beta" to betas.joinToString(",")),
        )
    }

    private fun Map<String, String>.betas(name: String): List<String> =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    private data class BuiltRequest(
        val request: BuiltAnthropicRequest,
        val headers: Map<String, String>,
        val payload: String,
    )

}

private fun Flow<AnthropicFrame?>.filterNotNullFrames(): Flow<AnthropicFrame> = flow {
    collect { frame -> frame?.let { emit(it) } }
}

/**
 * Anthropic's error envelope: `{"type":"error","error":{"type":…,"message":…}}`.
 *
 * Without it every failure read `HTTP 429 from https://…: {"type":"error","error":{…` — the vendor's own
 * sentence, truncated at 500 characters, inside a string. And `overloaded_error` could not be marked
 * retryable, which is the one thing a caller actually wants to do about it.
 */
internal val AnthropicErrorStructure: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body ->
        ((body as? JsonObject)?.get("error") as? JsonObject)?.get("message")?.stringOrNull()
    },
    isRetryable = { _, body ->
        val type = ((body as? JsonObject)?.get("error") as? JsonObject)?.get("type")?.stringOrNull()
        if (type == "overloaded_error") true else null
    },
)
