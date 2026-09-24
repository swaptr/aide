package com.sabreware.aide.aisdk

import kotlinx.coroutines.flow.Flow

// Ref: `batch/v4` (`Experimental_BatchV4*`), the version and `Experimental_` prefixes dropped per the
// porting rules — experimental status is a KDoc fact here, not a name. The reference's batch interface
// stopped being a language-model extension in provider 4.0.11: `BatchLanguageModelV4` became a
// standalone `BatchV4` whose requests each name their own model and may be text OR image. The name here
// stays — every provider implements it, and a rename buys nothing the KDoc does not — while the shape
// follows: a request is a [BatchRequest] union, a result is typed by modality, and the model's own
// [BatchLanguageModel.modelId] is the default a request without one runs on.

/**
 * Durable batch processing: many requests, submitted once, collected later.
 *
 * The batch outlives the process that started it — [BatchStartResult.batchId] is the persistable
 * handle, and status and results are fetched by id from any later process. That is the property the
 * three-method shape exists for; a single suspend call that waited for completion would tie a
 * multi-hour batch to one coroutine's lifetime. Cancellation and listing are optional on top of it.
 *
 * **Experimental** in the reference (`Experimental_BatchV4`) and inheriting that status here: the
 * surface may still move with the upstream spec.
 */
public interface BatchLanguageModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /**
     * The vendor's own model identifier — the DEFAULT model.
     *
     * A [BatchRequest] carries its own [BatchRequest.modelId]; one that carries none runs on this. The
     * reference dropped the model from its batch interface once requests named theirs; here it stays as
     * the default, so a batch of one model is spelled the way it always was.
     */
    public val modelId: String

    /**
     * URL patterns, by media type, this batch interface fetches for itself — see [LanguageModel.supportedUrls].
     * The runtime consults it before downloading a request's attachments on the vendor's behalf.
     */
    public suspend fun supportedUrls(): Map<String, List<Regex>> = emptyMap()

    /**
     * Submits every request in one batch and returns the handle the rest of the lifecycle uses.
     *
     * @throws UnsupportedFunctionalityError for a request of a [BatchRequestType] this interface does not
     *   serve, BEFORE anything is sent — the reference's rejection, and here the one every text-only
     *   provider inherits by reading [BatchRequest.options].
     */
    public suspend fun doStartBatch(options: BatchStartOptions): BatchStartResult

    /** Reads the batch's lifecycle state without touching its results. */
    public suspend fun doGetBatchStatus(options: BatchOperationOptions): BatchStatus

    /**
     * The per-request outcomes of a finished batch.
     *
     * Cold: collecting it fetches the results. The order is the VENDOR's, not submission order — pair
     * outcomes to requests by [BatchItemResult] id, never by position.
     *
     * @throws InvalidArgumentError if the batch is still pending, or its results have expired.
     */
    public fun doGetBatchResults(options: BatchOperationOptions): Flow<BatchItemResult>

    /**
     * Asks the vendor to cancel the batch.
     *
     * Optional; null means this interface offers no cancellation (the reference's absent method). A
     * request is an ask, not a guarantee: items already running may still complete, and the batch reads
     * [BatchStatus.State.Pending] until the vendor settles it.
     */
    public suspend fun doCancelBatch(options: BatchOperationOptions): BatchCancelResult? = null

    /**
     * One page of the vendor's batches, newest first where the vendor orders them.
     *
     * Optional; null means this interface offers no listing (the reference's absent method).
     */
    public suspend fun doListBatches(options: BatchListOptions): BatchListResult? = null
}

/**
 * The modality of one request in a batch — the reference's `type` discriminator.
 *
 * On the wire a vendor spells it in lower case (`text`, `image`), which is what [wireName] is for.
 */
public enum class BatchRequestType {
    Text,
    Image,
    ;

    /** The reference's spelling, e.g. `image`. */
    public val wireName: String get() = name.lowercase()
}

/**
 * One request in a batch, named by [id] and discriminated by modality.
 *
 * A sealed union because a batch may mix text and image requests, and the reference types each
 * variant's options after the live call it stands for: a [Text] request carries the [CallOptions] a
 * [LanguageModel.doGenerate] would take, an [Image] request the [ImageCallOptions] an
 * [ImageModel.doGenerate] would.
 */
public sealed interface BatchRequest {

    /** The caller's correlation id — the only way to pair an outcome back to its request. */
    public val id: String

    /**
     * The provider-specific model for THIS request. Null runs it on [BatchLanguageModel.modelId], which
     * is how a batch that names one model everywhere is spelled.
     */
    public val modelId: String?

    /** Which variant this is, for a provider that switches on the wire spelling. */
    public val type: BatchRequestType

    /**
     * The text call — for a provider that only speaks text.
     *
     * On a [Text] request this is its [Text.options]. On anything else it THROWS
     * [UnsupportedFunctionalityError] naming the request type, which is the reference's rejection of a
     * request type a provider does not serve, made before any request is sent: a text-only provider
     * that maps every request through here gets it without a line of code, and a provider that serves
     * images switches on the variant and never reads this off an [Image].
     */
    public val options: CallOptions
        get() = throw UnsupportedFunctionalityError(
            functionality = "batch request type: ${type.wireName}",
            message = "This batch API does not support batch requests with type \"${type.wireName}\".",
        )

    /** A text generation — see [BatchRequest]. */
    public data class Text(
        override val id: String,
        /** The call itself, exactly as a single [LanguageModel.doGenerate] would take it. */
        override val options: CallOptions,
        override val modelId: String? = null,
    ) : BatchRequest {

        override val type: BatchRequestType get() = BatchRequestType.Text
    }

    /** An image generation — see [BatchRequest]. */
    public data class Image(
        override val id: String,
        /** The call itself, exactly as a single [ImageModel.doGenerate] would take it. */
        val imageOptions: ImageCallOptions,
        override val modelId: String? = null,
    ) : BatchRequest {

        override val type: BatchRequestType get() = BatchRequestType.Image
    }
}

/** A [BatchRequest.Text], spelled as a request was before requests had a type. */
public fun BatchRequest(id: String, options: CallOptions, modelId: String? = null): BatchRequest.Text =
    BatchRequest.Text(id, options, modelId)

/** Everything [BatchLanguageModel.doStartBatch] needs. */
public data class BatchStartOptions(
    /** The requests to run, text and image alike. Ids must be unique within the batch. */
    val requests: List<BatchRequest>,
    /**
     * Batch-wide provider-namespaced options, passed through verbatim — see [ProviderOptions].
     *
     * One key is a contract across the providers that stage a batch as an uploaded input file:
     * `inputFileExpiresAfter`, seconds until the vendor deletes that file, under the provider's own key.
     * The file itself is reported back on the start result — see [BatchStartResult].
     */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers for the batch calls. */
    val headers: Map<String, String>? = null,
    /**
     * A URL the provider calls when the batch reaches a terminal state. A provider without webhook
     * support returns an unsupported [Warning] rather than failing the batch over its delivery method.
     */
    val webhookUrl: String? = null,
)

/** Names a batch for a status, results or cancel operation. */
public data class BatchOperationOptions(
    /** The handle [BatchStartResult.batchId] returned. */
    val batchId: String,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers for this call. */
    val headers: Map<String, String>? = null,
)

/** Everything [BatchLanguageModel.doListBatches] needs. */
public data class BatchListOptions(
    /** How many batches one page may hold. Null takes the vendor's default. */
    val limit: Int? = null,
    /** Where the previous page ended — its [BatchListResult.nextCursor]. Null starts at the first page. */
    val cursor: String? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers for this call. */
    val headers: Map<String, String>? = null,
)

/** Serializable failure detail for a batch or one of its items. */
public data class BatchError(
    val message: String,
    /** The vendor's error type, when it names one. */
    val type: String? = null,
    /** The vendor's error code, when it names one. */
    val code: String? = null,
    /** HTTP status, when the failure had one. */
    val statusCode: Int? = null,
)

/**
 * A batch's lifecycle state.
 *
 * Normalized to three states because that is all a poller branches on; the vendor's own word survives
 * in [rawStatus] for the log line.
 */
public data class BatchStatus(
    val state: State,
    /** The vendor's own status string, e.g. Anthropic's `in_progress` / `canceling` / `ended`. */
    val rawStatus: String? = null,
    /** Per-request progress, where the vendor reports it. */
    val requestCounts: BatchRequestCounts? = null,
    /** Why a [State.Failed] batch failed, when the vendor says. */
    val error: BatchError? = null,
    /** Vendor timestamp string, as received. */
    val createdAt: String? = null,
    /** When the batch's results stop being retrievable. */
    val expiresAt: String? = null,
    /** Provider-namespaced status detail, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
) {

    /** The three states a poller branches on. */
    public enum class State {
        /** Still running — including a cancellation that has not settled yet. */
        Pending,

        /** Terminal; results are retrievable (individual items may still have failed). */
        Completed,

        /** Terminal; the BATCH failed — distinct from a completed batch with failed items. */
        Failed,
    }
}

/** How far a batch has progressed, request by request. */
public data class BatchRequestCounts(
    val total: Int,
    val pending: Int,
    val completed: Int,
    val failed: Int,
)

/**
 * The result of [BatchLanguageModel.doStartBatch]: the persistable handle plus the opening status.
 *
 * A provider that stages the batch as an uploaded input file surfaces that file under its own key in
 * [status]'s [BatchStatus.providerMetadata] — `inputFileId`, and `inputFileExpiresAt` where the vendor
 * set an expiry (see [BatchStartOptions.providerOptions]) — so a caller can find, extend or delete the
 * upload the batch reads from.
 */
public data class BatchStartResult(
    /** The handle every later status and results call names. */
    val batchId: String,
    /** The batch's state as of submission. */
    val status: BatchStatus,
    /**
     * Warnings, each optionally tied to the request that raised it — a batch-wide warning (an
     * unsupported [BatchStartOptions.webhookUrl]) carries no request id.
     */
    val warnings: List<RequestWarning> = emptyList(),
) {

    /** One warning, attributed to a request when one raised it. */
    public data class RequestWarning(
        val warning: Warning,
        /** The [BatchRequest.id] this warning is about; null for a batch-wide warning. */
        val requestId: String? = null,
    )
}

/** The result of [BatchLanguageModel.doCancelBatch]. */
public data class BatchCancelResult(
    /** Provider-namespaced detail about the request, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
)

/** One batch as a listing reports it — see [BatchLanguageModel.doListBatches]. */
public data class BatchListItem(
    /** The handle [BatchStartResult.batchId] returned when it was started. */
    val batchId: String,
    /** Its state as of the listing. */
    val status: BatchStatus,
)

/** The result of [BatchLanguageModel.doListBatches]: one page and where the next one starts. */
public data class BatchListResult(
    val batches: List<BatchListItem>,
    /** The [BatchListOptions.cursor] for the next page; null when this was the last. */
    val nextCursor: String? = null,
    /** Provider-namespaced detail about the page, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
)

/**
 * The terminal outcome of one request in a batch.
 *
 * A sealed union rather than a status field so the compiler forces every consumer to decide what a
 * cancelled or expired item means for it — the two easiest outcomes to forget are the two that only
 * appear under operational stress. Every arm carries its [type], the reference's discriminator, so a
 * failure is still known to have been an image request or a text one.
 */
public sealed interface BatchItemResult {

    /** The [BatchRequest.id] this outcome answers. */
    public val id: String

    /** The modality of the request this outcome answers. */
    public val type: BatchRequestType

    /** A text request ran; [result] is exactly what a live [LanguageModel.doGenerate] would have returned. */
    public data class Succeeded(
        override val id: String,
        val result: GenerateResult,
    ) : BatchItemResult {

        override val type: BatchRequestType get() = BatchRequestType.Text
    }

    /** An image request ran; [result] is exactly what a live [ImageModel.doGenerate] would have returned. */
    public data class ImageSucceeded(
        override val id: String,
        val result: ImageResult,
    ) : BatchItemResult {

        override val type: BatchRequestType get() = BatchRequestType.Image
    }

    /** The request ran and the vendor rejected or errored it. */
    public data class Failed(
        override val id: String,
        val error: BatchError,
        /** Provider-namespaced failure detail — a vendor request id — carried verbatim. */
        val providerMetadata: ProviderMetadata? = null,
        override val type: BatchRequestType = BatchRequestType.Text,
    ) : BatchItemResult

    /** The batch was cancelled before this request ran. */
    public data class Cancelled(
        override val id: String,
        val error: BatchError? = null,
        val providerMetadata: ProviderMetadata? = null,
        override val type: BatchRequestType = BatchRequestType.Text,
    ) : BatchItemResult

    /** The batch expired before this request ran. */
    public data class Expired(
        override val id: String,
        val error: BatchError? = null,
        val providerMetadata: ProviderMetadata? = null,
        override val type: BatchRequestType = BatchRequestType.Text,
    ) : BatchItemResult
}
