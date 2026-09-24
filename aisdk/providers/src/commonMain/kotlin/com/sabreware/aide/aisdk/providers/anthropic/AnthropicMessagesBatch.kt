package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.BatchCancelResult
import com.sabreware.aide.aisdk.BatchError
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.BatchListItem
import com.sabreware.aide.aisdk.BatchListOptions
import com.sabreware.aide.aisdk.BatchListResult
import com.sabreware.aide.aisdk.BatchOperationOptions
import com.sabreware.aide.aisdk.BatchRequest
import com.sabreware.aide.aisdk.BatchRequestCounts
import com.sabreware.aide.aisdk.BatchStartOptions
import com.sabreware.aide.aisdk.BatchStartResult
import com.sabreware.aide.aisdk.BatchStatus
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.mergedFor
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic's Message Batches API — `POST /v1/messages/batches`, half price, results within a day.
 *
 * Every request body is built by the SAME [AnthropicRequestBuilder] a live call uses, and every stored
 * result is decoded by feeding the finished message back through the SAME [AnthropicStreamMapper] as a
 * sequence of synthetic stream events — see [toSyntheticEvents]. That is the house rule ("one wire path
 * that is right beats two that drift") applied to the one endpoint that has no stream: a signature,
 * a redacted block or a server-tool result decodes identically whether it arrived live or from a batch,
 * because the same code decodes both.
 *
 * Three request shapes are REJECTED at start rather than degraded, because their decode half depends on
 * state that no longer exists when results are fetched independently later: the JSON-tool
 * structured-output fallback, aliased provider-tool names, and per-request `anthropicBeta` (betas apply
 * to the whole batch or not at all). A fourth is rejected because the endpoint has no form for it at
 * all: an image request. Every request may name its own model; one that does not runs on [modelId].
 */
internal class AnthropicMessagesBatchModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val supportsNativeStructuredOutput: Boolean = true,
    private val optionsNamespace: String? = null,
) : BatchLanguageModel {

    override val provider: String = ANTHROPIC_PROVIDER_ID

    private val http = http.withErrorStructure(AnthropicErrorStructure)

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
        // The reference asserts every request's type before it validates anything else, so a batch with
        // one image request fails on that request, not on an id further down the list.
        val requests = options.requests.map { request ->
            when (request) {
                is BatchRequest.Text -> request
                is BatchRequest.Image -> throw UnsupportedFunctionalityError(
                    functionality = "batch request type: ${request.type.wireName}",
                    message = "The Anthropic Message Batches API does not support batch requests " +
                        "with type \"${request.type.wireName}\".",
                )
            }
        }
        validateRequestIds(requests.map { it.id })

        val warnings = mutableListOf<BatchStartResult.RequestWarning>()
        if (options.webhookUrl != null) {
            warnings += BatchStartResult.RequestWarning(
                Warning.Unsupported(
                    feature = "webhookUrl",
                    details = "The Anthropic Message Batches API does not support completion webhooks.",
                ),
            )
        }

        val batchBetas = batchLevelBetas(options.providerOptions).toMutableSet()
        val prepared = requests.map { request ->
            if (batchLevelBetas(request.options.providerOptions).isNotEmpty()) {
                throw UnsupportedFunctionalityError(
                    functionality = "per-request providerOptions.anthropic.anthropicBeta",
                    message = "Anthropic Message Batches do not support per-request betas " +
                        "(request \"${request.id}\"). Set anthropicBeta on the batch instead.",
                )
            }

            val built = AnthropicRequestBuilder.build(
                request.modelId ?: modelId,
                request.options,
                supportsNativeStructuredOutput,
                optionsNamespace,
            )
            if (built.usesJsonResponseTool) {
                throw UnsupportedFunctionalityError(
                    functionality = "batch responseFormat JSON-tool fallback",
                    message = "Anthropic Message Batches cannot decode the JSON-tool " +
                        "structured-output fallback (request \"${request.id}\"): results are " +
                        "retrieved independently of the start call. Use a model with native " +
                        "structured outputs.",
                )
            }
            request.options.tools.orEmpty()
                .filterIsInstance<Tool.ProviderDefined>()
                .firstOrNull { built.toolNames.toProviderToolName(it.name) != it.name }
                ?.let { aliased ->
                    throw UnsupportedFunctionalityError(
                        functionality = "aliased provider tool names in batches",
                        message = "Anthropic Message Batches cannot restore the custom " +
                            "provider-tool name \"${aliased.name}\" (request \"${request.id}\") " +
                            "when results are retrieved later. Use the canonical tool name.",
                    )
                }

            // The builder always builds for the streaming endpoint; a batch param set is the same
            // request with the stream flag gone.
            val body = JsonObject(built.body.filterKeys { it != "stream" })
            validateBatchBody(body, request.id)

            batchBetas += built.betas
            built.warnings.forEach { warnings += BatchStartResult.RequestWarning(it, request.id) }
            buildJsonObject {
                put("custom_id", request.id)
                put("params", body)
            }
        }

        val result = http.postJson(
            url = batchUrl(),
            body = buildJsonObject { put("requests", JsonArray(prepared)) },
            headers = startHeaders(batchBetas, options.headers),
        )
        val batch =
            ProviderJson.decodeFromJsonElement(AnthropicBatchResponse.serializer(), result.value)

        return BatchStartResult(
            batchId = batch.id,
            status = batch.toStatus(),
            warnings = warnings,
        )
    }

    override suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus =
        retrieve(options).toStatus()

    /**
     * `POST /v1/messages/batches/{id}/cancel`, with an empty object for a body.
     *
     * The answer is the batch itself, now `canceling`; it is decoded to make sure the vendor said so,
     * and the result is empty as the reference's is — the caller polls [doGetBatchStatus] for the
     * settled state, since a cancel is an ask and items already running may still finish.
     */
    override suspend fun doCancelBatch(options: BatchOperationOptions): BatchCancelResult {
        val result = http.postJson(
            url = "${batchUrl()}/${options.batchId.encodeURLPathPart()}/cancel",
            body = JsonObject(emptyMap()),
            headers = combineHeaders(headers, options.headers),
        )
        ProviderJson.decodeFromJsonElement(AnthropicBatchResponse.serializer(), result.value)
        return BatchCancelResult()
    }

    /**
     * `GET /v1/messages/batches?limit=&after_id=` — one page, newest first, each entry normalized the
     * way a status read is. The cursor is the page's `last_id`, and only while `has_more` says a next
     * page exists; the vendor's `first_id` is not surfaced, since paging here only goes forward.
     */
    override suspend fun doListBatches(options: BatchListOptions): BatchListResult {
        val query = listOfNotNull(
            options.limit?.let { "limit=$it" },
            options.cursor?.let { "after_id=${it.encodeURLParameter()}" },
        )
        val url = if (query.isEmpty()) batchUrl() else "${batchUrl()}?${query.joinToString("&")}"
        val result = http.getJson(url, combineHeaders(headers, options.headers))
        val page = ProviderJson.decodeFromJsonElement(AnthropicBatchListResponse.serializer(), result.value)
        return BatchListResult(
            batches = page.data.map { BatchListItem(batchId = it.id, status = it.toStatus()) },
            nextCursor = page.lastId.takeIf { page.hasMore },
        )
    }

    override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> = flow {
        val batch = retrieve(options)
        if (batch.toStatus().state == BatchStatus.State.Pending) {
            throw InvalidArgumentError(
                message = "Anthropic batch \"${options.batchId}\" is not complete.",
                argument = "batchId",
            )
        }
        if (batch.archivedAt != null) {
            throw InvalidArgumentError(
                message = "Anthropic batch \"${options.batchId}\" results are no longer available.",
                argument = "batchId",
            )
        }
        val resultsUrl = batch.resultsUrl ?: throw InvalidResponseDataError(
            message = "Anthropic batch \"${options.batchId}\" completed without batch output.",
        )

        // The results URL is the vendor's own, fetched with credentials only because it stays on the
        // API origin — getBytes drops them the moment a redirect leaves it.
        val lines = http.getBytes(
            url = resultsUrl,
            headers = combineHeaders(headers, options.headers),
            trustedOrigin = baseUrl,
        ).value.decodeToString().lineSequence().filter { it.isNotBlank() }

        for (line in lines) emit(line.toItemResult())
    }

    private suspend fun retrieve(options: BatchOperationOptions): AnthropicBatchResponse {
        val url = "${batchUrl()}/${options.batchId.encodeURLPathPart()}"
        val result = http.getJson(url, combineHeaders(headers, options.headers))
        return ProviderJson.decodeFromJsonElement(AnthropicBatchResponse.serializer(), result.value)
    }

    private fun batchUrl(): String = "$baseUrl/messages/batches"

    /** The model's headers, the caller's, and ONE deduplicated `anthropic-beta`. */
    private fun startHeaders(betas: Set<String>, callHeaders: Map<String, String>?): Map<String, String> =
        combineHeaders(
            headers,
            callHeaders,
            if (betas.isEmpty()) emptyMap() else mapOf("anthropic-beta" to betas.joinToString(",")),
        )

    /** `anthropicBeta` from the canonical namespace merged with [optionsNamespace] — see [mergedFor]. */
    private fun batchLevelBetas(providerOptions: ProviderOptions?): Set<String> {
        val merged = providerOptions.mergedFor(ANTHROPIC_PROVIDER_ID, optionsNamespace)
        return (merged["anthropicBeta"] as? JsonArray)
            ?.mapNotNull { it.stringOrNull() }
            ?.toSet()
            .orEmpty()
    }
}

/** Batch request ids are echoed as `custom_id`; Anthropic constrains them to this shape. */
private val BATCH_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

private fun validateRequestIds(ids: List<String>) {
    val seen = mutableSetOf<String>()
    for (id in ids) {
        if (!BATCH_ID_PATTERN.matches(id)) {
            throw InvalidArgumentError(
                message = "Anthropic batch request ID \"$id\" must match ^[A-Za-z0-9_-]{1,64}$.",
                argument = "requests",
            )
        }
        if (!seen.add(id)) {
            throw InvalidArgumentError(
                message = "Anthropic batch request IDs must be unique; duplicate ID \"$id\".",
                argument = "requests",
            )
        }
    }
}

/** `speed` steers live latency routing and has no meaning for a queued batch; the API rejects it. */
private fun validateBatchBody(body: JsonObject, requestId: String) {
    if (body["speed"] != null && body["speed"] != JsonNull) {
        throw UnsupportedFunctionalityError(
            functionality = "providerOptions.anthropic.speed",
            message = "Anthropic Message Batches do not support speed (request \"$requestId\").",
        )
    }
    val fallbackSpeed = (body["fallbacks"] as? JsonArray)?.any { fallback ->
        val speed = (fallback as? JsonObject)?.get("speed")
        speed != null && speed != JsonNull
    } == true
    if (fallbackSpeed) {
        throw UnsupportedFunctionalityError(
            functionality = "providerOptions.anthropic.fallbacks[].speed",
            message = "Anthropic Message Batches do not support fallback speed " +
                "(request \"$requestId\").",
        )
    }
}

// ---- Status ---------------------------------------------------------------------------------------

/** `GET /v1/messages/batches/{id}` (and the start response — same shape). */
@Serializable
internal data class AnthropicBatchResponse(
    val id: String,
    @SerialName("processing_status") val processingStatus: String,
    @SerialName("request_counts") val requestCounts: AnthropicBatchRequestCounts? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("cancel_initiated_at") val cancelInitiatedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("results_url") val resultsUrl: String? = null,
)

/** `GET /v1/messages/batches`: one page of batches and where the next one starts. */
@Serializable
internal data class AnthropicBatchListResponse(
    val data: List<AnthropicBatchResponse> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null,
)

@Serializable
internal data class AnthropicBatchRequestCounts(
    val processing: Int = 0,
    val succeeded: Int = 0,
    val errored: Int = 0,
    val canceled: Int = 0,
    val expired: Int = 0,
)

/**
 * `ended` is the only terminal word Anthropic has — a batch never fails wholesale, its items do — so
 * [BatchStatus.State.Failed] is unreachable here and everything not ended is still pending.
 */
private fun AnthropicBatchResponse.toStatus(): BatchStatus = BatchStatus(
    state = if (processingStatus == "ended") BatchStatus.State.Completed else BatchStatus.State.Pending,
    rawStatus = processingStatus,
    requestCounts = requestCounts?.let {
        BatchRequestCounts(
            total = it.processing + it.succeeded + it.errored + it.canceled + it.expired,
            pending = it.processing,
            completed = it.succeeded,
            failed = it.errored + it.canceled + it.expired,
        )
    },
    createdAt = createdAt,
    expiresAt = expiresAt,
    providerMetadata = mapOf(
        ANTHROPIC_PROVIDER_ID to buildJsonObject {
            put("archivedAt", archivedAt)
            put("cancelInitiatedAt", cancelInitiatedAt)
            put("endedAt", endedAt)
            put("resultsUrl", resultsUrl)
            requestCounts?.let {
                put(
                    "requestCounts",
                    buildJsonObject {
                        put("processing", it.processing)
                        put("succeeded", it.succeeded)
                        put("errored", it.errored)
                        put("canceled", it.canceled)
                        put("expired", it.expired)
                    },
                )
            }
        },
    ),
)

// ---- Results --------------------------------------------------------------------------------------

/** One JSONL line: `{"custom_id": …, "result": {…}}`. */
@Serializable
internal data class AnthropicBatchResultLine(
    @SerialName("custom_id") val customId: String,
    val result: JsonObject? = null,
)

/** The per-item result envelope. */
@Serializable
internal data class AnthropicBatchResult(
    val type: String,
    val message: AnthropicBatchMessage? = null,
    val error: AnthropicBatchErrorEnvelope? = null,
)

@Serializable
internal data class AnthropicBatchErrorEnvelope(
    val error: AnthropicError? = null,
    @SerialName("request_id") val requestId: String? = null,
)

/** A COMPLETE message, as a batch stores it — the non-streaming twin of the SSE event sequence. */
@Serializable
internal data class AnthropicBatchMessage(
    val id: String? = null,
    val model: String? = null,
    val role: String? = null,
    val content: List<AnthropicContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_details") val stopDetails: AnthropicStopDetails? = null,
    val usage: AnthropicUsage? = null,
    val container: AnthropicContainer? = null,
    @SerialName("context_management") val contextManagement: AnthropicContextManagement? = null,
    /** What the thinking-binding controls dropped before inference — see [AnthropicInputTransformation]. */
    @SerialName("input_transformations") val inputTransformations: List<AnthropicInputTransformation>? = null,
)

private suspend fun String.toItemResult(): BatchItemResult {
    val line = runCatching {
        ProviderJson.decodeFromString(AnthropicBatchResultLine.serializer(), this)
    }.getOrElse {
        throw InvalidResponseDataError(message = "Anthropic returned an unparseable batch result line.")
    }
    val result = line.result?.let { envelope ->
        runCatching {
            ProviderJson.decodeFromJsonElement(AnthropicBatchResult.serializer(), envelope)
        }.getOrNull()
    } ?: return invalidItem(line.customId)

    return when (result.type) {
        "canceled" -> BatchItemResult.Cancelled(line.customId)
        "expired" -> BatchItemResult.Expired(line.customId)
        "errored" -> BatchItemResult.Failed(
            id = line.customId,
            error = BatchError(
                message = result.error?.error?.message ?: "Anthropic batch request failed.",
                type = result.error?.error?.type,
            ),
            providerMetadata = result.error?.requestId?.let {
                mapOf(ANTHROPIC_PROVIDER_ID to buildJsonObject { put("requestId", it) })
            },
        )
        "succeeded" -> result.message?.let { BatchItemResult.Succeeded(line.customId, it.toGenerateResult()) }
            ?: invalidItem(line.customId)
        else -> invalidItem(line.customId)
    }
}

private fun invalidItem(id: String): BatchItemResult.Failed = BatchItemResult.Failed(
    id = id,
    error = BatchError(
        message = "Anthropic returned an invalid Message batch result.",
        code = "invalid_response",
    ),
)

// ---- Decoding a stored message through the live mapper --------------------------------------------

/**
 * Replays a finished message as the event sequence its live stream WOULD have been, so
 * [AnthropicStreamMapper] — the one implementation that gets signatures, redacted blocks and
 * server-tool results right — decodes batch results too.
 *
 * The only synthesis beyond re-framing: a complete `thinking` block carries its `signature` as a field,
 * where the stream delivers it as a `signature_delta` — so that one delta is reconstructed.
 */
internal fun AnthropicBatchMessage.toSyntheticEvents(): List<AnthropicStreamEvent> = buildList {
    add(
        AnthropicStreamEvent(
            type = "message_start",
            message = AnthropicStreamMessage(
                id = id,
                model = model,
                role = role,
                usage = usage,
                container = container,
                inputTransformations = inputTransformations,
            ),
        ),
    )
    content.forEachIndexed { index, block ->
        add(AnthropicStreamEvent(type = "content_block_start", index = index, contentBlock = block))
        block.signature?.let {
            add(
                AnthropicStreamEvent(
                    type = "content_block_delta",
                    index = index,
                    delta = AnthropicDelta(type = "signature_delta", signature = it),
                ),
            )
        }
        add(AnthropicStreamEvent(type = "content_block_stop", index = index))
    }
    add(
        AnthropicStreamEvent(
            type = "message_delta",
            delta = AnthropicDelta(
                stopReason = stopReason,
                stopDetails = stopDetails,
                container = container,
            ),
            usage = usage,
            contextManagement = contextManagement,
        ),
    )
    add(AnthropicStreamEvent(type = "message_stop"))
}

private suspend fun AnthropicBatchMessage.toGenerateResult(): GenerateResult {
    val assembled = assembleGenerateResult(
        AnthropicStreamMapper(sourceUrl = "anthropic-batch").map(
            toSyntheticEvents().map { AnthropicFrame(it) }.asFlow(),
            warnings = emptyList(),
        ),
    )
    return assembled.copy(content = assembled.content.withCitations(content))
}

/**
 * Attaches each stored text block's citations to the assembled text part it became, and surfaces web
 * citations as sources — the part of the reference's batch decode the stream mapper has no event for.
 *
 * Document-location citations stay metadata-only: batch retrieval no longer knows the prompt's document
 * order, so a `document_index` cannot be resolved to a title without guessing (the reference makes the
 * same call).
 */
private fun List<Content>.withCitations(blocks: List<AnthropicContentBlock>): List<Content> {
    val citationsPerText = blocks.filter { it.type == "text" }.map { it.citations.orEmpty() }
    if (citationsPerText.all { it.isEmpty() }) return this

    val sourceIds = IdGenerator("src_")
    var textIndex = 0
    return flatMap { part ->
        if (part !is Content.Text) return@flatMap listOf(part)
        val citations = citationsPerText.getOrElse(textIndex) { emptyList() }
        textIndex++
        if (citations.isEmpty()) return@flatMap listOf(part)

        val annotated = part.copy(
            providerMetadata = mergeAnthropicMetadata(
                part.providerMetadata,
                buildJsonObject { put("citations", JsonArray(citations)) },
            ),
        )
        listOf(annotated) + citations.mapNotNull { it.toWebSource(sourceIds) }
    }
}

private fun JsonObject.toWebSource(ids: IdGenerator): Content.Source? {
    if (this["type"]?.stringOrNull() != "web_search_result_location") return null
    val url = this["url"]?.stringOrNull() ?: return null
    return Content.Source.Url(
        id = ids.next(),
        url = url,
        title = this["title"]?.stringOrNull(),
        providerMetadata = mapOf(
            ANTHROPIC_PROVIDER_ID to buildJsonObject {
                this@toWebSource["cited_text"]?.let { put("citedText", it) }
                this@toWebSource["encrypted_index"]?.let { put("encryptedIndex", it) }
            },
        ),
    )
}

private fun mergeAnthropicMetadata(existing: ProviderMetadata?, addition: JsonObject): ProviderMetadata {
    val current = existing?.get(ANTHROPIC_PROVIDER_ID) ?: JsonObject(emptyMap())
    return (existing ?: emptyMap()) + (ANTHROPIC_PROVIDER_ID to JsonObject(current + addition))
}
