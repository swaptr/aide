package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoVideoGeneratedError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoResult
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.VideoWebhookDelivery
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobStatus
import com.sabreware.aide.aisdk.util.JobTimeoutError
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.RetryPolicy
import com.sabreware.aide.aisdk.util.pollUntilDone
import com.sabreware.aide.aisdk.util.withRetry
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------------------------------
// Video generation, in three functions rather than one.
//
// `VideoModel` splits `doStart` from `doStatus` so a job outlives the process that submitted it: the
// handle is opaque JSON precisely so a caller can write it to a database, close the app, and come back.
// A single `generateVideo` that always polled would erase that — the only reference to the job would be
// a local variable in a suspended coroutine, and killing the app would strand a render someone paid for.
//
// So polling is something a caller OPTS INTO. `startVideo` submits and returns the handle. `videoStatus`
// checks one, from a handle that may have been read off disk minutes or hours later. `awaitVideo` is the
// loop, for a caller that has decided it will stay alive; `generateVideo` is the convenience that does
// both in one call and says so in its signature.
//
// A webhook is the third way to learn the job has finished, and the endpoint is the caller's, not ours.
// `generateVideo` takes a `VideoWebhookFactory` that stands one up, hands its URL to `doStart`, and
// waits on the delivery instead of polling. The factory is invoked only for a model that says it will
// use the URL (`VideoModel.supportsWebhooks`): creating a real HTTP listener for a vendor that silently
// ignores the parameter leaves an endpoint nothing will ever call.
// ---------------------------------------------------------------------------------------------------

/**
 * A webhook the caller stood up for one generation.
 *
 * [url] is what the vendor is told to notify, and [received] completes when it does. A [Deferred] rather
 * than a callback because what the runtime does with it is race it against the poll budget, and a value
 * that can be awaited is the shape a race needs. It is the caller's listener, so the runtime never
 * cancels it: a timeout abandons the wait, not the endpoint.
 */
public data class VideoWebhook(
    /** Where the vendor should post — forwarded to [VideoModel.doStart] verbatim. */
    val url: String,
    /** Completes with what the vendor posted; see [VideoWebhookDelivery] for why the body is not read. */
    val received: Deferred<VideoWebhookDelivery>,
)

/**
 * Stands up a webhook endpoint for one generation — the reference's `GenerateVideoWebhookFactory`.
 *
 * `suspend` because creating an endpoint is real work: registering a route, opening a tunnel, minting a
 * signed URL. It is called at most once per [generateVideo], and only for a model whose
 * [VideoModel.supportsWebhooks] says the URL will be used. A model that would ignore it never has an
 * endpoint created on its behalf, and is polled instead with a [Warning.Unsupported] on the result
 * saying so.
 */
public fun interface VideoWebhookFactory {

    /** Create the endpoint and return where it listens. */
    public suspend fun create(): VideoWebhook
}

/**
 * Submits a generation and returns the handle, without waiting.
 *
 * [VideoStartResult.operation] is what to persist. It is the vendor's own job reference in whatever
 * shape the vendor uses, and nothing outside the provider should read inside it.
 *
 * [webhookUrl], when given, is forwarded to [VideoModel.doStart] so the vendor notifies that endpoint at
 * the terminal state — the fire-and-forget shape: submit from a process that will not stay alive, and let
 * whatever receives the notification call [videoStatus] with the handle it stored. The URL is forwarded
 * whether or not [VideoModel.supportsWebhooks] is true, because a caller who already stood the endpoint
 * up knows which vendor it registered with; the capability check belongs where an endpoint would be
 * CREATED on the caller's behalf, which is [generateVideo].
 *
 * ```kotlin
 * val model = KlingProvider(client, accessKey = key, secretKey = secret).videoModel("kling-v2.6-t2v")
 * val started = startVideo(model, VideoCallOptions(prompt = "waves at sunset"))
 * jobs.save(chatId, started.operation.toString()) // poll later, even from another process
 * ```
 *
 * @param model the video model to submit to.
 * @param options the prompt, clip count, resolution, duration and any input frames.
 * @param retry retry policy for the submit call; [RetryPolicy.None] because providers already retry
 *   inside their transport.
 * @param webhookUrl an endpoint of the caller's for the vendor to notify when the job finishes.
 */
public suspend fun startVideo(
    model: VideoModel,
    options: VideoCallOptions,
    retry: RetryPolicy = RetryPolicy.None,
    webhookUrl: String? = null,
): VideoStartResult {
    requireServableCount(model, options)
    return withRetry(retry) { model.doStart(options, webhookUrl) } ?: throw noAsyncFlow(model)
}

/**
 * Checked before the request, not after: a render is the most expensive call in this library, and a
 * count the model cannot serve is a bill for a result the caller was never going to get.
 */
private suspend fun requireServableCount(model: VideoModel, options: VideoCallOptions) {
    if (options.n < 1) {
        throw InvalidArgumentError("n must be at least 1, was ${options.n}", argument = "n")
    }
    model.maxVideosPerCall()?.let { max ->
        if (options.n > max) {
            throw InvalidArgumentError(
                "${model.modelId} serves at most $max video(s) per call, but ${options.n} were asked for",
                argument = "n",
            )
        }
    }
}

/**
 * Checks one submitted generation. A single request — no waiting, no loop.
 *
 * This is the entry point that makes a persisted handle useful: [operation] comes from wherever the
 * caller stored [VideoStartResult.operation], not from an object still held in memory, which is the
 * whole reason the handle is JSON.
 *
 * ```kotlin
 * val handle = Json.parseToJsonElement(jobs.load(chatId))
 * when (val status = videoStatus(model, handle)) {
 *     is VideoStatusResult.Completed -> status.videos.forEach { save(it) }
 *     is VideoStatusResult.Pending -> showSpinner()
 *     is VideoStatusResult.Failed -> showError(status.error)
 * }
 * ```
 *
 * @param model the video model the job was submitted to.
 * @param operation the vendor's job handle, from [VideoStartResult.operation] — opaque JSON.
 * @param headers extra request headers for the status call.
 * @param retry retry policy for the status call; [RetryPolicy.None] because providers already retry
 *   inside their transport.
 */
public suspend fun videoStatus(
    model: VideoModel,
    operation: JsonElement,
    headers: Map<String, String>? = null,
    retry: RetryPolicy = RetryPolicy.None,
): VideoStatusResult =
    withRetry(retry) { model.doStatus(operation, headers) } ?: throw noAsyncFlow(model)

/**
 * Waits for a submitted generation to finish, and returns the clips.
 *
 * Takes the handle rather than a start result, so resuming a job submitted by an earlier process is the
 * same call as waiting on one submitted a moment ago.
 *
 * [PollPolicy.timeoutMillis] is the caller's budget, not the job's: giving up here abandons the wait,
 * not the render, and the same handle can be polled again later with a longer one.
 *
 * Two ways to wait. Without [received] the handle is polled. With it — the [VideoWebhook.received] of an
 * endpoint this caller stood up and handed to [startVideo] — the delivery is awaited under the same
 * budget and the status is then read ONCE, because the vendor has said the job is done and a poll loop
 * after that would be polling for a state that already arrived. A job the vendor notified about but
 * still reports as pending is a failure rather than a reason to start polling: the caller stood up an
 * endpoint precisely so as not to poll, and should hear that the notification was wrong.
 *
 * ```kotlin
 * val started = startVideo(model, VideoCallOptions(prompt = "waves at sunset"))
 * val result = awaitVideo(model, started.operation, poll = PollPolicy(timeoutMillis = 10 * 60 * 1_000))
 * result.videos.forEach { save(it) }
 * ```
 *
 * @param model the video model the job was submitted to.
 * @param operation the vendor's job handle, from [VideoStartResult.operation] — opaque JSON.
 * @param poll how often to check and how long to keep checking — see [PollPolicy]. With [received], only
 *   the budget applies.
 * @param headers extra request headers for every status call.
 * @param retry retry policy, applied per status call; [RetryPolicy.None] because providers already retry
 *   inside their transport.
 * @param elapsedMillis the poll budget's clock — monotonic by default; injectable so tests can pin it.
 * @param received a webhook delivery to wait for instead of polling — see the class doc.
 */
public suspend fun awaitVideo(
    model: VideoModel,
    operation: JsonElement,
    poll: PollPolicy = PollPolicy(),
    headers: Map<String, String>? = null,
    retry: RetryPolicy = RetryPolicy.None,
    elapsedMillis: () -> Long = monotonicMillis(),
    received: Deferred<VideoWebhookDelivery>? = null,
): VideoResult {
    if (received != null) return awaitNotified(model, operation, poll, headers, retry, received)

    // A vendor reports a clamped setting or a request id on the poll that first notices it, and never
    // again. Reading them only off the completed status throws away everything said while the render was
    // running, which is exactly when a vendor has something to say about the request it accepted.
    val pendingWarnings = mutableListOf<Warning>()
    val pendingMetadata = mutableListOf<ProviderMetadata?>()

    val completed = pollUntilDone(policy = poll, elapsedMillis = elapsedMillis) {
        when (val status = videoStatus(model, operation, headers, retry)) {
            // A vendor that paces its own callers is obeyed, but `VideoStatusResult` has nowhere to carry
            // a `retry-after`, so the policy's backoff stands.
            is VideoStatusResult.Pending -> {
                pendingWarnings += status.warnings
                pendingMetadata += status.providerMetadata
                JobStatus.InProgress()
            }
            is VideoStatusResult.Completed -> JobStatus.Succeeded(
                VideoResult(
                    videos = status.videos,
                    warnings = status.warnings,
                    providerMetadata = status.providerMetadata,
                    response = status.response,
                ),
            )
            // A refused prompt is an answer. `pollUntilDone` turns this into JobFailedError rather than
            // retrying, because the same prompt will be refused every time it is asked.
            is VideoStatusResult.Failed -> JobStatus.Failed(status.error)
        }
    }

    return completed.copy(
        warnings = (pendingWarnings + completed.warnings).distinct(),
        providerMetadata = (pendingMetadata + completed.providerMetadata).mergeProviderMetadata(),
    )
}

/**
 * The webhook flow: wait for the delivery, then read the status once.
 *
 * The delivery is a signal, not the answer. Its body is whatever the vendor chose to post, and the clips
 * come from [videoStatus] exactly as they do when polling, so a caller sees the same [VideoResult]
 * whichever way the wait ended — and a forged notification can at most trigger one authenticated poll.
 *
 * The timeout is the poll budget, because the caller set one number for "how long will I wait" and which
 * mechanism ends the wait is not the caller's concern; the timeout error is the polling flow's for the
 * same reason. The deferred is left alone when the budget runs out: it is the caller's listener, and the
 * delivery may still be worth reading later.
 */
private suspend fun awaitNotified(
    model: VideoModel,
    operation: JsonElement,
    poll: PollPolicy,
    headers: Map<String, String>?,
    retry: RetryPolicy,
    received: Deferred<VideoWebhookDelivery>,
): VideoResult {
    withTimeoutOrNull(poll.timeoutMillis) { received.await() } ?: throw JobTimeoutError(poll.timeoutMillis)

    return when (val status = videoStatus(model, operation, headers, retry)) {
        is VideoStatusResult.Completed -> VideoResult(
            videos = status.videos,
            warnings = status.warnings,
            providerMetadata = status.providerMetadata,
            response = status.response,
        )
        is VideoStatusResult.Failed -> throw JobFailedError(status.error)
        is VideoStatusResult.Pending -> throw NoVideoGeneratedError(
            "Video generation did not complete after webhook notification.",
            responses = listOf(status.response),
        )
    }
}

/**
 * Generates video and waits for it, whichever flow the model implements.
 *
 * A model with a synchronous [VideoModel.doGenerate] is called directly. One with only the
 * start/status pair is started and then polled — which is the moment the caller accepts that this
 * coroutine must stay alive for the duration, and why [poll] is a parameter with a visible budget
 * instead of a hidden loop.
 *
 * A [webhook] factory changes who does the waiting. For a model that [VideoModel.supportsWebhooks], the
 * factory is invoked, its URL rides on the start call, and the delivery is awaited under the poll budget
 * instead of polling — the asynchronous flow is taken even where a synchronous endpoint exists, because a
 * caller who stood up an endpoint asked for the flow that uses it. For a model that does not, the factory
 * is never invoked (no endpoint is created for a vendor that would ignore it) and the job is polled, with
 * a [Warning.Unsupported] for `webhook` leading the result's warnings so the fallback is on the record.
 * A model with only a synchronous endpoint answers it directly either way; there is no job to notify
 * about.
 *
 * Warnings from the start call are carried onto the result: a model that ignored `fps` says so when the
 * job is submitted, and the status response has no reason to repeat it.
 *
 * ```kotlin
 * val model = KlingProvider(client, accessKey = key, secretKey = secret).videoModel("kling-v2.6-t2v")
 * val result = generateVideo(model, VideoCallOptions(prompt = "waves at sunset", durationInSeconds = 5.0))
 * result.videos.forEach { save(it) }
 * ```
 *
 * @param model the video model to call.
 * @param options the prompt, clip count, resolution, duration and any input frames.
 * @param poll how often to check and how long to keep waiting, when the model polls — see [PollPolicy].
 *   With a webhook in use, only the budget applies.
 * @param retry retry policy, applied per request; [RetryPolicy.None] because providers already retry
 *   inside their transport.
 * @param elapsedMillis the poll budget's clock — monotonic by default; injectable so tests can pin it.
 * @param webhook stands up an endpoint for the vendor to notify — see [VideoWebhookFactory] and above.
 */
public suspend fun generateVideo(
    model: VideoModel,
    options: VideoCallOptions,
    poll: PollPolicy = PollPolicy(),
    retry: RetryPolicy = RetryPolicy.None,
    elapsedMillis: () -> Long = monotonicMillis(),
    webhook: VideoWebhookFactory? = null,
): VideoResult {
    // Refused before the listener exists: a caller's endpoint is a real resource, and one stood up for
    // a request that is about to be rejected is a listener nothing ever calls.
    requireServableCount(model, options)
    // The endpoint is created before anything is submitted, because its URL has to ride on the start
    // call — and only for a model that will use it, because a listener for a vendor that ignores the
    // parameter is a listener nothing ever calls.
    val listener = if (webhook != null && model.supportsWebhooks) webhook.create() else null

    if (listener == null) {
        withRetry(retry) { model.doGenerate(options) }?.let { return it }
    }

    val fallback = if (webhook != null && listener == null) listOf(WEBHOOK_UNSUPPORTED) else emptyList()
    val started = startVideo(model, options, retry = retry, webhookUrl = listener?.url)
    val finished = awaitVideo(
        model = model,
        operation = started.operation,
        poll = poll,
        headers = options.headers,
        retry = retry,
        elapsedMillis = elapsedMillis,
        received = listener?.received,
    )
    return finished.copy(
        warnings = (fallback + started.warnings + finished.warnings).distinct(),
        providerMetadata = finished.providerMetadata ?: started.providerMetadata,
    )
}

/** The reference's own wording, so a log line reads the same out of either host. */
private val WEBHOOK_UNSUPPORTED = Warning.Unsupported(
    feature = "webhook",
    details = "This model does not support webhooks. Falling back to polling.",
)

/**
 * The default clock: elapsed time since this call began.
 *
 * A monotonic mark rather than a wall clock, because a poll budget measured against wall time is wrong
 * across an NTP correction or a device sleeping — both of which happen inside the minutes a render
 * takes.
 */
private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

private fun noAsyncFlow(model: VideoModel): UnsupportedFunctionalityError = UnsupportedFunctionalityError(
    "doStart/doStatus",
    "The '${model.provider}' model '${model.modelId}' has no asynchronous video flow.",
)
