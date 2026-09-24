package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.NoVideoGeneratedError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoResult
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.VideoWebhookDelivery
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobTimeoutError
import com.sabreware.aide.aisdk.util.PollPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The webhook half of the reference's `ai/src/generate-video` and `start-video` cases, translated.
 *
 * The decision these pin matters more than any wire detail: the caller's factory — which may open a
 * tunnel or register a route — is invoked ONLY for a model that says it will use the URL, and when it
 * is, the job is not polled. Everything else is the same [VideoResult] the polling flow produces,
 * because the delivery is a signal and the clips still come from the status call.
 */
class VideoWebhookConformanceTest {

    private val clip = VideoData.Base64("bXA0", "video/mp4")
    private val webhookUrl = "https://example.com/webhook"
    private val delivery = VideoWebhookDelivery(
        headers = mapOf("x-signature" to "sig"),
        body = buildJsonObject { put("status", "completed") },
    )

    private fun factoryFor(received: CompletableDeferred<VideoWebhookDelivery>, onCreate: () -> Unit = {}) =
        VideoWebhookFactory {
            onCreate()
            VideoWebhook(webhookUrl, received)
        }

    // -----------------------------------------------------------------------------------------------
    // The flow, when the model supports it
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `the factory's URL rides on the start call and the delivery ends the wait`() = runTest {
        val received = CompletableDeferred<VideoWebhookDelivery>()
        var seenUrl: String? = null
        var seenOperation: JsonElement? = null
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, url ->
                seenUrl = url
                launch {
                    delay(10)
                    received.complete(delivery)
                }
                VideoStartResult(operation = JsonPrimitive("op-webhook"))
            },
            status = { operation ->
                seenOperation = operation
                VideoStatusResult.Completed(videos = listOf(clip))
            },
        )
        var factoryCalls = 0

        val result = generateVideo(
            model,
            VideoCallOptions(prompt = "a wave"),
            webhook = factoryFor(received) { factoryCalls++ },
        )

        assertEquals(1, factoryCalls)
        assertEquals(webhookUrl, seenUrl)
        assertEquals(JsonPrimitive("op-webhook"), seenOperation)
        assertEquals(listOf(clip), result.videos)
    }

    @Test
    fun `the status is read once, after the delivery, and never polled`() = runTest {
        val received = CompletableDeferred<VideoWebhookDelivery>()
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, _ ->
                launch {
                    delay(10)
                    received.complete(delivery)
                }
                VideoStartResult(operation = JsonPrimitive("op-both"))
            },
            status = {
                // A poll before the notification is exactly what the caller stood up an endpoint to avoid.
                assertTrue(received.isCompleted, "doStatus ran before the webhook arrived")
                VideoStatusResult.Completed(videos = listOf(clip))
            },
        )

        val result = generateVideo(
            model,
            VideoCallOptions(prompt = "a wave"),
            // A policy that would poll eagerly, to show the loop is not entered at all.
            poll = PollPolicy(initialDelayMillis = 1, maxDelayMillis = 1),
            webhook = factoryFor(received),
        )

        assertEquals(1, model.statusCalls)
        assertEquals(listOf(clip), result.videos)
    }

    @Test
    fun `a caller who stood up an endpoint gets the flow that uses it, even where a synchronous one exists`() =
        runTest {
            val received = CompletableDeferred(delivery)
            val model = WebhookVideoModel(
                supportsWebhooks = true,
                generate = { error("doGenerate must not be called when the caller asked for a webhook") },
                start = { _, _ -> VideoStartResult(operation = JsonPrimitive("op-1")) },
                status = { VideoStatusResult.Completed(videos = listOf(clip)) },
            )

            val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), webhook = factoryFor(received))

            assertEquals(0, model.generateCalls)
            assertEquals(1, model.startCalls)
            assertEquals(listOf(clip), result.videos)
        }

    @Test
    fun `warnings and metadata from the start call and the status both reach the result`() = runTest {
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, _ ->
                VideoStartResult(
                    operation = JsonPrimitive("op-1"),
                    warnings = listOf(Warning.Other("start warning")),
                    providerMetadata = mapOf("p" to buildJsonObject { put("jobId", "abc") }),
                )
            },
            status = {
                VideoStatusResult.Completed(
                    videos = listOf(clip),
                    warnings = listOf(Warning.Other("status warning")),
                )
            },
        )

        val result = generateVideo(
            model,
            VideoCallOptions(prompt = "a wave"),
            webhook = factoryFor(CompletableDeferred(delivery)),
        )

        assertEquals(listOf(Warning.Other("start warning"), Warning.Other("status warning")), result.warnings)
        assertEquals(mapOf("p" to buildJsonObject { put("jobId", "abc") }), result.providerMetadata)
    }

    // -----------------------------------------------------------------------------------------------
    // When the wait ends badly
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a delivery that never arrives times out on the poll budget, and the status is never read`() =
        runTest {
            val model = WebhookVideoModel(
                supportsWebhooks = true,
                start = { _, _ -> VideoStartResult(operation = JsonPrimitive("op-webhook-timeout")) },
                status = { error("doStatus should not be called") },
            )

            val error = assertFailsWith<JobTimeoutError> {
                generateVideo(
                    model,
                    VideoCallOptions(prompt = "a wave"),
                    poll = PollPolicy(timeoutMillis = 20),
                    webhook = factoryFor(CompletableDeferred()),
                )
            }

            // The same error the polling flow throws: the caller set one budget, and which mechanism ran
            // out of it is not something a `catch` should have to know.
            assertEquals(20, error.timeoutMillis)
            assertEquals(0, model.statusCalls)
        }

    @Test
    fun `cancelling the caller while it waits stops the wait without a status call`() = runTest {
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, _ -> VideoStartResult(operation = JsonPrimitive("op-webhook-abort")) },
            status = { error("doStatus should not be called") },
        )

        val job = launch {
            generateVideo(model, VideoCallOptions(prompt = "a wave"), webhook = factoryFor(CompletableDeferred()))
        }
        runCurrent()
        assertEquals(1, model.startCalls)

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, model.statusCalls)
    }

    @Test
    fun `a job the vendor notified about but still reports pending is a failure, not a poll`() = runTest {
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, _ -> VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = { VideoStatusResult.Pending(response = ModalityResponse(id = "poll-1")) },
        )

        val error = assertFailsWith<NoVideoGeneratedError> {
            generateVideo(
                model,
                VideoCallOptions(prompt = "a wave"),
                webhook = factoryFor(CompletableDeferred(delivery)),
            )
        }

        assertEquals("Video generation did not complete after webhook notification.", error.message)
        assertEquals(listOf(ModalityResponse(id = "poll-1")), error.responses)
        assertEquals(1, model.statusCalls)
    }

    @Test
    fun `a job the vendor refused after notifying is the same refusal polling reports`() = runTest {
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, _ -> VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = { VideoStatusResult.Failed("content policy") },
        )

        val error = assertFailsWith<JobFailedError> {
            generateVideo(
                model,
                VideoCallOptions(prompt = "a wave"),
                webhook = factoryFor(CompletableDeferred(delivery)),
            )
        }

        assertEquals("content policy", error.message)
    }

    // -----------------------------------------------------------------------------------------------
    // A receiver that fails — the reference's `webhook receiver rejection` cases
    //
    // In JS a receiver promise that rejects before it is awaited is an unhandled rejection, and the fix
    // observes it early. A failed Deferred is inert until awaited, so the two properties that remain
    // are the ones pinned here: a failing start wins, and a failed receiver surfaces once, unchanged.
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a receiver that failed before the start does not pre-empt a failing start`() = runTest {
        val received = CompletableDeferred<VideoWebhookDelivery>().apply {
            completeExceptionally(IllegalStateException("webhook delivery failed"))
        }
        val model = WebhookVideoModel(
            supportsWebhooks = true,
            start = { _, _ -> error("start failed") },
            status = { error("doStatus should not be called") },
        )

        val error = assertFailsWith<IllegalStateException> {
            generateVideo(model, VideoCallOptions(prompt = "a wave"), webhook = factoryFor(received))
        }

        assertEquals("start failed", error.message)
        assertEquals(0, model.statusCalls)
    }

    @Test
    fun `a receiver that failed while the start was in flight surfaces its own failure, and the status is never read`() =
        runTest {
            val received = CompletableDeferred<VideoWebhookDelivery>()
            var created = 0
            val model = WebhookVideoModel(
                supportsWebhooks = true,
                start = { _, _ ->
                    received.completeExceptionally(IllegalStateException("webhook delivery failed"))
                    VideoStartResult(operation = JsonPrimitive("op-1"))
                },
                status = { error("doStatus should not be called") },
            )

            val error = assertFailsWith<IllegalStateException> {
                generateVideo(model, VideoCallOptions(prompt = "a wave"), webhook = factoryFor(received) { created++ })
            }

            assertEquals("webhook delivery failed", error.message)
            assertEquals(1, created)
            assertEquals(1, model.startCalls)
            assertEquals(0, model.statusCalls)
        }

    // -----------------------------------------------------------------------------------------------
    // When the model does not support webhooks
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a model without webhook support is polled, the factory is never invoked, and the result says so`() =
        runTest {
            var seenUrl: String? = "unset"
            var factoryCalls = 0
            val model = WebhookVideoModel(
                supportsWebhooks = false,
                start = { _, url ->
                    seenUrl = url
                    VideoStartResult(
                        operation = JsonPrimitive("op-fallback"),
                        warnings = listOf(Warning.Other("start warning")),
                    )
                },
                status = {
                    VideoStatusResult.Completed(
                        videos = listOf(clip),
                        warnings = listOf(Warning.Other("status warning")),
                    )
                },
            )

            val result = generateVideo(
                model,
                VideoCallOptions(prompt = "a wave"),
                poll = PollPolicy.Fast,
                webhook = factoryFor(CompletableDeferred()) { factoryCalls++ },
            )

            // No endpoint is created for a vendor that would ignore the URL — see VideoModel.supportsWebhooks.
            assertEquals(0, factoryCalls)
            assertNull(seenUrl)
            assertTrue(model.statusCalls >= 1)
            assertEquals(listOf(clip), result.videos)
            // The reference's own wording, leading the list so the fallback is the first thing a log shows.
            assertEquals(
                listOf(
                    Warning.Unsupported("webhook", "This model does not support webhooks. Falling back to polling."),
                    Warning.Other("start warning"),
                    Warning.Other("status warning"),
                ),
                result.warnings,
            )
        }

    @Test
    fun `a synchronous model answers directly, and the factory is never invoked`() = runTest {
        var factoryCalls = 0
        val model = WebhookVideoModel(
            supportsWebhooks = false,
            generate = { VideoResult(videos = listOf(clip)) },
        )

        val result = generateVideo(
            model,
            VideoCallOptions(prompt = "a wave"),
            webhook = factoryFor(CompletableDeferred()) { factoryCalls++ },
        )

        assertEquals(0, factoryCalls)
        assertEquals(listOf(clip), result.videos)
    }

    // -----------------------------------------------------------------------------------------------
    // startVideo / awaitVideo, used on their own
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `startVideo forwards a webhook URL to doStart, and none when none was given`() = runTest {
        val seen = mutableListOf<String?>()
        val model = WebhookVideoModel(
            supportsWebhooks = false,
            start = { _, url ->
                seen += url
                VideoStartResult(operation = JsonPrimitive("op-1"))
            },
        )

        startVideo(model, VideoCallOptions(prompt = "a wave"), webhookUrl = "https://example.com/hook")
        startVideo(model, VideoCallOptions(prompt = "a wave"))

        // Forwarded regardless of `supportsWebhooks`: the caller who stood the endpoint up already knows
        // which vendor it registered with, and the capability check belongs where an endpoint would be
        // created on the caller's behalf.
        assertEquals(listOf("https://example.com/hook", null), seen)
    }

    @Test
    fun `awaitVideo with a delivery waits on a handle it was handed, with no start call of its own`() =
        runTest {
            val received = CompletableDeferred<VideoWebhookDelivery>()
            val model = WebhookVideoModel(
                supportsWebhooks = true,
                start = { _, _ -> VideoStartResult(operation = JsonPrimitive("op-1")) },
                status = { VideoStatusResult.Completed(videos = listOf(clip)) },
            )
            launch {
                delay(10)
                received.complete(delivery)
            }

            val result = awaitVideo(model, JsonPrimitive("op-1"), received = received)

            assertEquals(0, model.startCalls)
            assertEquals(1, model.statusCalls)
            assertEquals(listOf(clip), result.videos)
        }
}

// ---------------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------------

/**
 * A model whose entry points are supplied per case, and which reports what `doStart` was handed.
 *
 * `supportsWebhooks` is a constructor argument because it is the whole subject: the specification's
 * capability flag is what decides whether a caller's factory — which may create real resources — is
 * ever invoked, and every case here exists to pin one side of that decision.
 */
private class WebhookVideoModel(
    override val supportsWebhooks: Boolean,
    private val generate: (suspend (VideoCallOptions) -> VideoResult)? = null,
    private val start: (suspend (VideoCallOptions, String?) -> VideoStartResult)? = null,
    private val status: (suspend (JsonElement) -> VideoStatusResult)? = null,
) : VideoModel {

    override val provider: String = "test-provider"
    override val modelId: String = "video-1"

    var generateCalls: Int = 0
        private set
    var startCalls: Int = 0
        private set
    var statusCalls: Int = 0
        private set

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doGenerate(options: VideoCallOptions): VideoResult? {
        val answer = generate ?: return null
        generateCalls++
        return answer(options)
    }

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult? {
        val answer = start ?: return null
        startCalls++
        return answer(options, webhookUrl)
    }

    override suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>?,
    ): VideoStatusResult? {
        val answer = status ?: return null
        statusCalls++
        return answer(operation)
    }
}
