package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.BatchError
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchRequestType
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.providers.openai.XaiResponsesQuirks
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The per-item half of xAI's Batch API. Results are paged, and each item is either an error envelope or
// a COMPLETE Chat Completions document — xAI's storage format for every text batch, whichever endpoint
// the request named. See XaiResponsesBatchModel for why that is decoded here rather than by the
// Responses output mapper.

/** `GET /v1/batches/{id}/results` — one page. */
@Serializable
internal data class XaiBatchResultsPage(
    val results: List<XaiBatchResult> = emptyList(),
    @SerialName("pagination_token") val paginationToken: String? = null,
)

/** One item: the caller's `custom_id` echoed as `batch_request_id`, plus either a result or an error. */
@Serializable
internal data class XaiBatchResult(
    @SerialName("batch_request_id") val batchRequestId: String,
    @SerialName("batch_result") val batchResult: XaiBatchResultBody? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
internal data class XaiBatchResultBody(
    val response: XaiBatchResultResponse? = null,
    val error: XaiBatchResultError? = null,
)

/** The stored document, kept opaque until the item is known not to be an error. */
@Serializable
internal data class XaiBatchResultResponse(
    @SerialName("chat_get_completion") val chatGetCompletion: JsonElement? = null,
    /** An image request's result — the `/images/generations` response, stored as it was answered. */
    @SerialName("image_generation") val imageGeneration: JsonElement? = null,
)

/**
 * xAI's status-style error: `code` is a number OR a string, and `0` means "no error" — an envelope
 * that is present on every successful item too.
 */
@Serializable
internal data class XaiBatchResultError(
    val code: JsonPrimitive? = null,
    val message: String? = null,
) {
    /** The code as text, or null for absent — `0` and `"0"` both read as `"0"` and are handled by the caller. */
    val codeText: String? get() = code?.takeIf { it !is JsonNull }?.content
}

/**
 * The item's outcome.
 *
 * An item is a failure when it carries an `error_message`, a non-zero `code`, or a message with no code
 * at all; a failure whose code is xAI's cancellation vocabulary is [BatchItemResult.Cancelled]. Anything
 * else must hold a decodable document — Chat Completions for a text request, an image response for an
 * image one — and an item that does not is a FAILED item with `invalid_response`: never an exception,
 * because one malformed item must not hide the hundreds beside it.
 *
 * [fetchImage] reads an image xAI stored by URL rather than inline; the batch model binds it to its own
 * origin so the credentials ride along.
 */
internal suspend fun XaiBatchResult.toItemResult(fetchImage: suspend (String) -> ByteArray): BatchItemResult {
    val error = batchResult?.error
    val code = error?.codeText
    val hasErrorMessage = !errorMessage.isNullOrEmpty()
    val hasErrorCode = code != null && code != "0"
    val hasCodelessMessage = code == null && !error?.message.isNullOrEmpty()
    if (hasErrorMessage || hasErrorCode || hasCodelessMessage) {
        val converted = BatchError(
            message = errorMessage?.takeIf { it.isNotEmpty() }
                ?: error?.message?.takeIf { it.isNotEmpty() }
                ?: "xAI batch request failed.",
            code = code?.takeIf { it != "0" },
        )
        return if (isXaiCancellationCode(code)) {
            BatchItemResult.Cancelled(id = batchRequestId, error = converted)
        } else {
            BatchItemResult.Failed(id = batchRequestId, error = converted)
        }
    }

    val stored = batchResult?.response
    val image = stored?.imageGeneration?.takeIf { it !is JsonNull }
    val document = stored?.chatGetCompletion?.takeIf { it !is JsonNull }
        ?: return image?.let { toImageItemResult(it, fetchImage) } ?: invalidXaiItem(batchRequestId)
    val response = runCatching {
        ProviderJson.decodeFromJsonElement(XaiBatchChatResponse.serializer(), document)
    }.getOrNull() ?: return invalidXaiItem(batchRequestId)
    return response.toItemResult(
        requestId = batchRequestId,
        rawUsage = (document as? JsonObject)?.get("usage") as? JsonObject,
    )
}

// ---- The stored image document ---------------------------------------------------------------------

/** What xAI stores for an image item — the `/images/generations` response. */
@Serializable
internal data class XaiBatchImageResponse(
    val data: List<XaiBatchImageEntry>,
    val usage: XaiBatchImageUsage? = null,
)

@Serializable
internal data class XaiBatchImageEntry(
    val url: String? = null,
    @SerialName("b64_json") val b64Json: String? = null,
    @SerialName("revised_prompt") val revisedPrompt: String? = null,
    /** False when the vendor's moderation blocked the image — the whole item is a failure then. */
    @SerialName("respect_moderation") val respectModeration: Boolean? = null,
)

@Serializable
internal data class XaiBatchImageUsage(@SerialName("cost_in_usd_ticks") val costInUsdTicks: Long? = null)

/**
 * An image item: the [ImageResult] a live image call would have returned, or a typed failure.
 *
 * A moderated image is a FAILED image item with the reference's sentence, not an exception; an image
 * with neither bytes nor a URL is the one malformation that throws, because the document validated and
 * the vendor simply sent nothing to return.
 */
private suspend fun XaiBatchResult.toImageItemResult(
    document: JsonElement,
    fetchImage: suspend (String) -> ByteArray,
): BatchItemResult {
    val response = runCatching {
        ProviderJson.decodeFromJsonElement(XaiBatchImageResponse.serializer(), document)
    }.getOrNull() ?: return BatchItemResult.Failed(
        id = batchRequestId,
        error = BatchError(message = "xAI returned an invalid image batch result.", code = "invalid_response"),
        type = BatchRequestType.Image,
    )
    if (response.data.any { it.respectModeration == false }) {
        return BatchItemResult.Failed(
            id = batchRequestId,
            error = BatchError(message = "Image generation was blocked due to a content policy violation."),
            type = BatchRequestType.Image,
        )
    }
    val images = response.data.map { entry ->
        entry.b64Json?.let { BinaryData.Base64(it) }
            ?: entry.url?.let { BinaryData.Bytes(fetchImage(it)) }
            ?: throw InvalidArgumentError(
                message = "xAI returned an image without data or a URL.",
                argument = "batchResult",
            )
    }
    return BatchItemResult.ImageSucceeded(
        id = batchRequestId,
        result = ImageResult(
            images = images,
            // A stored result has no response of its own to describe; the read is now.
            response = ModalityResponse(timestamp = Clock.System.now().toEpochMilliseconds()),
            providerMetadata = mapOf(
                XAI_PROVIDER_ID to buildJsonObject {
                    put(
                        "images",
                        buildJsonArray {
                            response.data.forEach { entry ->
                                add(buildJsonObject { entry.revisedPrompt?.let { put("revisedPrompt", it) } })
                            }
                        },
                    )
                    response.usage?.costInUsdTicks?.let { put("costInUsdTicks", it) }
                },
            ),
        ),
    )
}

/** `1` is xAI's cancellation status; the two words are what its newer envelopes spell it as. */
private fun isXaiCancellationCode(code: String?): Boolean =
    code != null && code.lowercase() in setOf("1", "cancelled", "batch_cancelled")

private fun invalidXaiItem(id: String): BatchItemResult.Failed = BatchItemResult.Failed(
    id = id,
    error = BatchError(
        message = "xAI returned an invalid Responses batch result.",
        code = "invalid_response",
    ),
)

// ---- The stored Chat Completions document -------------------------------------------------------

/** What xAI stores for a text batch item — Chat Completions, whatever endpoint the line named. */
@Serializable
internal data class XaiBatchChatResponse(
    val id: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<XaiBatchChatChoice>? = null,
    val usage: XaiBatchChatUsage? = null,
    val citations: List<String>? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
    /** A refusal xAI reports inside a 200: a code plus a sentence, no `choices`. */
    val code: String? = null,
    val error: String? = null,
)

@Serializable
internal data class XaiBatchChatChoice(
    val message: XaiBatchChatMessage,
    val index: Int? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class XaiBatchChatMessage(
    /** `assistant`, or `tool` for a transcript row holding a provider-executed tool's result. */
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<XaiBatchChatToolCall>? = null,
)

@Serializable
internal data class XaiBatchChatToolCall(
    val id: String,
    val type: String? = null,
    val function: XaiBatchChatFunction,
)

@Serializable
internal data class XaiBatchChatFunction(
    val name: String,
    val arguments: String,
)

/**
 * xAI's Chat Completions usage. `completion_tokens` EXCLUDES reasoning, and `prompt_tokens` may exclude
 * the cached share — both the opposite of OpenAI — which is why this is not the shared reader.
 */
@Serializable
internal data class XaiBatchChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("cost_in_usd_ticks") val costInUsdTicks: Long? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: XaiBatchPromptTokensDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: XaiBatchCompletionTokensDetails? = null,
)

@Serializable
internal data class XaiBatchPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

@Serializable
internal data class XaiBatchCompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
)

/**
 * A stored document as the [GenerateResult] a live call would have returned.
 *
 * The document is a TRANSCRIPT, not one answer: xAI's agentic runs store every turn as a choice, and a
 * `tool` row holds the result of a call the vendor executed itself. A call whose id a tool row also
 * names is therefore provider-executed (and `dynamic`, since the batch holds no tool table to declare
 * it); a call no tool row answers is the client's to run, and the finish reason is the LAST assistant
 * row's, because that is the turn the transcript ended on. Citations become URL sources keyed by their
 * own URL, the reference's choice — a batch has no id generator whose output would mean anything across
 * processes.
 */
private fun XaiBatchChatResponse.toItemResult(requestId: String, rawUsage: JsonObject?): BatchItemResult {
    error?.let { return BatchItemResult.Failed(id = requestId, error = BatchError(message = it, code = code)) }

    val choices = choices?.takeIf { it.isNotEmpty() } ?: return BatchItemResult.Failed(
        id = requestId,
        error = BatchError(
            message = "xAI returned a batch response without any choices.",
            code = "invalid_response",
        ),
    )
    val providerExecutedIds = choices
        .filter { it.message.role == "tool" }
        .flatMap { it.message.toolCalls.orEmpty().map { call -> call.id } }
        .toSet()
    var lastAssistant: XaiBatchChatChoice? = null

    val content = buildList<Content> {
        choices.forEach { choice ->
            val message = choice.message
            if (message.role == "tool") {
                val result = message.content ?: return@forEach
                message.toolCalls.orEmpty().forEach { call ->
                    add(
                        Content.ToolResult(
                            toolCallId = call.id,
                            toolName = call.function.name,
                            output = ToolOutput.Text(result),
                            dynamic = true,
                        ),
                    )
                }
                return@forEach
            }
            lastAssistant = choice
            message.content?.takeIf { it.isNotEmpty() }?.let { add(Content.Text(it)) }
            message.reasoningContent?.takeIf { it.isNotEmpty() }?.let { add(Content.Reasoning(it)) }
            message.toolCalls.orEmpty().forEach { call ->
                val providerExecuted = call.id in providerExecutedIds
                add(
                    Content.ToolCall(
                        toolCallId = call.id,
                        toolName = call.function.name,
                        input = call.function.arguments,
                        providerExecuted = providerExecuted,
                        dynamic = providerExecuted,
                    ),
                )
            }
        }
        citations.orEmpty().forEach { add(Content.Source.Url(id = it, url = it)) }
    }
    val finishReason = lastAssistant?.finishReason
    val metadata = buildJsonObject {
        usage?.costInUsdTicks?.let { put("costInUsdTicks", it) }
        serviceTier?.let { put("serviceTier", it) }
    }.takeIf { it.isNotEmpty() }?.let { mapOf(XAI_PROVIDER_ID to it) }

    return BatchItemResult.Succeeded(
        id = requestId,
        result = GenerateResult(
            content = content,
            finishReason = FinishReason(
                // The Responses quirk table already holds xAI's finish vocabulary; Chat Completions
                // spells it the same way.
                unified = finishReason?.let { XaiResponsesQuirks.finishReasons[it] } ?: FinishReason.Unified.Other,
                raw = finishReason,
            ),
            usage = usage?.toUsage(rawUsage) ?: Usage(),
            providerMetadata = metadata,
            response = ResponseInfo(
                metadata = ResponseMetadata(
                    id = id,
                    timestamp = created?.let { it * MILLIS_PER_SECOND },
                    modelId = model,
                ),
            ),
        ),
    )
}

/**
 * The reference's `convertXaiChatUsage`.
 *
 * `prompt_tokens` INCLUDES the cached share unless the cached count is larger than it — the arithmetic
 * tell that xAI reported them separately — and `completion_tokens` never includes reasoning, so the
 * output total is the sum.
 */
private fun XaiBatchChatUsage.toUsage(raw: JsonObject?): Usage {
    val cacheRead = promptTokensDetails?.cachedTokens ?: 0
    val reasoning = completionTokensDetails?.reasoningTokens ?: 0
    val promptIncludesCached = cacheRead <= promptTokens
    return Usage(
        inputTokens = Usage.InputTokens(
            total = if (promptIncludesCached) promptTokens else promptTokens + cacheRead,
            noCache = if (promptIncludesCached) promptTokens - cacheRead else promptTokens,
            cacheRead = cacheRead,
            cacheWrite = null,
        ),
        outputTokens = Usage.OutputTokens(
            total = completionTokens + reasoning,
            text = completionTokens,
            reasoning = reasoning,
        ),
        raw = raw,
    )
}

private const val MILLIS_PER_SECOND = 1000L
