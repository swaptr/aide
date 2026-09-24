package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.BatchCancelResult
import com.sabreware.aide.aisdk.BatchError
import com.sabreware.aide.aisdk.BatchItemResult
import com.sabreware.aide.aisdk.BatchLanguageModel
import com.sabreware.aide.aisdk.BatchListOptions
import com.sabreware.aide.aisdk.BatchOperationOptions
import com.sabreware.aide.aisdk.BatchRequest
import com.sabreware.aide.aisdk.BatchRequestType
import com.sabreware.aide.aisdk.BatchStartOptions
import com.sabreware.aide.aisdk.BatchStartResult
import com.sabreware.aide.aisdk.BatchStatus
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ProviderOptions
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.withRetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------------
// The runtime side of durable batches — the reference's `ai/src/batch/`.
//
// The spec's BatchLanguageModel takes finished CallOptions / ImageCallOptions and hands back raw results;
// these wrappers are what make a batch request the same thing as a live one. Each text request goes
// through the SAME standardizePrompt and validated() the loop uses, so a prompt that would be a 400 live
// is refused at submission rather than surfacing a day later as a failed item; and each result comes
// back in the runtime's shape — a Step, or GeneratedImages — so a batch answer is read with the
// accessors a live answer is read with.
// ---------------------------------------------------------------------------------------------------

/**
 * One request in a batch, before normalization: a [TextBatchRequest] or an [ImageBatchRequest].
 *
 * The reference's `BatchRequest` union at the `ai` layer; the spec's [BatchRequest] is what it becomes
 * once its prompt is standardized and its options validated.
 */
public sealed interface BatchRequestInput {

    /** The caller's correlation id; the only way an outcome pairs back to its request. */
    public val id: String

    /**
     * The provider-specific model for THIS request. Null runs it on the batch model's own
     * [BatchLanguageModel.modelId] — a batch of one model names none.
     */
    public val model: String?
}

/**
 * One text request in a batch: a conversation plus its call settings, named by [id].
 *
 * The same inputs a single [generateText] takes for one round. Tools ride on [options] exactly as they
 * do live — the definitions are forwarded to the vendor and never executed here, because a batch cannot
 * run a tool loop: a call the model makes comes back as a [Step.toolCalls] entry for the caller to run.
 */
public data class TextBatchRequest(
    override val id: String,
    /** The conversation, validated by [standardizePrompt] before submission. */
    val prompt: Prompt,
    /** Every other call setting; its own `prompt` is replaced by the standardized [prompt]. */
    val options: CallOptions = CallOptions(prompt = prompt),
    /** Becomes the leading system turn, via [standardizePrompt]. */
    val instructions: String? = null,
    /** See [standardizePrompt]'s parameter of the same name. */
    val allowSystemInMessages: Boolean = true,
    override val model: String? = null,
) : BatchRequestInput

/**
 * One image request in a batch: what a single [generateImage] takes, named by [id].
 *
 * Forwarded as it is — an image call has no prompt to standardize — after the one check [generateImage]
 * makes, that [ImageCallOptions.n] asks for at least one image.
 */
public data class ImageBatchRequest(
    override val id: String,
    val options: ImageCallOptions,
    override val model: String? = null,
) : BatchRequestInput

/**
 * The persistable handle for a batch.
 *
 * Serializable because that is its whole purpose: a batch outlives the process that started it, and
 * [getBatchStatus] and [getBatchResults] from a later process need exactly these two fields — the id
 * to name it, the provider to refuse a lookup against a vendor that did not produce it. The model is no
 * longer part of it: a batch may span models, and a vendor finds a batch by id alone. The reference also
 * stamps a `version` on the stored shape; here the class IS the type, and a format version is a spec
 * bump's concern rather than a field to carry until then.
 */
@Serializable
public data class BatchReference(
    /** The handle the provider issued — [BatchStartResult.batchId]. */
    val id: String,
    /** The provider that holds the batch. */
    val provider: String,
)

/** The acknowledged batch: its handle, its state as of submission, and what the provider ignored. */
public data class Batch(
    /** The handle the provider issued. */
    val id: String,
    /** The provider that holds the batch. */
    val provider: String,
    /** The batch's state — [BatchStatus.State.Pending], almost always, when freshly started. */
    val status: BatchStatus,
    /** What the provider could not honour, per request or batch-wide — see [BatchStartResult.warnings]. */
    val warnings: List<BatchStartResult.RequestWarning> = emptyList(),
) {

    /** The two fields worth persisting — the rest goes stale the moment it is returned. */
    val reference: BatchReference get() = BatchReference(id, provider)
}

/** One page of [listBatches]. */
public data class ListBatchesResult(
    val batches: List<Batch>,
    /** The cursor for the next page; null when this was the last. */
    val nextCursor: String? = null,
    /** Provider-namespaced detail about the page, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
)

/**
 * The terminal outcome of one request, with a successful result already in the runtime's shape.
 *
 * A [TextBatchItem] for a text request, an [ImageBatchItem] for an image one — the reference's
 * `BatchItemResult` union, discriminated by its `type`. The three failure arms are the spec's, unchanged.
 */
public sealed interface BatchItem {

    /** The [BatchRequestInput.id] this outcome answers. */
    public val id: String
}

/**
 * The outcome of one TEXT request.
 *
 * The spec's [BatchItemResult] carries a raw [GenerateResult]; this carries a [Step], so the accessors a
 * live answer is read with — [Step.text], [files], [sources], [toolCalls] — read a batched one too.
 */
public sealed interface TextBatchItem : BatchItem {

    /**
     * The request ran. [step] is what a one-round [generateText] would have produced for it, minus the
     * tool execution a batch cannot do: a tool call in it is validated and flagged exactly as the loop
     * would, and left for the caller to run.
     */
    public data class Succeeded(override val id: String, val step: Step) : TextBatchItem

    /** The request ran and the vendor rejected or errored it. */
    public data class Failed(
        override val id: String,
        val error: BatchError,
        /** Provider-namespaced failure detail — a vendor request id — carried verbatim. */
        val providerMetadata: ProviderMetadata? = null,
    ) : TextBatchItem

    /** The batch was cancelled before this request ran. */
    public data class Cancelled(
        override val id: String,
        val error: BatchError? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : TextBatchItem

    /** The batch expired before this request ran. */
    public data class Expired(
        override val id: String,
        val error: BatchError? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : TextBatchItem
}

/**
 * The outcome of one IMAGE request.
 *
 * The spec's [BatchItemResult.ImageSucceeded] carries a raw [ImageResult]; this carries the
 * [GeneratedImages] a live [generateImage] returns, so both are read the same way.
 */
public sealed interface ImageBatchItem : BatchItem {

    /** The request ran; [images] is what a [generateImage] of it would have produced. */
    public data class Succeeded(override val id: String, val images: GeneratedImages) : ImageBatchItem

    /** The request ran and the vendor rejected or errored it. */
    public data class Failed(
        override val id: String,
        val error: BatchError,
        val providerMetadata: ProviderMetadata? = null,
    ) : ImageBatchItem

    /** The batch was cancelled before this request ran. */
    public data class Cancelled(
        override val id: String,
        val error: BatchError? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : ImageBatchItem

    /** The batch expired before this request ran. */
    public data class Expired(
        override val id: String,
        val error: BatchError? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : ImageBatchItem
}

/**
 * Submits a batch and returns the handle to keep.
 *
 * Every text request is put through [standardizePrompt] and [CallOptions.validated] first, so the batch
 * is refused HERE for a prompt with an unanswered tool call or a `maxOutputTokens` of zero — a failed
 * item a day later names neither. Ids are checked for the same reason: an outcome pairs to its request
 * by id and nothing else, so a duplicate makes two answers indistinguishable. And a tool name must mean
 * one definition across the whole batch: a vendor stores the batch's tools once, so two requests that
 * disagree about `lookup` would have one of them silently answered against the other's schema.
 *
 * A request of a type the model does not serve is refused by the model, before anything is sent — see
 * [BatchRequest.options].
 *
 * @param model the batch-capable model; a provider without one offers no [BatchLanguageModel] at all.
 * @param requests the requests to run, text and image alike, ids unique and non-blank.
 * @param providerOptions batch-wide provider options — a beta header for the whole batch, say.
 * @param headers extra HTTP headers for the submission.
 * @param webhookUrl where the provider should call when the batch settles; an unsupported warning where
 *   it cannot, never a failure.
 * @param downloadAssets fetches URL attachments the model cannot fetch itself — judged by
 *   [BatchLanguageModel.supportedUrls]; null sends URLs as they are.
 * @param timeoutMs a ceiling on the submission call.
 * @param logWarnings where the provider's warnings go, each attributed to the model of the request that
 *   raised it — see [WarningLogger].
 */
public suspend fun startBatch(
    model: BatchLanguageModel,
    requests: List<BatchRequestInput>,
    providerOptions: ProviderOptions? = null,
    headers: Map<String, String>? = null,
    webhookUrl: String? = null,
    downloadAssets: AssetDownloader? = null,
    timeoutMs: Long? = null,
    logWarnings: WarningLogger = WarningLogger.Console,
): Batch {
    requests.requireDistinctIds()
    val supportedUrls = if (downloadAssets == null) emptyMap() else model.supportedUrls()
    val toolsByName = mutableMapOf<String, Tool>()
    val normalized = requests.map { request ->
        when (request) {
            is TextBatchRequest -> {
                val standardized = standardizePrompt(
                    messages = request.prompt,
                    instructions = request.instructions,
                    deferredToolNames = request.options.tools.deferredToolNames(),
                    allowSystemInMessages = request.allowSystemInMessages,
                ).let { if (downloadAssets == null) it else it.withDownloadedAssets(supportedUrls, downloadAssets) }
                requireCompatibleTools(request.id, request.options.tools, toolsByName)
                BatchRequest.Text(request.id, request.options.validated().copy(prompt = standardized), request.model)
            }
            is ImageBatchRequest -> {
                if (request.options.n < 1) throw InvalidArgumentError("n must be at least 1.", "n")
                BatchRequest.Image(request.id, request.options, request.model)
            }
        }
    }

    val result = withOptionalTimeout(timeoutMs) {
        model.doStartBatch(BatchStartOptions(normalized, providerOptions, headers, webhookUrl))
    }
    val modelByRequestId = requests.associate { it.id to (it.model ?: model.modelId) }
    for ((warning, requestId) in result.warnings) {
        logWarnings.logIfAny(listOf(warning), model.provider, requestId?.let(modelByRequestId::get) ?: model.modelId)
    }
    return Batch(id = result.batchId, provider = model.provider, status = result.status, warnings = result.warnings)
}

/**
 * The batch's lifecycle state right now.
 *
 * @param retry loop-level retries for the status call. [RetryPolicy.None], because the provider's
 *   transport already retries — the same reasoning as [streamText]'s default.
 * @throws InvalidArgumentError if [batch] names a different provider than [model] — a status read
 *   against the wrong vendor is a 404 that reads as "your batch is gone".
 */
public suspend fun getBatchStatus(
    model: BatchLanguageModel,
    batch: BatchReference,
    providerOptions: ProviderOptions? = null,
    headers: Map<String, String>? = null,
    retry: RetryPolicy = RetryPolicy.None,
    timeoutMs: Long? = null,
): BatchStatus {
    batch.requireCompatible(model)
    return withOptionalTimeout(timeoutMs) {
        withRetry(retry) { model.doGetBatchStatus(BatchOperationOptions(batch.id, providerOptions, headers)) }
    }
}

/**
 * The per-request outcomes of a finished batch, each successful one in the runtime's shape.
 *
 * Cold, like every flow in this library: collecting it fetches the results, and the order is the
 * vendor's — pair by [BatchItem.id], never by position. The reference opens the results stream the
 * moment it is called; a cold flow is the Kotlin shape of the same operation, and the difference is
 * invisible to a caller that collects what it asked for.
 *
 * No retry policy, deliberately: a retry around a flow that has already delivered items would deliver
 * them again, and the provider's transport retries the request that opens the stream.
 *
 * @param tools the definitions the batch's requests were started with, for validating the tool calls
 *   that come back — the input is parsed against the schema and an unknown name is flagged, exactly as
 *   the loop does. Never executed. Null validates the input as JSON and checks no name, because a
 *   batch collected without its tool list is a batch whose calls the caller will route itself.
 * @throws InvalidArgumentError if [batch] names a different provider than [model], or — from the
 *   provider — if the batch is still pending or its results have expired.
 */
public fun getBatchResults(
    model: BatchLanguageModel,
    batch: BatchReference,
    tools: List<Tool>? = null,
    providerOptions: ProviderOptions? = null,
    headers: Map<String, String>? = null,
): Flow<BatchItem> = flow {
    batch.requireCompatible(model)
    model.doGetBatchResults(BatchOperationOptions(batch.id, providerOptions, headers))
        .collect { item -> emit(item.toBatchItem(tools)) }
}

/**
 * Asks the vendor to cancel the batch.
 *
 * An ask, not a guarantee: items already running may still complete, and [getBatchStatus] keeps
 * answering [BatchStatus.State.Pending] until the vendor has settled it.
 *
 * @throws UnsupportedFunctionalityError if [model] offers no cancellation — the reference's error, with
 *   its message.
 * @throws InvalidArgumentError if [batch] names a different provider than [model].
 */
public suspend fun cancelBatch(
    model: BatchLanguageModel,
    batch: BatchReference,
    providerOptions: ProviderOptions? = null,
    headers: Map<String, String>? = null,
    timeoutMs: Long? = null,
): BatchCancelResult {
    batch.requireCompatible(model)
    return withOptionalTimeout(timeoutMs) {
        model.doCancelBatch(BatchOperationOptions(batch.id, providerOptions, headers))
    } ?: throw UnsupportedFunctionalityError(
        functionality = "batch cancellation",
        message = "The provider does not support batch cancellation.",
    )
}

/**
 * One page of the vendor's batches, each as a [Batch] whose [Batch.reference] can be looked up later.
 *
 * @param limit how many batches the page may hold; null takes the vendor's default.
 * @param cursor where the previous page ended — its [ListBatchesResult.nextCursor]; null starts over.
 * @param retry loop-level retries for the listing call — see [getBatchStatus].
 * @throws UnsupportedFunctionalityError if [model] offers no listing — the reference's error, with its
 *   message.
 */
public suspend fun listBatches(
    model: BatchLanguageModel,
    providerOptions: ProviderOptions? = null,
    limit: Int? = null,
    cursor: String? = null,
    headers: Map<String, String>? = null,
    retry: RetryPolicy = RetryPolicy.None,
    timeoutMs: Long? = null,
): ListBatchesResult {
    val page = withOptionalTimeout(timeoutMs) {
        withRetry(retry) { model.doListBatches(BatchListOptions(limit, cursor, providerOptions, headers)) }
    } ?: throw UnsupportedFunctionalityError(
        functionality = "batch listing",
        message = "The provider does not support listing batches.",
    )
    return ListBatchesResult(
        batches = page.batches.map { Batch(id = it.batchId, provider = model.provider, status = it.status) },
        nextCursor = page.nextCursor,
        providerMetadata = page.providerMetadata,
    )
}

private suspend fun BatchItemResult.toBatchItem(tools: List<Tool>?): BatchItem = when (this) {
    is BatchItemResult.Succeeded -> TextBatchItem.Succeeded(id, result.toBatchStep(id, tools))
    is BatchItemResult.ImageSucceeded -> ImageBatchItem.Succeeded(id, result.toGeneratedImages())
    is BatchItemResult.Failed -> when (type) {
        BatchRequestType.Text -> TextBatchItem.Failed(id, error, providerMetadata)
        BatchRequestType.Image -> ImageBatchItem.Failed(id, error, providerMetadata)
    }
    is BatchItemResult.Cancelled -> when (type) {
        BatchRequestType.Text -> TextBatchItem.Cancelled(id, error, providerMetadata)
        BatchRequestType.Image -> ImageBatchItem.Cancelled(id, error, providerMetadata)
    }
    is BatchItemResult.Expired -> when (type) {
        BatchRequestType.Text -> TextBatchItem.Expired(id, error, providerMetadata)
        BatchRequestType.Image -> ImageBatchItem.Expired(id, error, providerMetadata)
    }
}

/**
 * A stored result as the [Step] the loop would have built from it.
 *
 * Tool calls are validated against [tools] — with none, the input is normalized and no name is checked
 * — and an unparseable one is flagged [Content.ToolCall.invalid] rather than thrown on, exactly as the
 * loop treats a call it cannot check against a schema. The step's [Step.callId] is the request id: a
 * batch item is a run of one round, and the request id is its correlation key.
 */
private suspend fun GenerateResult.toBatchStep(requestId: String, tools: List<Tool>?): Step = Step(
    content = content.map { part ->
        if (part is Content.ToolCall) validateToolCall(part, tools = tools, repair = null).call else part
    },
    finishReason = finishReason,
    usage = usage,
    warnings = warnings,
    providerMetadata = providerMetadata,
    request = request,
    response = response,
    callId = requestId,
    stepNumber = 0,
)

/** A stored image result as the [GeneratedImages] one call of [generateImage] produces. */
private fun ImageResult.toGeneratedImages(): GeneratedImages = GeneratedImages(
    images = images,
    warnings = warnings,
    usage = usage,
    providerMetadata = providerMetadata,
    responses = listOf(response),
)

/** The reference's three checks, in its order and with its messages. */
private fun List<BatchRequestInput>.requireDistinctIds() {
    if (isEmpty()) throw InvalidArgumentError("requests must not be empty", "requests")
    val seen = mutableSetOf<String>()
    for (request in this) {
        if (request.id.isBlank()) throw InvalidArgumentError("request IDs must not be empty", "requests")
        if (!seen.add(request.id)) {
            throw InvalidArgumentError("request IDs must be unique; duplicate ID \"${request.id}\"", "requests")
        }
    }
}

/** A tool name means one definition across the batch — the reference's `validateCompatibleTools`. */
private fun requireCompatibleTools(requestId: String, tools: List<Tool>?, toolsByName: MutableMap<String, Tool>) {
    for (tool in tools.orEmpty()) {
        val previous = toolsByName[tool.name]
        if (previous != null && previous != tool) {
            throw InvalidArgumentError(
                "tool \"${tool.name}\" must have the same definition in every batch request (request \"$requestId\")",
                "requests",
            )
        }
        toolsByName[tool.name] = tool
    }
}

private fun BatchReference.requireCompatible(model: BatchLanguageModel) {
    if (provider != model.provider) {
        throw InvalidArgumentError(
            "provider ${model.provider} is not compatible with batch provider $provider",
            "provider",
        )
    }
}
