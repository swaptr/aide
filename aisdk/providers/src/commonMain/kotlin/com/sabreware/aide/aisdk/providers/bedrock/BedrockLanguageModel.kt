package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.RequestInfo
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicFrame
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicRequestBuilder
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicStreamEvent
import com.sabreware.aide.aisdk.providers.anthropic.AnthropicStreamMapper
import com.sabreware.aide.aisdk.providers.anthropic.BuiltAnthropicRequest
import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.AwsEventStreamDecoder
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.SigV4
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** The provider id, and the namespace Bedrock payloads are filed under. */
public const val BEDROCK_PROVIDER_ID: String = "amazon-bedrock"

/**
 * Claude on Amazon Bedrock.
 *
 * Bedrock is a transport, not a model family: the request and response bodies are the VENDOR's own —
 * for Claude, the Anthropic Messages format this library already speaks. So this reuses
 * [AnthropicRequestBuilder] and [AnthropicStreamMapper] wholesale, and only three things differ:
 *
 * 1. **Auth is SigV4**, not an API key header.
 * 2. **The model id goes in the URL**, and `anthropic_version` goes in the body in place of `model`.
 * 3. **The response is AWS's binary event stream**, not SSE, with each frame carrying the Anthropic event
 *    base64-encoded inside a JSON envelope.
 *
 * Sharing the mapper is the point. Signature handling is delicate enough that a second copy would
 * eventually drift, and the copy that drifted would fail as an opaque 400 on the second tool round.
 */
internal class BedrockLanguageModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val credentials: () -> AwsCredentials,
    private val region: String,
    private val now: () -> Long,
    private val anthropicVersion: String = DEFAULT_ANTHROPIC_VERSION,
    /** The runtime endpoint; defaults to the region's own, and a host with an override passes it. */
    private val baseUrl: String = bedrockBaseUrl(region),
) : LanguageModel {

    override val provider: String = BEDROCK_PROVIDER_ID

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

    @OptIn(ExperimentalEncodingApi::class)
    private fun streamParts(
        built: BuiltAnthropicRequest,
        options: CallOptions,
        onResponse: (HttpResult<Unit>) -> Unit = {},
    ): Flow<StreamPart> {
        val url = "$baseUrl/model/$modelId/invoke-with-response-stream"
        val payload = ProviderJson.encodeToString(JsonElement.serializer(), built.body).encodeToByteArray()

        val events = flow {
            // The caller's headers are signed along with everything else: SigV4 covers the header set, so
            // adding one after signing is a 403 rather than an extra header.
            val callerHeaders = combineHeaders(
                mapOf("content-type" to "application/json"),
                options.headers,
            )
            val signed = SigV4.signedHeaders(
                method = "POST",
                url = url,
                headers = callerHeaders,
                payload = payload,
                credentials = credentials(),
                region = region,
                service = SERVICE,
                timestampMillis = now(),
            )
            val decoder = AwsEventStreamDecoder()
            http.postBytes(url, payload, callerHeaders + signed, onResponse).collect { chunk ->
                decoder.feed(chunk).forEach { message ->
                    message.exceptionType?.let { exceptionType ->
                        val payloadText = message.payload.decodeToString()
                        // Routed through the provider's error structure so the human-readable
                        // `message` Bedrock puts in the frame is what the caller reads, and its
                        // throttling exception stays retryable like any other 429.
                        val extracted = runCatching { parseJsonObject(payloadText) }.getOrNull()
                            ?.let { BedrockErrors.extractMessage(it) }
                        throw APICallError(
                            message = "$exceptionType: ${extracted ?: payloadText}",
                            url = url,
                            responseBody = payloadText,
                            isRetryable = exceptionType == "throttlingException",
                        )
                    }
                    // Bedrock wraps the vendor's own event as base64 inside a JSON envelope.
                    val envelope = runCatching { parseJsonObject(message.payload.decodeToString()) }.getOrNull()
                    val encoded = envelope?.get("bytes")?.jsonPrimitive?.content ?: return@forEach
                    emit(Base64.decode(encoded).decodeToString())
                }
            }
            if (decoder.hasPartialMessage()) {
                throw APICallError("Bedrock stream ended mid-frame", url = url)
            }
        }.mapNotNull { json ->
            runCatching { ProviderJson.decodeFromString(AnthropicStreamEvent.serializer(), json) }
                .getOrNull()
                ?.let { AnthropicFrame(it) }
        }

        return AnthropicStreamMapper(
            sourceUrl = url,
            toolNames = built.toolNames,
            usesJsonResponseTool = built.usesJsonResponseTool,
            markCodeExecutionDynamic = built.markCodeExecutionDynamic,
        ).map(events, built.warnings)
    }

    private fun buildRequest(options: CallOptions): BuiltAnthropicRequest {
        val built = AnthropicRequestBuilder.buildHosted(
            modelId = modelId,
            options = options,
            anthropicVersion = anthropicVersion,
            // Bedrock's endpoint implies streaming and rejects an explicit `stream` field.
            includeStream = false,
            // Bedrock validates against its own copy of the Messages schema and rejects
            // `output_config.format` for the newest Claude families; two more serve it unreliably. The
            // JSON tool is the fallback for those — a caller forcing `outputFormat` still gets it.
            supportsNativeStructuredOutput = bedrockSupportsNativeStructuredOutput(modelId),
            // Options filed under this provider's own id are read too, merged over `anthropic`.
            optionsNamespace = BEDROCK_PROVIDER_ID,
            rewriteTools = BedrockAnthropicRemap::rewriteTools,
        )
        return built.copy(body = BedrockAnthropicRemap.rewriteBody(built.body))
    }

    public companion object {
        public const val DEFAULT_ANTHROPIC_VERSION: String = "bedrock-2023-05-31"
        private const val SERVICE = "bedrock"
    }
}
