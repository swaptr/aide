package com.sabreware.aide.aisdk.providers.google

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
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.IdGenerator
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Google's cap on a batch input file. A JVM array cannot reach it, but the refusal names the real limit. */
private const val GOOGLE_BATCH_INPUT_FILE_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024

/** Past this the creation body stops being inline requests and becomes an uploaded JSONL file. */
private const val GOOGLE_BATCH_INLINE_CREATION_MAX_BYTES: Long = 20_000_000L

private const val JSONL_MEDIA_TYPE = "application/jsonl"

/**
 * Gemini's batch API — `POST models/{model}:batchGenerateContent`, half price, results within a day.
 *
 * Every request body is built by the SAME builder a live call uses, and every stored result is decoded by
 * feeding the finished response back through the SAME part mapper as the stream — see
 * [GoogleLanguageModel.convertResponse]. That is the house rule applied to the one Gemini surface that
 * has no stream: a thought signature, a generated image or a code-execution result decodes identically
 * whether it arrived live or a day later, because one piece of code decodes both.
 *
 * Two shapes of submission, chosen by SIZE rather than by the caller. Requests ride inline in the creation
 * body while it stays under 20 MB; past that they become a JSONL file uploaded through the resumable
 * Files API and the batch is created against the file name. Google answers by the same rule — results
 * come back inline or as a file to download — and the caller sees one batch either way.
 *
 * Webhooks are supported natively (`webhookConfig.uris`), so unlike Anthropic's there is no warning to
 * raise for one.
 *
 * A request names its own model, and every request in a batch must name the same one, because the model
 * is part of the endpoint (`models/{model}:batchGenerateContent`); a request naming none runs on
 * [modelId]. An image request is the same body an image call sends — see [googleImageCall] — and a
 * stored result carrying an image decodes as an image result, whatever kind of request produced it, the
 * way the reference reads it back: results are keyed by id, and Google does not say which kind was asked.
 */
internal class GoogleBatchModel(
    override val modelId: String,
    http: ProviderHttp,
    private val baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
    /** Resolved per call, for the reason [GoogleLanguageModel] gives: Vertex bearers expire hourly. */
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
    /** Names the batch on Google's side, `ai-sdk-batch-<id>`. Injectable so a test can pin the name. */
    private val generateId: () -> String = IdGenerator()::next,
) : BatchLanguageModel {

    override val provider: String = GOOGLE_PROVIDER_ID

    private val http = http.withErrorStructure(GoogleErrorStructure)

    /** The live model, for its request builder and its part mapper — never for a call. */
    private val languageModel = GoogleLanguageModel(modelId, http, baseUrl, headers)

    /** The live model for the batch's own model id, which every request must share. */
    private fun languageModel(batchModelId: String): GoogleLanguageModel =
        if (batchModelId == modelId) languageModel else GoogleLanguageModel(batchModelId, http, baseUrl, headers)

    override suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult {
        val batchModelId = batchModelId(options.requests)
        val builder = languageModel(batchModelId)
        val warnings = mutableListOf<BatchStartResult.RequestWarning>()
        val displayName = "ai-sdk-batch-${generateId()}"
        val submission = GoogleBatchSubmission(displayName, options.webhookUrl)

        for (request in options.requests) {
            val (call, imageWarnings) = when (request) {
                is BatchRequest.Text -> request.options to emptyList()
                is BatchRequest.Image -> googleImageCall(batchModelId, request.imageOptions)
            }
            val built = builder.buildRequest(call)
            submission.add(request.id, built.body)
            (imageWarnings + built.warnings).forEach { warnings += BatchStartResult.RequestWarning(it, request.id) }
        }

        val callHeaders = combineHeaders(headers(), options.headers)
        val createUrl = "$baseUrl/${googleModelPath(batchModelId)}:batchGenerateContent"
        submission.inlineBody()?.let { body ->
            val operation = http.postJson(createUrl, body, callHeaders).value.toOperation()
            return BatchStartResult(batchId = operation.name, status = operation.toStatus(), warnings = warnings)
        }

        val uploaded = uploadInputFile(submission.fileBytes(), displayName, callHeaders)
        val operation = http.postJson(createUrl, submission.fileBody(uploaded.name), callHeaders).value.toOperation()
        // The uploaded input is the caller's to find, extend or delete — so its name and expiry are
        // reported under the provider key, the contract every file-staging provider shares.
        val inputFile = mapOf(
            GOOGLE_PROVIDER_ID to buildJsonObject {
                put("inputFileId", uploaded.name)
                uploaded.expirationTime?.let { put("inputFileExpiresAt", it) }
            },
        )
        return BatchStartResult(
            batchId = operation.name,
            status = operation.toStatus().copy(providerMetadata = inputFile),
            warnings = warnings,
        )
    }

    override suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus =
        retrieve(options).toStatus()

    /** `POST batches/{id}:cancel` with an empty body; Google answers with an empty object. */
    override suspend fun doCancelBatch(options: BatchOperationOptions): BatchCancelResult {
        http.postJson(
            url = "$baseUrl/${options.batchId}:cancel",
            body = JsonObject(emptyMap()),
            headers = combineHeaders(headers(), options.headers),
        )
        return BatchCancelResult()
    }

    /** `GET batches?pageSize=&pageToken=`: one page of operations, each read as a status. */
    override suspend fun doListBatches(options: BatchListOptions): BatchListResult {
        val query = listOfNotNull(
            options.limit?.let { "pageSize=$it" },
            options.cursor?.let { "pageToken=" + it.encodeURLParameter() },
        ).joinToString("&")
        val page = http.getJson(
            url = "$baseUrl/batches" + (if (query.isEmpty()) "" else "?$query"),
            headers = combineHeaders(headers(), options.headers),
        ).value.let { ProviderJson.decodeFromJsonElement(GoogleBatchListPage.serializer(), it) }
        return BatchListResult(
            batches = page.operations.orEmpty().map { BatchListItem(it.name, it.toStatus()) },
            nextCursor = page.nextPageToken,
        )
    }

    override fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult> = flow {
        val operation = retrieve(options)
        val status = operation.toStatus()
        if (status.state == BatchStatus.State.Pending) {
            throw InvalidArgumentError(
                message = "Google batch \"${options.batchId}\" is not complete.",
                argument = "batchId",
            )
        }

        // The output lands under `metadata.output` while the operation is running and under `response`
        // once it is done; Google has been seen filling either, so both are read.
        val inlined = operation.metadata?.output?.inlinedResponses?.inlinedResponses
            ?: operation.response?.inlinedResponses?.inlinedResponses
        if (inlined != null) {
            for (item in inlined) {
                emit(GoogleBatchResultLine(item.metadata.key, item.response, item.error).toItemResult())
            }
            return@flow
        }

        val responsesFile = operation.metadata?.output?.responsesFile ?: operation.response?.responsesFile
        if (responsesFile == null) {
            if (status.state == BatchStatus.State.Completed) {
                throw InvalidResponseDataError(
                    message = "Google batch \"${options.batchId}\" completed without batch output.",
                    data = ProviderJson.encodeToJsonElement(GoogleBatchOperation.serializer(), operation),
                )
            }
            // A failed batch with nothing to show is an empty result set, not an error: the failure is
            // already on the status, and a poller reading results after seeing it wants the list.
            return@flow
        }

        // Every path segment is encoded on its own — the file name is Google's, and a name carrying a `?`
        // or a `#` would otherwise rewrite the URL it is embedded in.
        val encodedFile = responsesFile.split('/').joinToString("/") { it.encodeURLParameter() }
        // Streamed line by line: a results file is one response per line and can run to hundreds of
        // megabytes, so nothing larger than one line is ever held.
        http.getLines(
            url = "${baseOrigin()}/download/v1beta/$encodedFile:download?alt=media",
            headers = combineHeaders(headers(), options.headers),
            trustedOrigin = baseUrl,
        ).collect { line -> emit(line.toResultLine().toItemResult()) }
    }

    private suspend fun retrieve(options: BatchOperationOptions): GoogleBatchOperation =
        http.getJson("$baseUrl/${options.batchId}", combineHeaders(headers(), options.headers))
            .value.toOperation()

    /**
     * The resumable upload, in Google's three steps: open a session, send the bytes, finalize.
     *
     * The session-open reply carries the upload URL in a HEADER and has no body, which is why it goes
     * out as a bytes request rather than a JSON one. The upload itself carries NO provider headers: the
     * session URL is pre-authorized, and the reference sends none.
     */
    private suspend fun uploadInputFile(
        bytes: ByteArray,
        displayName: String,
        callHeaders: Map<String, String>,
    ): GoogleUploadedFile {
        if (bytes.size.toLong() > GOOGLE_BATCH_INPUT_FILE_MAX_BYTES) {
            throw InvalidArgumentError(
                message = "Google batch input files must not exceed 2 GB.",
                argument = "requests",
            )
        }

        val session = http.postBytesForBytes(
            url = "${baseOrigin()}/upload/v1beta/files",
            body = buildJsonObject {
                putJsonObject("file") { put("display_name", "$displayName-input") }
            },
            headers = combineHeaders(
                callHeaders,
                mapOf(
                    "X-Goog-Upload-Protocol" to "resumable",
                    "X-Goog-Upload-Command" to "start",
                    "X-Goog-Upload-Header-Content-Length" to bytes.size.toString(),
                    "X-Goog-Upload-Header-Content-Type" to JSONL_MEDIA_TYPE,
                ),
            ),
        )
        val uploadUrl = session.headers["x-goog-upload-url"]
            ?: throw InvalidResponseDataError(message = "Google did not return a resumable upload URL.")

        val uploaded = http.postRawBytes(
            url = uploadUrl,
            payload = bytes,
            contentType = JSONL_MEDIA_TYPE,
            headers = mapOf(
                "X-Goog-Upload-Offset" to "0",
                "X-Goog-Upload-Command" to "upload, finalize",
            ),
        )
        return ProviderJson.decodeFromJsonElement(GoogleFileUploadResponse.serializer(), uploaded.value).file
    }

    /** The Files endpoints hang off the host, not off `/v1beta` — they carry their own version. */
    private fun baseOrigin(): String = baseUrl.removeSuffix("/v1beta")

    private suspend fun GoogleBatchResultLine.toItemResult(): BatchItemResult {
        error?.let { rpc ->
            val batchError = rpc.toBatchError("Google batch request failed.")
            // gRPC code 1 is CANCELLED; a numeric code arrives on some surfaces and the name on others.
            return if (rpc.status == "CANCELLED" || batchError.code == "1") {
                BatchItemResult.Cancelled(key, batchError)
            } else {
                BatchItemResult.Failed(key, batchError)
            }
        }
        val payload = response?.takeIf { it !is JsonNull }
            ?: return failed(
                "Google returned a batch result without a response or error.",
                "invalid_batch_result",
            )
        blockedItem(payload)?.let { return it }

        val chunk = runCatching {
            ProviderJson.decodeFromJsonElement(GoogleResponseChunk.serializer(), payload)
        }.getOrNull()
            ?: return failed("Google returned an invalid GenerateContent batch result.", "invalid_response")

        val result = languageModel.convertResponse(chunk, warnings = emptyList())
        // A result carrying an image is an image request's answer: Google keys results by id alone and
        // never says which kind was asked, so the reference reads the kind off the content, as here.
        if (result.content.any { it.isGeneratedImage() }) {
            val imageModelId = result.response?.metadata?.modelId ?: modelId
            return BatchItemResult.ImageSucceeded(key, result.toGoogleImageResult(imageModelId, emptyList<Warning>()))
        }
        result.content.firstOrNull { !it.isBatchSupported() }?.let { part ->
            return failed(
                "Google returned a \"${part.batchTypeName()}\" content block, " +
                    "but that content is not supported in AI SDK text batches.",
                "unsupported_content",
            )
        }
        return BatchItemResult.Succeeded(key, result)
    }

    private fun GoogleBatchResultLine.failed(message: String, code: String): BatchItemResult.Failed =
        BatchItemResult.Failed(id = key, error = BatchError(message = message, code = code))
}

// ---- Submission -----------------------------------------------------------------------------------

/**
 * The creation body, accumulating requests inline until the 20 MB limit and spilling to JSONL past it.
 *
 * The size is tracked INCREMENTALLY — the bytes of the empty envelope, then each request's bytes plus its
 * separating comma — rather than by re-serializing the whole body per request, because a batch is exactly
 * the case where that body is large. The limit is compared against the bytes Google will receive, which
 * is why it is counted in UTF-8 and not in characters.
 */
private class GoogleBatchSubmission(private val displayName: String, private val webhookUrl: String?) {

    private val inlined = mutableListOf<JsonObject>()
    private var inlineBytes: Long = envelope(emptyList()).encodedSize()
    private var fileLines: StringBuilder? = null

    fun add(key: String, request: JsonObject) {
        val lines = fileLines
        if (lines != null) {
            lines.appendLine(key, request)
            return
        }
        val entry = buildJsonObject {
            put("request", request)
            putJsonObject("metadata") { put("key", key) }
        }
        val next = inlineBytes + entry.encodedSize() + (if (inlined.isNotEmpty()) 1 else 0)
        if (next < GOOGLE_BATCH_INLINE_CREATION_MAX_BYTES) {
            inlined += entry
            inlineBytes = next
            return
        }
        // Over the line: everything so far becomes the first lines of the file, this request the next.
        val spilled = StringBuilder()
        inlined.forEach { previous ->
            val previousKey = (previous["metadata"] as JsonObject)["key"] as JsonPrimitive
            spilled.appendLine(previousKey.content, previous["request"] as JsonObject)
        }
        inlined.clear()
        spilled.appendLine(key, request)
        fileLines = spilled
    }

    /** The inline creation body, or null once the requests have spilled to a file. */
    fun inlineBody(): JsonObject? = if (fileLines == null) envelope(inlined) else null

    fun fileBytes(): ByteArray = checkNotNull(fileLines).toString().encodeToByteArray()

    fun fileBody(fileName: String): JsonObject = batch {
        putJsonObject("inputConfig") { put("fileName", fileName) }
    }

    private fun envelope(requests: List<JsonObject>): JsonObject = batch {
        putJsonObject("inputConfig") {
            putJsonObject("requests") { put("requests", JsonArray(requests)) }
        }
    }

    private fun batch(inputConfig: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            putJsonObject("batch") {
                put("displayName", displayName)
                webhookUrl?.let { putJsonObject("webhookConfig") { putJsonArray("uris") { add(it) } } }
                inputConfig()
            }
        }

    /** One JSONL line: `{"key": …, "request": …}` and a newline. */
    private fun StringBuilder.appendLine(key: String, request: JsonObject) {
        val line = buildJsonObject {
            put("key", key)
            put("request", request)
        }
        append(ProviderJson.encodeToString(JsonElement.serializer(), line)).append('\n')
    }

    /** The wire size of one entry, counted rather than allocated: the bytes are never needed here. */
    private fun JsonElement.encodedSize(): Long =
        ProviderJson.encodeToString(JsonElement.serializer(), this).utf8Length()
}

// ---- Wire types -----------------------------------------------------------------------------------

/** A long-running operation: the start reply and the `GET batches/{id}` reply are the same shape. */
@Serializable
internal data class GoogleBatchOperation(
    val name: String,
    val metadata: GoogleBatchMetadata? = null,
    val done: Boolean? = null,
    val error: GoogleRpcStatus? = null,
    val response: GoogleBatchOutput? = null,
)

@Serializable
internal data class GoogleBatchMetadata(
    val state: String? = null,
    @SerialName("createTime") val createTime: String? = null,
    @SerialName("batchStats") val batchStats: GoogleBatchStats? = null,
    val output: GoogleBatchOutput? = null,
)

/**
 * Request counts, as int64 — which JSON has no type for, so Google sends them as STRINGS. A number is
 * accepted too; both are read by [parseCount].
 */
@Serializable
internal data class GoogleBatchStats(
    @SerialName("requestCount") val requestCount: JsonElement? = null,
    @SerialName("successfulRequestCount") val successfulRequestCount: JsonElement? = null,
    @SerialName("failedRequestCount") val failedRequestCount: JsonElement? = null,
    @SerialName("pendingRequestCount") val pendingRequestCount: JsonElement? = null,
)

@Serializable
internal data class GoogleBatchOutput(
    @SerialName("responsesFile") val responsesFile: String? = null,
    @SerialName("inlinedResponses") val inlinedResponses: GoogleInlinedResponses? = null,
)

@Serializable
internal data class GoogleInlinedResponses(
    @SerialName("inlinedResponses") val inlinedResponses: List<GoogleInlinedResponse> = emptyList(),
)

@Serializable
internal data class GoogleInlinedResponse(
    val metadata: GoogleInlinedResponseKey,
    val response: JsonElement? = null,
    val error: GoogleRpcStatus? = null,
)

@Serializable
internal data class GoogleInlinedResponseKey(val key: String)

/** `google.rpc.Status`. [code] is a number on the wire and a string on some surfaces; read either. */
@Serializable
internal data class GoogleRpcStatus(
    val code: JsonElement? = null,
    val message: String? = null,
    val status: String? = null,
)

@Serializable
internal data class GoogleFileUploadResponse(val file: GoogleUploadedFile)

@Serializable
internal data class GoogleUploadedFile(
    val name: String,
    /** When Google deletes the upload — reported back so the caller can extend or clean it up. */
    @SerialName("expirationTime") val expirationTime: String? = null,
)

/** `GET batches`: a page of operations and the token for the next one. */
@Serializable
internal data class GoogleBatchListPage(
    val operations: List<GoogleBatchOperation>? = null,
    @SerialName("nextPageToken") val nextPageToken: String? = null,
)

/** One line of the results file — and, once normalized, one inline result too. */
@Serializable
internal data class GoogleBatchResultLine(
    val key: String,
    val response: JsonElement? = null,
    val error: GoogleRpcStatus? = null,
)

private fun JsonElement.toOperation(): GoogleBatchOperation =
    ProviderJson.decodeFromJsonElement(GoogleBatchOperation.serializer(), this)

private fun String.toResultLine(): GoogleBatchResultLine = runCatching {
    ProviderJson.decodeFromString(GoogleBatchResultLine.serializer(), this)
}.getOrElse {
    throw InvalidResponseDataError(message = "Google returned an unparseable batch result line.")
}

// ---- Status ---------------------------------------------------------------------------------------

private val STATE_PREFIX = Regex("^(?:BATCH|JOB)_STATE_")

/**
 * The operation's lifecycle, in the three states a poller branches on.
 *
 * A top-level RPC error is the batch failing wholesale and outranks any state word. Without a state the
 * operation's own `done` flag decides. Google spells the state with a `BATCH_STATE_` prefix on the Gemini
 * API and `JOB_STATE_` on Vertex; the word after it is what is read.
 */
private fun GoogleBatchOperation.toStatus(): BatchStatus {
    val rawStatus = metadata?.state
    val batchError = error?.toBatchError("Google batch failed.")
    val state = when {
        batchError != null -> BatchStatus.State.Failed
        rawStatus == null -> if (done == true) BatchStatus.State.Completed else BatchStatus.State.Pending
        else -> when (STATE_PREFIX.replaceFirst(rawStatus, "")) {
            "SUCCEEDED" -> BatchStatus.State.Completed
            "FAILED", "CANCELLED", "EXPIRED" -> BatchStatus.State.Failed
            else -> BatchStatus.State.Pending
        }
    }
    return BatchStatus(
        state = state,
        rawStatus = rawStatus,
        requestCounts = metadata?.batchStats.toRequestCounts(),
        error = batchError,
        createdAt = metadata?.createTime,
    )
}

/**
 * The counts, or null unless every one is a non-negative integer and the items add up to the total.
 *
 * Google omits a zero counter rather than sending `0`, so the three item counts default to zero; the
 * total does not, because a batch that reports no total has reported nothing. A set that does not add up
 * is reported as absent rather than as a wrong number — the reference's `normalizeBatchRequestCounts`.
 */
private fun GoogleBatchStats?.toRequestCounts(): BatchRequestCounts? {
    val total = parseCount(this?.requestCount) ?: return null
    val completed = parseCount(this?.successfulRequestCount.orZero()) ?: return null
    val failed = parseCount(this?.failedRequestCount.orZero()) ?: return null
    val pending = parseCount(this?.pendingRequestCount.orZero()) ?: return null
    return BatchRequestCounts(total = total, pending = pending, completed = completed, failed = failed)
        .takeIf { pending + completed + failed == total }
}

private fun JsonElement?.orZero(): JsonElement = this?.takeIf { it !is JsonNull } ?: JsonPrimitive(0)

private val DIGITS = Regex("^\\d+$")

/** A count from a JSON number or a digit string; anything else — negative, fractional, prose — is null. */
private fun parseCount(value: JsonElement?): Int? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (primitive.isString && !DIGITS.matches(primitive.content)) return null
    return primitive.content.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
}

/**
 * The one model the batch runs on.
 *
 * The model is part of the batch endpoint, so a batch cannot mix models; a request that names none
 * runs on the batch model's own. Both refusals are the reference's, message for message.
 */
private fun GoogleBatchModel.batchModelId(requests: List<BatchRequest>): String {
    val first = requests.firstOrNull()?.let { it.modelId ?: modelId }
        ?: throw InvalidArgumentError(message = "Google batches require at least one request.", argument = "requests")
    if (requests.any { (it.modelId ?: modelId) != first }) {
        throw InvalidArgumentError(
            message = "Google batches require every request to use the same model because the model is " +
                "part of the batch endpoint.",
            argument = "requests",
        )
    }
    return first
}

private fun GoogleRpcStatus.toBatchError(fallback: String): BatchError = BatchError(
    message = message ?: fallback,
    type = status,
    code = (code as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
)

// ---- Results --------------------------------------------------------------------------------------

/**
 * A response with no candidates, which is what a blocked prompt looks like.
 *
 * Read off the raw payload BEFORE the full decode, because a blocked response is a valid response with
 * nothing in it, and decoding it into an empty result would report a success that never generated
 * anything. Null when the payload is not this shape — an object with a missing or empty candidate list.
 */
private fun GoogleBatchResultLine.blockedItem(payload: JsonElement): BatchItemResult.Failed? {
    val response = payload as? JsonObject ?: return null
    val empty = when (val candidates = response["candidates"]) {
        null, is JsonNull -> true
        is JsonArray -> candidates.isEmpty()
        else -> return null
    }
    if (!empty) return null
    val feedback = response["promptFeedback"]?.takeIf { it !is JsonNull }
    val promptFeedback = when (feedback) {
        null -> null
        is JsonObject -> feedback
        else -> return null
    }
    val reason = promptFeedback?.get("blockReason")?.takeIf { it !is JsonNull }
    val blockReason = when (reason) {
        null -> null
        is JsonPrimitive -> if (reason.isString) reason.content else return null
        else -> return null
    }
    return BatchItemResult.Failed(
        id = key,
        error = BatchError(
            message = if (blockReason == null) {
                "Google returned a batch response without any candidates."
            } else {
                "Google blocked the batch request ($blockReason)."
            },
            type = blockReason,
            code = if (blockReason == null) "invalid_response" else "prompt_blocked",
        ),
        providerMetadata = promptFeedback?.let {
            mapOf(
                GOOGLE_PROVIDER_ID to buildJsonObject {
                    putJsonObject("promptFeedback") {
                        put("blockReason", blockReason?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                },
            )
        },
    )
}

/** Text, reasoning, sources and tool traffic: what a text batch can carry. A file fails the item. */
private fun Content.isBatchSupported(): Boolean =
    this is Content.Text || this is Content.Reasoning || this is Content.Source ||
        this is Content.ToolCall || this is Content.ToolResult

/** An image the model generated, as inline bytes — what makes a stored result an image result. */
private fun Content.isGeneratedImage(): Boolean =
    this is Content.File && mediaType.startsWith("image/") && data is FileData.Bytes

/** The reference's type word for a content part, for the message that names what was refused. */
private fun Content.batchTypeName(): String = when (this) {
    is Content.Text -> "text"
    is Content.Reasoning -> "reasoning"
    is Content.File -> "file"
    is Content.ReasoningFile -> "reasoning-file"
    is Content.ToolCall -> "tool-call"
    is Content.ToolResult -> "tool-result"
    is Content.ToolApprovalRequest -> "tool-approval-request"
    is Content.Source -> "source"
    is Content.Custom -> "custom"
}


/** UTF-8 byte length without encoding: what `encodeToByteArray().size` answers, minus the copy. */
private fun String.utf8Length(): Long {
    var size = 0L
    var i = 0
    while (i < length) {
        val c = this[i]
        size += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return size
}
