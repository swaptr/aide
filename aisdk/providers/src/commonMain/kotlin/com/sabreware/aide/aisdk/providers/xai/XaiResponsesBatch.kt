package com.sabreware.aide.aisdk.providers.xai

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
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openai.XaiResponsesQuirks
import com.sabreware.aide.aisdk.providers.openai.buildResponsesRequest
import com.sabreware.aide.aisdk.providers.openai.fileTtlSeconds
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * xAI's Batch API over the Responses wire — a JSONL of `/v1/responses` requests, uploaded as a file,
 * turned into a batch, collected page by page.
 *
 * Every request body is built by the SAME [buildResponsesRequest] a live call uses, with the same
 * [XaiResponsesQuirks], so a tool table or a finish-reason rule fixed for the live path is fixed here
 * too. The results, however, do NOT come back in the Responses shape: xAI stores every text batch
 * result as a Chat Completions document (`chat_get_completion`), including for requests it received at
 * the Responses endpoint — the vendor's batch guide says so and the reference decodes exactly that. So
 * the decode half is a small Chat Completions reader ([XaiBatchResults.kt]) rather than the Responses
 * output mapper, which would be given a document it has never seen.
 *
 * Two lifecycle facts xAI leaves implicit are made explicit in [toStatus]: a batch has no status word of
 * its own, so completion is inferred from the counters, and expiry is inferred from `expire_time`
 * against the clock — which is why [now] is injected.
 *
 * A batch may also carry IMAGE requests — a line against `/v1/images/generations` (or `/edits` with
 * input images) whose stored result is an `image_generation` document — and each request names its own
 * model, so one file may mix Grok text models with `grok-imagine-image`. Cancellation
 * (`/batches/{id}:cancel`) and listing (`/batches?pagination_token=`) are both served.
 */
internal class XaiResponsesBatchModel(
    override val modelId: String,
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    /** Epoch millis. A batch whose `expire_time` is at or before this has expired. */
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : BatchLanguageModel {

    override val provider: String = XAI_PROVIDER_ID

    override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
        val warnings = mutableListOf<BatchStartResult.RequestWarning>()
        if (options.webhookUrl != null) {
            warnings += BatchStartResult.RequestWarning(
                Warning.Unsupported(
                    feature = "webhookUrl",
                    details = "The xAI Batch API does not support per-batch webhook URLs.",
                ),
            )
        }

        val inputFileExpiresAfter = options.providerOptions?.get(XAI_PROVIDER_ID)?.get("inputFileExpiresAfter")
            ?.takeIf { it != JsonNull }
            ?.fileTtlSeconds("inputFileExpiresAfter", "xAI")

        val jsonl = StringBuilder()
        for (request in options.requests) {
            val prepared = prepareRequest(request)
            val line = buildJsonObject {
                put("custom_id", request.id)
                put("method", "POST")
                put("url", prepared.endpoint)
                put("body", prepared.body)
            }
            jsonl.append(ProviderJson.encodeToString(JsonElement.serializer(), line)).append('\n')
            prepared.warnings.forEach { warnings += BatchStartResult.RequestWarning(it, request.id) }
        }

        val callHeaders = combineHeaders(headers, options.headers)
        val upload = http.postMultipartParts(
            url = "$baseUrl/files",
            // xAI reads `expires_after` only if it precedes the file part — see XaiFiles.
            parts = listOfNotNull(
                inputFileExpiresAfter?.let { MultipartPart.Field("expires_after", it.toString()) },
                MultipartPart.File(
                    field = "file",
                    fileName = XAI_BATCH_FILENAME,
                    bytes = jsonl.toString().encodeToByteArray(),
                    contentType = "application/jsonl",
                ),
            ),
            headers = callHeaders,
        )
        val file = ProviderJson.decodeFromJsonElement(XaiFileResponse.serializer(), upload.value)

        val created = http.postJson(
            url = "$baseUrl/batches",
            body = buildJsonObject {
                put("name", XAI_BATCH_NAME)
                put("input_file_id", file.id)
            },
            headers = callHeaders,
        )
        val batch = ProviderJson.decodeFromJsonElement(XaiBatchResponse.serializer(), created.value)

        return BatchStartResult(
            batchId = batch.batchId,
            // The upload the batch reads from, so a caller can find, extend or delete it.
            status = batch.toStatus(
                now(),
                extraMetadata = buildJsonObject {
                    put("inputFileId", file.id)
                    file.expiresAt?.let { put("inputFileExpiresAt", Instant.fromEpochSeconds(it).isoWithMillis()) }
                },
            ),
            warnings = warnings,
        )
    }

    /** One line of the JSONL: a Responses body for a text request, an image body for an image one. */
    private suspend fun prepareRequest(request: BatchRequest): PreparedBatchLine = when (request) {
        is BatchRequest.Text -> {
            val built = buildResponsesRequest(
                modelId = request.modelId ?: modelId,
                options = request.options,
                stream = false,
                namespace = XAI_PROVIDER_ID,
                quirks = XaiResponsesQuirks,
            )
            PreparedBatchLine(XAI_BATCH_ENDPOINT, built.body, built.warnings)
        }
        is BatchRequest.Image -> prepareImageRequest(request.modelId ?: modelId, request.imageOptions)
    }

    override suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus =
        retrieve(options).toStatus(now())

    /** An ask, not a guarantee: the batch reads pending until xAI stamps its `cancel_time`. */
    override suspend fun doCancelBatch(options: BatchOperationOptions): BatchCancelResult {
        http.postJson(
            url = "${batchUrl(options.batchId)}:cancel",
            body = JsonObject(emptyMap()),
            headers = combineHeaders(headers, options.headers),
        )
        return BatchCancelResult()
    }

    /** One page of `/batches`, cursored by xAI's `pagination_token`. */
    override suspend fun doListBatches(options: BatchListOptions): BatchListResult {
        val query = buildList {
            options.limit?.let { add("limit=$it") }
            options.cursor?.let { add("pagination_token=${it.encodeURLParameter()}") }
        }.joinToString("&")
        val result = http.getJson(
            url = "$baseUrl/batches" + (if (query.isEmpty()) "" else "?$query"),
            headers = combineHeaders(headers, options.headers),
        )
        val page = ProviderJson.decodeFromJsonElement(XaiBatchListResponse.serializer(), result.value)
        val nowMillis = now()
        return BatchListResult(
            batches = page.batches.map { BatchListItem(batchId = it.batchId, status = it.toStatus(nowMillis)) },
            nextCursor = page.paginationToken,
        )
    }

    override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> = flow {
        val batch = retrieve(options)
        if (batch.toStatus(now()).state == BatchStatus.State.Pending) {
            throw InvalidArgumentError(
                message = "xAI batch \"${options.batchId}\" is not complete.",
                argument = "batchId",
            )
        }

        var paginationToken: String? = null
        do {
            val query = buildString {
                append("limit=").append(XAI_BATCH_RESULTS_PAGE_SIZE)
                paginationToken?.let { append("&pagination_token=").append(it.encodeURLParameter()) }
            }
            val result = http.getJson(
                url = "${batchUrl(options.batchId)}/results?$query",
                headers = combineHeaders(headers, options.headers),
            )
            val page = ProviderJson.decodeFromJsonElement(XaiBatchResultsPage.serializer(), result.value)
            for (item in page.results) {
                // An image stored by URL is fetched from xAI's own origin, credentials attached.
                emit(item.toItemResult { url -> http.getBytes(url, headers, trustedOrigin = baseUrl).value })
            }
            // An empty token is treated as the last page: the reference would re-request with
            // `pagination_token=` forever, and a server that sends "" for "no more" is the likelier reading.
            paginationToken = page.paginationToken?.takeIf { it.isNotEmpty() }
        } while (paginationToken != null)
    }

    private suspend fun retrieve(options: BatchOperationOptions): XaiBatchResponse {
        val result = http.getJson(batchUrl(options.batchId), combineHeaders(headers, options.headers))
        return ProviderJson.decodeFromJsonElement(XaiBatchResponse.serializer(), result.value)
    }

    private fun batchUrl(batchId: String): String = "$baseUrl/batches/${batchId.encodeURLPathPart()}"
}

/** One JSONL line before it is serialized: the path xAI routes it on, its body, and what it could not honour. */
private class PreparedBatchLine(val endpoint: String, val body: JsonObject, val warnings: List<Warning>)

/**
 * An image generation as a batch line — the reference's image half of `prepareRequest`.
 *
 * Grok Imagine takes an aspect ratio rather than a size, so `size` is refused with the same message the
 * live image model gives; `seed` and `mask` have no field either. The vendor's own options ride from
 * `providerOptions.xai` under their wire names (`output_format`, `sync_mode`, `resolution`, `quality`,
 * `user`, and `aspect_ratio` when the call named none). Input images route the line to `/edits`.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun prepareImageRequest(modelId: String, options: ImageCallOptions): PreparedBatchLine {
    val warnings = mutableListOf<Warning>()
    if (options.size != null) {
        warnings += Warning.Unsupported("size", "This model does not support the `size` option. Use `aspectRatio` instead.")
    }
    if (options.seed != null) warnings += Warning.Unsupported("seed")
    if (options.mask != null) warnings += Warning.Unsupported("mask")

    val vendor = options.providerOptions?.get(XAI_PROVIDER_ID)
    val imageUrls = options.files.orEmpty().map { file ->
        when (file) {
            is ImageFile.Url -> file.url
            is ImageFile.Data -> "data:${file.mediaType};base64," + when (val data = file.data) {
                is BinaryData.Base64 -> data.value
                is BinaryData.Bytes -> Base64.encode(data.value)
            }
        }
    }
    val body = buildJsonObject {
        put("model", modelId)
        options.prompt?.let { put("prompt", it) }
        put("n", options.n)
        put("response_format", "b64_json")
        (options.aspectRatio ?: vendor?.optString("aspect_ratio"))?.let { put("aspect_ratio", it) }
        vendor?.optString("output_format")?.let { put("output_format", it) }
        vendor?.optBoolean("sync_mode")?.let { put("sync_mode", it) }
        vendor?.optString("resolution")?.let { put("resolution", it) }
        vendor?.optString("quality")?.let { put("quality", it) }
        vendor?.optString("user")?.let { put("user", it) }
        if (imageUrls.size == 1) {
            putJsonObject("image") {
                put("url", imageUrls.single())
                put("type", "image_url")
            }
        } else if (imageUrls.size > 1) {
            put(
                "images",
                buildJsonArray {
                    imageUrls.forEach { url ->
                        add(
                            buildJsonObject {
                                put("url", url)
                                put("type", "image_url")
                            },
                        )
                    }
                },
            )
        }
    }
    return PreparedBatchLine(
        endpoint = if (imageUrls.isEmpty()) XAI_IMAGE_GENERATIONS_ENDPOINT else XAI_IMAGE_EDITS_ENDPOINT,
        body = body,
        warnings = warnings,
    )
}

/** The reference's `Date.toISOString()`: milliseconds always present. */
internal fun Instant.isoWithMillis(): String {
    val iso = toString()
    return if ('.' in iso) iso else iso.removeSuffix("Z") + ".000Z"
}

/** The `url` each JSONL line names — xAI's own path, whatever base URL the provider was given. */
private const val XAI_BATCH_ENDPOINT = "/v1/responses"
private const val XAI_IMAGE_GENERATIONS_ENDPOINT = "/v1/images/generations"
private const val XAI_IMAGE_EDITS_ENDPOINT = "/v1/images/edits"

/** The name the batch is created under; the reference's, so a dashboard groups both SDKs' batches. */
private const val XAI_BATCH_NAME = "ai-sdk-text-batch"

private const val XAI_BATCH_FILENAME = "batch.jsonl"

/** xAI's page-size ceiling; anything larger is clamped server-side and pages anyway. */
private const val XAI_BATCH_RESULTS_PAGE_SIZE = 1000

// ---- Status ---------------------------------------------------------------------------------------

/** `POST /v1/batches` and `GET /v1/batches/{id}` — the same object. */
@Serializable
internal data class XaiBatchResponse(
    @SerialName("batch_id") val batchId: String,
    val name: String? = null,
    @SerialName("create_time") val createTime: String? = null,
    @SerialName("expire_time") val expireTime: String? = null,
    @SerialName("cancel_time") val cancelTime: String? = null,
    @SerialName("cancel_by_xai_message") val cancelByXaiMessage: String? = null,
    val state: XaiBatchState? = null,
)

/** `GET /v1/batches` — one page. */
@Serializable
internal data class XaiBatchListResponse(
    val batches: List<XaiBatchResponse> = emptyList(),
    @SerialName("pagination_token") val paginationToken: String? = null,
)

/** xAI's counters. Errors and cancellations are separate here and folded together in the neutral counts. */
@Serializable
internal data class XaiBatchState(
    @SerialName("num_requests") val numRequests: Int? = null,
    @SerialName("num_pending") val numPending: Int? = null,
    @SerialName("num_success") val numSuccess: Int? = null,
    @SerialName("num_error") val numError: Int? = null,
    @SerialName("num_cancelled") val numCancelled: Int? = null,
)

/**
 * The three neutral states, inferred — xAI reports no status word.
 *
 * Cancelled (a `cancel_time` or a cancellation message) or expired is [BatchStatus.State.Failed] with
 * a coded error; otherwise consistent counters with nothing pending mean completed, and everything else
 * is still pending. Counters that do not add up are dropped rather than reported, because a poller
 * that reads `pending == 0` off an inconsistent snapshot would fetch results that are not there.
 */
internal fun XaiBatchResponse.toStatus(nowMillis: Long, extraMetadata: JsonObject = JsonObject(emptyMap())): BatchStatus {
    val counts = normalizeRequestCounts(
        total = state?.numRequests,
        pending = state?.numPending,
        completed = state?.numSuccess,
        failed = state?.let { s ->
            if (s.numError != null && s.numCancelled != null) s.numError + s.numCancelled else null
        },
    )
    val cancelled = cancelTime != null || cancelByXaiMessage != null
    val expired = expireTime.isAtOrBefore(nowMillis)
    val stateValue = when {
        cancelled || expired -> BatchStatus.State.Failed
        counts != null && counts.total > 0 && counts.pending == 0 -> BatchStatus.State.Completed
        else -> BatchStatus.State.Pending
    }
    val error = when {
        cancelled -> BatchError(
            message = cancelByXaiMessage ?: "xAI batch \"$batchId\" was cancelled.",
            code = "batch_cancelled",
        )
        expired -> BatchError(message = "xAI batch \"$batchId\" expired.", code = "batch_expired")
        else -> null
    }
    return BatchStatus(
        state = stateValue,
        requestCounts = counts,
        error = error,
        createdAt = createTime,
        expiresAt = expireTime,
        // The error/cancelled split survives only here: the neutral counts fold both into `failed`.
        providerMetadata = mapOf(
            XAI_PROVIDER_ID to buildJsonObject {
                name?.let { put("name", it) }
                cancelTime?.let { put("cancelTime", it) }
                cancelByXaiMessage?.let { put("cancelByXaiMessage", it) }
                state?.let { s ->
                    put(
                        "state",
                        buildJsonObject {
                            s.numRequests?.let { put("numRequests", it) }
                            s.numPending?.let { put("numPending", it) }
                            s.numSuccess?.let { put("numSuccess", it) }
                            s.numError?.let { put("numError", it) }
                            s.numCancelled?.let { put("numCancelled", it) }
                        },
                    )
                }
                extraMetadata.forEach { (key, value) -> put(key, value) }
            },
        ),
    )
}

/**
 * The reference's `normalizeBatchRequestCounts`: every count present, non-negative, and adding up.
 *
 * Null otherwise — an inconsistent snapshot is reported as no snapshot rather than as a wrong one.
 */
private fun normalizeRequestCounts(total: Int?, pending: Int?, completed: Int?, failed: Int?): BatchRequestCounts? {
    if (total == null || pending == null || completed == null || failed == null) return null
    if (total < 0 || pending < 0 || completed < 0 || failed < 0) return null
    if (pending + completed + failed != total) return null
    return BatchRequestCounts(total = total, pending = pending, completed = completed, failed = failed)
}

/** An unparseable timestamp is "not expired" — the reference's `NaN` rule, and the only safe reading. */
private fun String?.isAtOrBefore(nowMillis: Long): Boolean {
    val value = this ?: return false
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return false
    return instant.toEpochMilliseconds() <= nowMillis
}
