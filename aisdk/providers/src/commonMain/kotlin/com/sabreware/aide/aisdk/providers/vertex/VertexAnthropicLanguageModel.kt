package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.providers.anthropic.ANTHROPIC_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicFrame
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicRequestBuilder
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicStreamEvent
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicStreamMapper
import com.sabreware.aide.aisdk.providers.anthropic.BuiltAnthropicRequest
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement

/**
 * Claude on Vertex AI.
 *
 * The third transport for the same body. Anthropic's own API, Bedrock and Vertex all carry the Messages
 * format; what differs is auth, the URL, and the framing — and all three share
 * [AnthropicRequestBuilder] and [AnthropicStreamMapper] rather than each keeping a copy.
 *
 * Against Bedrock specifically: Vertex is SSE rather than a binary event stream, and its
 * `streamRawPredict` REQUIRES `stream: true` in the body where Bedrock's endpoint rejects the field. One
 * word of difference, and the kind that is far cheaper to state than to rediscover from a 400.
 *
 * [provider] stays `anthropic` deliberately. The host changed; the model did not, and a conversation held
 * here must replay its signatures under the same namespace it would anywhere else — otherwise moving a
 * chat between the direct API and Vertex would silently lose them.
 */
internal class VertexAnthropicLanguageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val projectId: String,
    private val location: String,
    private val accessToken: suspend () -> String,
    private val anthropicVersion: String = DEFAULT_VERTEX_ANTHROPIC_VERSION,
) : LanguageModel {

    override val provider: String = ANTHROPIC_PROVIDER_ID

    override suspend fun doStream(options: CallOptions): StreamResult {
        val built = buildRequest(options)
        return StreamResult(
            stream = streamParts(built, options),
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
        )
    }

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        val built = buildRequest(options)
        var meta: HttpResult<Unit>? = null
        val result = assembleGenerateResult(streamParts(built, options) { meta = it })
        return result.copy(
            request = RequestInfo(ProviderJson.encodeToString(JsonElement.serializer(), built.body)),
            response = meta?.responseInfo(modelId = modelId, id = result.response?.metadata?.id)
                ?: result.response,
        )
    }

    private fun streamParts(
        built: BuiltAnthropicRequest,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> {
        val url = "https://$location-aiplatform.googleapis.com/v1/projects/$projectId/locations/$location" +
            "/publishers/anthropic/models/$modelId:streamRawPredict"
        val events = flow {
            val headers = combineHeaders(
                mapOf("Authorization" to "Bearer ${accessToken()}"),
                options.headers,
            )
            http.postSse(url, built.body, headers, onResponse).collect { emit(it) }
        }.mapNotNull { sse ->
            runCatching {
                ProviderJson.decodeFromString(AnthropicStreamEvent.serializer(), sse.data)
            }.getOrNull()?.let { AnthropicFrame(it) }
        }
        return AnthropicStreamMapper(
            sourceUrl = url,
            toolNames = built.toolNames,
            usesJsonResponseTool = built.usesJsonResponseTool,
        ).map(events, built.warnings)
    }

    private fun buildRequest(options: CallOptions): BuiltAnthropicRequest =
        AnthropicRequestBuilder.buildHosted(
            modelId = modelId,
            options = options,
            anthropicVersion = anthropicVersion,
            // streamRawPredict requires it in the body, where Bedrock's endpoint rejects it.
            includeStream = true,
            // Vertex does not forward the structured-output beta, so `output_config.format` is refused
            // there and `responseFormat` has to be served through the JSON tool instead.
            supportsNativeStructuredOutput = false,
            // Options filed under `google-vertex` are read too, merged over `anthropic`.
            optionsNamespace = VERTEX_PROVIDER_ID,
        )

    public companion object {
        public const val DEFAULT_VERTEX_ANTHROPIC_VERSION: String = "vertex-2023-10-16"
    }
}
