package com.sabreware.aide.aisdk.providers.openai

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
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ResponseInfo
import com.sabreware.aide.aisdk.ResponseMetadata
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonElement
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The endpoint every batch line names. It is the API PATH the Batch API routes on, fixed by OpenAI
 * rather than derived from the base URL — a proxy in front of OpenAI still forwards `/v1/responses`.
 */
private const val BATCH_ENDPOINT = "/v1/responses"

/** The only completion window the Batch API offers today. */
private const val BATCH_COMPLETION_WINDOW = "24h"

/**
 * How long the uploaded input file lives by default: twice the completion window, so it still exists
 * for a caller inspecting a failed batch the next day, and no longer, so a nightly pipeline does not
 * accumulate them. `providerOptions.openai.inputFileExpiresAfter` overrides it, within the file store's
 * one-hour-to-thirty-day range.
 */
private const val BATCH_INPUT_FILE_EXPIRES_AFTER_SECONDS = 48L * 60 * 60

private const val BATCH_INPUT_FILENAME = "batch.jsonl"
private const val BATCH_INPUT_CONTENT_TYPE = "application/jsonl"

private const val MILLIS_PER_SECOND = 1000L

/**
 * OpenAI's Batch API over the Responses endpoint — many requests uploaded as a JSONL file, run within
 * a day at half price, collected as another file.
 *
 * Every request body is built by the SAME [buildResponsesRequest] a live call uses, and every stored
 * result is decoded by the SAME [toContent] the live `doGenerate` uses, so a reasoning item's
 * `encrypted_content`, a web-search citation or a tool call decodes identically whether it arrived live
 * or from a batch — the house rule the Anthropic batch already follows. **This is where the port departs
 * from the reference:** `openai-responses-batch.ts` fails any result that carries a tool call or a
 * provider-executed item (`unsupported_content`), because its batch runtime is text-only. The
 * [BatchLanguageModel] contract here promises "exactly what a live `doGenerate` would have returned",
 * so the whole output is decoded instead; a caller that cannot use a tool call in a batch result can
 * see it and say so, where a refusal would have thrown the model's answer away.
 *
 * What CANNOT be restored later is refused at start rather than degraded: an aliased provider-tool name.
 * Results are retrieved by a process that no longer holds the request's tool list, so the decode runs
 * with identity name mapping, and a `web_search` declared under a custom name would come back under
 * the wire name the caller never registered.
 *
 * The lifecycle is four HTTP calls, each its own failure: upload the JSONL (`/files`), create the batch
 * (`/batches`), poll it (`/batches/{id}`), and read the output and error files (`/files/{id}/content`).
 * A finished batch may have BOTH files — the output file holds every request that reached the model,
 * including the ones it answered with a 4xx, and the error file holds the ones the batch itself refused
 * (cancelled, expired) — so both are read, in that order. Cancellation (`/batches/{id}/cancel`) and
 * listing (`/batches?after=`) are the two optional operations, both served.
 *
 * Text only, and one model per batch: an image request is refused before anything is uploaded — the
 * contract's own `BatchRequest.options` says so for every text-only provider — and a request naming a
 * different model than its neighbours is refused too, because the Batch API runs one model per file.
 */
internal class OpenAIResponsesBatchModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val generateId: () -> String = IdGenerator("src_")::next,
) : BatchLanguageModel {

    override val provider: String = OPENAI_PROVIDER_ID

    private val http = http.withErrorStructure(OpenAIErrorStructure)

    override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
        val warnings = mutableListOf<BatchStartResult.RequestWarning>()
        if (options.webhookUrl != null) {
            warnings += BatchStartResult.RequestWarning(
                Warning.Unsupported(
                    feature = "webhookUrl",
                    details = "The OpenAI Batch API does not support per-batch webhook URLs.",
                ),
            )
        }

        // Both refusals happen BEFORE the upload: a text-only provider reading an image request's
        // `options` is the contract's rejection, and one model per batch is the API's rule.
        val requests = options.requests.map { it to it.options }
        val batchModelId = requests.firstOrNull()?.first?.modelId ?: modelId
        requests.forEach { (request, _) ->
            val requestModel = request.modelId ?: modelId
            if (requestModel != batchModelId) {
                throw InvalidArgumentError(
                    message = "The OpenAI Batch API requires all requests in a batch to use the same model. " +
                        "Found \"$batchModelId\" and \"$requestModel\".",
                    argument = "requests",
                )
            }
        }
        val inputFileExpiresAfter = options.providerOptions?.get(OPENAI_PROVIDER_ID)?.get("inputFileExpiresAfter")
            ?.takeIf { it != JsonNull }
            ?.fileTtlSeconds("inputFileExpiresAfter", "OpenAI")
            ?: BATCH_INPUT_FILE_EXPIRES_AFTER_SECONDS

        val jsonl = buildString {
            requests.forEach { (request, callOptions) ->
                // The builder builds for the unary endpoint here; a queued request cannot stream.
                val built = buildResponsesRequest(batchModelId, callOptions, stream = false)
                rejectAliasedProviderTools(request, callOptions, built)
                built.warnings.forEach { warnings += BatchStartResult.RequestWarning(it, request.id) }
                append(
                    ProviderJson.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("custom_id", request.id)
                            put("method", "POST")
                            put("url", BATCH_ENDPOINT)
                            put("body", built.body)
                        },
                    ),
                )
                append('\n')
            }
        }

        val callHeaders = combineHeaders(headers, options.headers)
        val upload = http.postMultipartParts(
            url = "$baseUrl/files",
            parts = listOf(
                MultipartPart.File(
                    field = "file",
                    fileName = BATCH_INPUT_FILENAME,
                    bytes = jsonl.encodeToByteArray(),
                    contentType = BATCH_INPUT_CONTENT_TYPE,
                ),
                MultipartPart.Field("purpose", "batch"),
            ) + expiresAfterFields(inputFileExpiresAfter),
            headers = callHeaders,
        )
        val inputFile = ProviderJson.decodeFromJsonElement(OpenAIFileResponse.serializer(), upload.value)

        val created = http.postJson(
            url = "$baseUrl/batches",
            body = buildJsonObject {
                put("input_file_id", inputFile.id)
                put("endpoint", BATCH_ENDPOINT)
                put("completion_window", BATCH_COMPLETION_WINDOW)
            },
            headers = callHeaders,
        )
        val batch = ProviderJson.decodeFromJsonElement(OpenAIBatchResponse.serializer(), created.value)

        return BatchStartResult(
            batchId = batch.id,
            // The upload the batch reads from, so a caller can find, extend or delete it.
            status = batch.toStatus(
                extraMetadata = buildJsonObject {
                    put("inputFileId", inputFile.id)
                    inputFile.expiresAt?.let { put("inputFileExpiresAt", openAIBatchTimestamp(it)) }
                },
            ),
            warnings = warnings,
        )
    }

    override suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus =
        retrieve(options).batch.toStatus()

    /** An ask, not a guarantee: the batch reads `cancelling` until OpenAI settles it. */
    override suspend fun doCancelBatch(options: BatchOperationOptions): BatchCancelResult {
        http.postJson(
            url = "$baseUrl/batches/${options.batchId.encodeURLPathPart()}/cancel",
            body = JsonObject(emptyMap()),
            headers = combineHeaders(headers, options.headers),
        )
        return BatchCancelResult()
    }

    /** One page of `/batches`, cursored by OpenAI's `after` — the last id of the previous page. */
    override suspend fun doListBatches(options: BatchListOptions): BatchListResult {
        val query = buildList {
            options.limit?.let { add("limit=$it") }
            options.cursor?.let { add("after=${it.encodeURLParameter()}") }
        }.joinToString("&")
        val result = http.getJson(
            url = "$baseUrl/batches" + (if (query.isEmpty()) "" else "?$query"),
            headers = combineHeaders(headers, options.headers),
        )
        val page = ProviderJson.decodeFromJsonElement(OpenAIBatchListResponse.serializer(), result.value)
        return BatchListResult(
            batches = page.data.map { BatchListItem(batchId = it.id, status = it.toStatus()) },
            nextCursor = page.lastId.takeIf { page.hasMore },
        )
    }

    override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> = flow {
        val retrieved = retrieve(options)
        val status = retrieved.batch.toStatus()
        if (status.state == BatchStatus.State.Pending) {
            throw InvalidArgumentError(
                message = "OpenAI batch \"${options.batchId}\" is not complete.",
                argument = "batchId",
            )
        }
        val fileIds = listOfNotNull(retrieved.batch.outputFileId, retrieved.batch.errorFileId)
        if (status.state == BatchStatus.State.Completed && fileIds.isEmpty()) {
            throw InvalidResponseDataError(
                message = "OpenAI batch \"${options.batchId}\" completed without batch output.",
                data = retrieved.raw,
            )
        }
        // A FAILED batch with no files has nothing to report and reports nothing — the failure itself is
        // on the status, which the caller read to get here.

        val callHeaders = combineHeaders(headers, options.headers)
        // Identity name mapping, built once: at retrieval time there is no request to read the tool
        // list from.
        val tools = prepareTools(tools = null, toolChoice = null)
        for (fileId in fileIds) {
            val url = "$baseUrl/files/${fileId.encodeURLPathPart()}/content"
            // Our own origin, so the credentials ride along; getLines drops them if a redirect leaves it.
            // Streamed line by line: an output file is one result per line and can run to hundreds of
            // megabytes, so nothing larger than one line is ever held.
            http.getLines(url, callHeaders, trustedOrigin = baseUrl).collect { line ->
                emit(line.toItemResult(generateId, tools))
            }
        }
    }

    private suspend fun retrieve(options: BatchOperationOptions): RetrievedBatch {
        val url = "$baseUrl/batches/${options.batchId.encodeURLPathPart()}"
        val result = http.getJson(url, combineHeaders(headers, options.headers))
        return RetrievedBatch(
            batch = ProviderJson.decodeFromJsonElement(OpenAIBatchResponse.serializer(), result.value),
            raw = result.value,
        )
    }

    /** The decoded status beside the payload it came from, so a refusal can quote the vendor verbatim. */
    private class RetrievedBatch(val batch: OpenAIBatchResponse, val raw: JsonElement)
}

/**
 * A provider tool declared under a custom name cannot be restored when the results are read by a later
 * process that never saw the request — see the class doc. Refused here, where the caller can rename it.
 */
private fun rejectAliasedProviderTools(request: BatchRequest, options: CallOptions, built: BuiltResponsesRequest) {
    options.tools.orEmpty()
        .filterIsInstance<Tool.ProviderDefined>()
        .firstOrNull { built.tools.mapping.toProviderToolName(it.name) != it.name }
        ?.let { aliased ->
            throw UnsupportedFunctionalityError(
                functionality = "aliased provider tool names in batches",
                message = "OpenAI batches cannot restore the custom provider-tool name " +
                    "\"${aliased.name}\" (request \"${request.id}\") when results are retrieved " +
                    "later. Use the canonical tool name.",
            )
        }
}

// ---- Status ---------------------------------------------------------------------------------------

/** `POST /v1/batches` and `GET /v1/batches/{id}` — same shape. Ref: `openaiBatchResponseSchema`. */
@Serializable
internal data class OpenAIBatchResponse(
    val id: String,
    val status: String,
    @SerialName("output_file_id") val outputFileId: String? = null,
    @SerialName("error_file_id") val errorFileId: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("request_counts") val requestCounts: OpenAIBatchRequestCounts? = null,
    val errors: OpenAIBatchErrors? = null,
)

/** `GET /v1/batches` — one page. */
@Serializable
internal data class OpenAIBatchListResponse(
    val data: List<OpenAIBatchResponse> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("last_id") val lastId: String? = null,
)

@Serializable
internal data class OpenAIBatchRequestCounts(
    val total: Int? = null,
    val completed: Int? = null,
    val failed: Int? = null,
)

@Serializable
internal data class OpenAIBatchErrors(val data: List<OpenAIBatchErrorEntry>? = null)

@Serializable
internal data class OpenAIBatchErrorEntry(
    val code: String? = null,
    val message: String? = null,
)

/**
 * OpenAI's eight words onto the poller's three.
 *
 * `expired` and `cancelled` are FAILED, not completed: a batch in either state may still carry an output
 * file with the requests that finished before the cut-off, which is why results are still readable for
 * a failed batch, but the batch as a whole did not do what was asked. An unknown word is pending, so a
 * status OpenAI adds later is waited on rather than read as done and its partial artifacts fetched.
 */
private fun OpenAIBatchResponse.toStatus(extraMetadata: JsonObject = JsonObject(emptyMap())): BatchStatus = BatchStatus(
    state = when (status) {
        "completed" -> BatchStatus.State.Completed
        "failed", "expired", "cancelled" -> BatchStatus.State.Failed
        else -> BatchStatus.State.Pending
    },
    rawStatus = status,
    requestCounts = requestCounts?.normalized(),
    error = errors?.data?.firstOrNull()?.let { first ->
        BatchError(message = first.message ?: "OpenAI batch failed.", code = first.code)
    },
    createdAt = createdAt?.let(::openAIBatchTimestamp),
    expiresAt = expiresAt?.let(::openAIBatchTimestamp),
    // The two file ids are the handles a caller needs to read the raw JSONL themselves — for a batch
    // whose results this port could not decode, that is the only way at the answer.
    providerMetadata = mapOf(
        OPENAI_PROVIDER_ID to buildJsonObject {
            outputFileId?.let { put("outputFileId", it) }
            errorFileId?.let { put("errorFileId", it) }
            extraMetadata.forEach { (key, value) -> put(key, value) }
        },
    ).takeIf { outputFileId != null || errorFileId != null || extraMetadata.isNotEmpty() },
)

/**
 * The reference's `normalizeBatchRequestCounts`: every count present and non-negative, or nothing.
 *
 * OpenAI reports no pending count, so it is derived — and a derived count that goes negative means the
 * vendor's numbers do not add up, which is a reason to report none rather than a wrong one.
 */
private fun OpenAIBatchRequestCounts.normalized(): BatchRequestCounts? {
    val total = total ?: return null
    val completed = completed ?: return null
    val failed = failed ?: return null
    val pending = total - completed - failed
    if (total < 0 || completed < 0 || failed < 0 || pending < 0) return null
    return BatchRequestCounts(total = total, pending = pending, completed = completed, failed = failed)
}

/**
 * Epoch seconds in the reference's `Date.toISOString()` shape — `2023-11-14T22:13:20.000Z`, with the
 * milliseconds always present — so a batch log line reads the same from either port. `Instant`'s own
 * rendering omits a zero fraction, hence the one adjustment.
 */
internal fun openAIBatchTimestamp(epochSeconds: Long): String {
    val iso = Instant.fromEpochSeconds(epochSeconds).toString()
    return if ('.' in iso) iso else iso.removeSuffix("Z") + ".000Z"
}

// ---- Results --------------------------------------------------------------------------------------

/** One JSONL line of an output or error file. Ref: `openaiBatchResultLineSchema`. */
@Serializable
internal data class OpenAIBatchResultLine(
    @SerialName("custom_id") val customId: String,
    val response: OpenAIBatchLineResponse? = null,
    val error: OpenAIBatchLineError? = null,
)

/** The HTTP response the batch worker got for one request, status code and all. */
@Serializable
internal data class OpenAIBatchLineResponse(
    @SerialName("status_code") val statusCode: Int,
    @SerialName("request_id") val requestId: String? = null,
    val body: JsonElement? = null,
)

/** A request the BATCH refused — cancelled, expired — as opposed to one the model answered with a 4xx. */
@Serializable
internal data class OpenAIBatchLineError(
    val code: String? = null,
    val message: String? = null,
)

/**
 * One line, decoded.
 *
 * A line that is not JSON fails the stream — the file is corrupt, and a caller pairing outcomes to
 * requests by id must not be handed a shorter list than it submitted. A line that IS JSON but not a
 * result envelope is a failed ITEM, so every other line is still delivered.
 */
private suspend fun String.toItemResult(generateId: () -> String, tools: PreparedTools): BatchItemResult {
    val element = parseJsonElement(this)
    val line = runCatching {
        ProviderJson.decodeFromJsonElement(OpenAIBatchResultLine.serializer(), element)
    }.getOrElse {
        throw InvalidResponseDataError(
            message = "OpenAI returned a batch result line that is not a result envelope.",
            data = element,
            cause = it,
        )
    }
    return line.toItemResult(generateId, tools)
}

private suspend fun OpenAIBatchResultLine.toItemResult(generateId: () -> String, tools: PreparedTools): BatchItemResult {
    error?.let { refusal ->
        val batchError = BatchError(
            message = refusal.message ?: "OpenAI batch request failed.",
            code = refusal.code,
        )
        return when (refusal.code) {
            "batch_cancelled" -> BatchItemResult.Cancelled(customId, batchError)
            "batch_expired" -> BatchItemResult.Expired(customId, batchError)
            else -> BatchItemResult.Failed(customId, batchError)
        }
    }

    val response = response ?: return BatchItemResult.Failed(
        id = customId,
        error = BatchError(
            message = "OpenAI returned a batch result without a response or error.",
            code = "invalid_batch_result",
        ),
    )
    val metadata = response.requestId?.let {
        mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("requestId", it) })
    }

    if (response.statusCode !in SUCCESS_STATUS) {
        return BatchItemResult.Failed(customId, response.toHttpError(), metadata)
    }

    return when (val converted = response.body.toGenerateResult(generateId, tools)) {
        is Converted.Success -> BatchItemResult.Succeeded(customId, converted.result)
        is Converted.Failure -> BatchItemResult.Failed(customId, converted.error, metadata)
    }
}

private val SUCCESS_STATUS = 200..299

/**
 * A 4xx/5xx the model answered one request with, in OpenAI's `{"error":{…}}` envelope where it used it.
 *
 * `code` may be a number on some OpenAI-compatible hosts; the reference stringifies it and so does this.
 */
private fun OpenAIBatchLineResponse.toHttpError(): BatchError {
    val error = (body as? JsonObject)?.get("error") as? JsonObject
    val message = error?.optString("message") ?: return BatchError(
        message = "OpenAI batch request failed with status code $statusCode.",
        statusCode = statusCode,
    )
    return BatchError(
        message = message,
        type = error.optString("type"),
        code = (error["code"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        statusCode = statusCode,
    )
}

private sealed interface Converted {
    class Success(val result: GenerateResult) : Converted
    class Failure(val error: BatchError) : Converted
}

/**
 * A stored `/v1/responses` document, decoded the way the live path decodes one.
 *
 * The three failures the reference distinguishes are kept distinct because a caller retrying a batch
 * needs them to be: a body that is not a Responses document at all, a document carrying OpenAI's own
 * `error`, and a document with no `output` — the last being what an OpenAI-compatible host returns for a
 * request it accepted and then dropped.
 */
private suspend fun JsonElement?.toGenerateResult(generateId: () -> String, tools: PreparedTools): Converted {
    val response = this?.let { body ->
        runCatching { ProviderJson.decodeFromJsonElement(OpenAIResponse.serializer(), body) }.getOrNull()
    } ?: return Converted.Failure(
        BatchError(message = "OpenAI returned an invalid Responses batch result.", code = "invalid_response"),
    )

    response.error?.let {
        return Converted.Failure(BatchError(message = it.message, type = it.type, code = it.code))
    }

    val output = response.output ?: return Converted.Failure(
        BatchError(
            message = response.incompleteDetails?.reason
                ?.let { "OpenAI Responses returned no output ($it)." }
                ?: "OpenAI Responses returned no output.",
            code = "invalid_response",
        ),
    )

    val mapped = output.toContent(tools, generateId)
    return Converted.Success(
        GenerateResult(
            content = mapped.content,
            finishReason = openAIFinishReason(response.incompleteDetails?.reason, mapped.hasFunctionCall),
            usage = response.usage.decodeResponsesUsage().toUsage(raw = response.usage),
            providerMetadata = response.batchMetadata(output),
            response = ResponseInfo(
                metadata = ResponseMetadata(
                    id = response.id,
                    // OpenAI reports `created_at` in epoch SECONDS; ResponseMetadata is millis.
                    timestamp = response.createdAt?.let { it * MILLIS_PER_SECOND },
                    modelId = response.model,
                ),
            ),
        ),
    )
}

/**
 * The response id — what `previous_response_id` on a follow-up turn needs — plus what the reference
 * files beside it: the message logprobs when they were asked for, the service tier, and the reasoning
 * context a compaction produced.
 */
private fun OpenAIResponse.batchMetadata(output: List<OpenAIOutputItem>): ProviderMetadata {
    val logprobs = output
        .filter { it.type == "message" }
        .flatMap { it.content.orEmpty() }
        .mapNotNull { part -> part.logprobs?.takeIf { it !is JsonNull } }
    return mapOf(
        OPENAI_PROVIDER_ID to buildJsonObject {
            id?.let { put(OPENAI_RESPONSE_ID_KEY, it) }
            if (logprobs.isNotEmpty()) put("logprobs", JsonArray(logprobs))
            serviceTier?.let { put("serviceTier", it) }
            reasoning?.context?.let { put("reasoningContext", it) }
        },
    )
}
