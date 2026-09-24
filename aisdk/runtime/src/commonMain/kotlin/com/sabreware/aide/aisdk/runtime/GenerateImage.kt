package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.ImageUsage
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.NoImageGeneratedError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.withRetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Every image the request produced, plus one [ModalityResponse] per call it took to produce them.
 *
 * Warnings are aggregated and de-duplicated: a fan-out of eight calls against a model that ignores
 * `seed` reports the same unsupported-setting warning eight times, and a caller rendering that verbatim
 * shows the user the same sentence eight times.
 */
public data class GeneratedImages(
    /** Every image produced, across every call, in call order. */
    val images: List<BinaryData>,
    /** Every distinct warning any call produced. */
    val warnings: List<Warning> = emptyList(),
    /** Token counts summed across calls, when the vendor reports them. */
    val usage: ImageUsage? = null,
    /** The calls' vendor payloads, merged per provider id — DALL-E's `revisedPrompt` lives here. */
    val providerMetadata: ProviderMetadata? = null,
    /** One entry per call actually made. */
    val responses: List<ModalityResponse> = emptyList(),
    /**
     * Every model call this request made, in call order — the empty attempts a retry recovered from
     * included, each with its own images, warnings, usage, metadata and response. [responses],
     * [warnings], [usage] and [providerMetadata] are views over this list; a caller that needs a call's
     * metadata beside the images IT produced reads the call, because the merged views cannot say which
     * revised prompt belongs to which image.
     */
    val calls: List<ImageResult> = emptyList(),
)

/**
 * Generates the images [ImageCallOptions.n] asks for, issuing as many calls as the model's ceiling
 * requires.
 *
 * [ImageModel.maxImagesPerCall] is the vendor's per-request limit, and most image endpoints set it to
 * one — DALL-E 3 and every Imagen variant among them. A request for eight images against such a model
 * is eight calls, not an error and not a silent truncation to one: refusing would make `n` useless on
 * exactly the models people use, and truncating would return the wrong number of images with nothing
 * to say so.
 *
 * The calls run concurrently, bounded, because an unbounded fan-out is how a request for sixty-four
 * images becomes sixty-four simultaneous connections and a 429.
 *
 * A call that answers with NO images is asked again under [retry], unless the provider classified the
 * result as final ([ImageResult.isRetryable] false); see [callUntilImages]. When every call is still
 * empty afterwards the failure is [NoImageGeneratedError], carrying every attempt's response so the
 * diagnostics of the empty calls — the request id, the headers — are not lost with the images.
 *
 * ```kotlin
 * val model = FalProvider(client, apiKey).imageModel("fal-ai/flux")
 * val result = generateImage(model, ImageCallOptions(prompt = "a lighthouse at dusk", n = 2))
 * result.images.forEach { save(it) }
 * ```
 *
 * @param model the image model to call; its per-call ceiling decides how many requests [options] takes.
 * @param options the prompt, count, size and the rest; `n` is re-split per call, everything else is
 *   forwarded to each one.
 * @param retry retry policy, applied per call, and covering an unclassified empty result as well as a
 *   retryable transport failure — one budget for both; [RetryPolicy.None] because providers already
 *   retry transport failures inside their transport.
 */
public suspend fun generateImage(
    model: ImageModel,
    options: ImageCallOptions,
    retry: RetryPolicy = RetryPolicy.None,
): GeneratedImages {
    if (options.n < 1) throw InvalidArgumentError("n must be at least 1.", "n")

    // One, not the whole request, when the model documents no ceiling: an image endpoint that has not
    // said it serves batches is assumed not to, because the failure of guessing high is a 400 per call
    // and the failure of guessing low is one extra round trip.
    val perCall = (model.maxImagesPerCall() ?: 1).coerceAtLeast(1)

    val calls = splitCount(options.n, perCall).chunked(MAX_PARALLEL_IMAGE_CALLS).flatMap { wave ->
        coroutineScope {
            wave.map { count ->
                async { callUntilImages(model, options.copy(n = count), retry) }
            }.awaitAll()
        }
    }.flatten()

    val images = calls.flatMap { it.images }
    if (images.isEmpty()) {
        throw NoImageGeneratedError(responses = calls.map { it.response }, calls = calls)
    }
    return GeneratedImages(
        images = images,
        warnings = calls.flatMap { it.warnings }.distinct(),
        usage = calls.mapNotNull { it.usage }.takeIf { it.isNotEmpty() }?.sumUsage(),
        providerMetadata = calls.map { it.providerMetadata }.mergeProviderMetadata(),
        responses = calls.map { it.response },
        calls = calls,
    )
}

/**
 * One call's attempts: the vendor is asked again, on the policy's backoff, while it answers with no
 * images and does not say the result is final.
 *
 * An empty 200 is not an `APICallError`, so [withRetry] cannot see it. The empty-result retries and the
 * transport retries still share ONE budget — [RetryPolicy.maxRetries] bounds them together, as the
 * reference's single retry counter does — by handing [withRetry] only what the empty attempts left of
 * it, and learning from its `attempt` count what it spent. A result the provider marks
 * `isRetryable = false` (a moderated prompt, a terminal refusal) is accepted at once: asking again would
 * spend the budget on the same verdict.
 *
 * @return every attempt's result, in order, so nothing an empty attempt reported is lost.
 */
private suspend fun callUntilImages(
    model: ImageModel,
    options: ImageCallOptions,
    retry: RetryPolicy,
): List<ImageResult> {
    val attempts = mutableListOf<ImageResult>()
    var retriesUsed = 0
    while (true) {
        var retriesHere = 0
        val result = withRetry(retry.copy(maxRetries = retry.maxRetries - retriesUsed)) { attempt ->
            retriesHere = attempt
            model.doGenerate(options)
        }
        retriesUsed += retriesHere
        attempts += result
        val emptyButRetryable = result.images.isEmpty() && result.isRetryable != false
        if (!emptyButRetryable || retriesUsed >= retry.maxRetries) return attempts
        retriesUsed++
        delay(retry.delayBeforeRetry(retriesUsed))
    }
}

/** The policy's curve for the [retryNumber]-th retry, counted from one — what [withRetry] would wait. */
private fun RetryPolicy.delayBeforeRetry(retryNumber: Int): Long {
    var wait = initialDelayMillis
    repeat(retryNumber - 1) { wait = (wait * backoffFactor).toLong().coerceAtMost(maxDelayMillis) }
    return wait
}

/**
 * How many images each call asks for: full batches, then the remainder.
 *
 * The remainder goes last rather than being padded up to a full batch, because several vendors bill per
 * image returned and rounding a request for nine into two calls of five charges for ten.
 */
private fun splitCount(total: Int, perCall: Int): List<Int> {
    val full = total / perCall
    val remainder = total % perCall
    return List(full) { perCall } + if (remainder == 0) emptyList() else listOf(remainder)
}

/**
 * Token counts add across calls; the raw blob does not, so the first call's is kept as the sample.
 *
 * [ImageUsage.totalTokens] is summed from the vendors' own totals rather than derived from the other two.
 * A vendor's total need not equal input plus output — several bill image tokens that appear in neither —
 * so recomputing it would quietly replace what the vendor charged with what we think it should have.
 */
private fun List<ImageUsage>.sumUsage(): ImageUsage = ImageUsage(
    inputTokens = mapNotNull { it.inputTokens }.takeIf { it.isNotEmpty() }?.sum(),
    outputTokens = mapNotNull { it.outputTokens }.takeIf { it.isNotEmpty() }?.sum(),
    totalTokens = mapNotNull { it.totalTokens }.takeIf { it.isNotEmpty() }?.sum(),
    raw = firstNotNullOfOrNull { it.raw },
)

private const val MAX_PARALLEL_IMAGE_CALLS = 4
