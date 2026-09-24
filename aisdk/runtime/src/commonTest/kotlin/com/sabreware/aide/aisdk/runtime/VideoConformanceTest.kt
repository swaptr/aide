package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoResult
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobTimeoutError
import com.sabreware.aide.aisdk.util.PollPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The reference's `ai/src/generate-video` and `start-video` cases, translated.
 *
 * Video is the modality whose contract is shaped by how long the work takes, so its wrapper cases are
 * about a job outliving the call that submitted it: a status check that fails must be retried without
 * resubmitting a render someone is paying for, a refusal must not be polled again because the same
 * prompt will be refused every time, and everything the provider said along the way — including from a
 * status poll that came back still pending — has to reach the caller at the end. The last of those is
 * where ours currently loses information.
 *
 * The webhook half of the reference's suite is [VideoWebhookConformanceTest].
 */
class VideoConformanceTest {

    private val clip = VideoData.Base64("bXA0", "video/mp4")

    // -----------------------------------------------------------------------------------------------
    // Which flow gets used
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a model with a synchronous endpoint is called directly and never started`() = runTest {
        val model = ScriptedVideoModel(
            generate = { VideoResult(videos = listOf(clip)) },
            start = { error("doStart must not be called when doGenerate answered") },
        )

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        assertEquals(listOf(clip), result.videos)
        assertEquals(0, model.startCalls)
    }

    @Test
    fun `a model with only the start-status pair is started and then polled`() = runTest {
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = { VideoStatusResult.Completed(videos = listOf(clip)) },
        )

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        assertEquals(1, model.startCalls)
        assertEquals(1, model.statusCalls)
        assertEquals(listOf(clip), result.videos)
    }

    @Test
    fun `a model with neither flow says so rather than hanging or returning nothing`() = runTest {
        val model = ScriptedVideoModel()

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)
        }

        assertEquals("doStart/doStatus", error.functionality)
    }

    @Test
    fun `videoStatus on a model with no asynchronous flow is the same refusal`() = runTest {
        assertFailsWith<UnsupportedFunctionalityError> {
            videoStatus(ScriptedVideoModel(), JsonPrimitive("op-1"))
        }
    }

    // -----------------------------------------------------------------------------------------------
    // The handle
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `the operation handed to doStatus is the one doStart returned, unread by the wrapper`() =
        runTest {
            val handle = buildJsonObject {
                put("jobId", "op-1")
                put("region", "us-central1")
            }
            var seen: JsonElement? = null
            val model = ScriptedVideoModel(
                start = { VideoStartResult(operation = handle) },
                status = { operation ->
                    seen = operation
                    VideoStatusResult.Completed(videos = listOf(clip))
                },
            )

            generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

            // A wrapper that reduced the handle to an id would work on the vendor it was written for and
            // fail on every vendor whose job reference is a URL, a pair, or a signed blob.
            assertEquals(handle, seen)
        }

    @Test
    fun `headers travel with each status check, not only with the start call`() = runTest {
        val seen = mutableListOf<Map<String, String>?>()
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = { VideoStatusResult.Completed(videos = listOf(clip)) },
            statusHeaders = { seen += it },
        )

        generateVideo(
            model,
            VideoCallOptions(prompt = "a wave", headers = mapOf("x-caller" to "aide")),
            poll = PollPolicy.Fast,
        )

        assertEquals(1, seen.size)
        assertEquals(mapOf("x-caller" to "aide"), seen.single())
    }

    // -----------------------------------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a pending job is polled until it completes, and the start call is not repeated`() = runTest {
        var checks = 0
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = {
                if (++checks < 3) VideoStatusResult.Pending()
                else VideoStatusResult.Completed(videos = listOf(clip))
            },
        )

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        // One start, three status checks: a poll loop that resubmitted would bill a second render.
        assertEquals(1, model.startCalls)
        assertEquals(3, checks)
        assertEquals(listOf(clip), result.videos)
    }

    @Test
    fun `a refused job fails immediately instead of being asked again`() = runTest {
        var checks = 0
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = {
                checks++
                VideoStatusResult.Failed("content policy")
            },
        )

        assertFailsWith<JobFailedError> {
            generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)
        }
        // The same prompt will be refused every time it is asked; retrying it burns a budget for nothing.
        assertEquals(1, checks)
    }

    @Test
    fun `a wait that runs out of budget names the budget it ran out of`() = runTest {
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = { VideoStatusResult.Pending() },
        )
        var clock = 0L

        val error = assertFailsWith<JobTimeoutError> {
            generateVideo(
                model,
                VideoCallOptions(prompt = "a wave"),
                poll = PollPolicy(initialDelayMillis = 10, timeoutMillis = 50),
                elapsedMillis = { clock += 30; clock },
            )
        }

        assertEquals(50, error.timeoutMillis)
    }

    @Test
    fun `awaitVideo polls a handle it was handed, with no start call of its own`() = runTest {
        var checks = 0
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = {
                if (++checks < 2) VideoStatusResult.Pending()
                else VideoStatusResult.Completed(videos = listOf(clip))
            },
        )

        val result = awaitVideo(
            model,
            JsonPrimitive("op-1"),
            poll = PollPolicy(initialDelayMillis = 1, timeoutMillis = 10_000),
        )

        // Resuming a job submitted by an earlier process is the same call as waiting on one submitted a
        // moment ago, which is the whole reason the handle is JSON rather than an object.
        assertEquals(0, model.startCalls)
        assertEquals(listOf(clip), result.videos)
    }

    // -----------------------------------------------------------------------------------------------
    // What reaches the caller at the end
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `warnings from the start call and from the completed status both arrive`() = runTest {
        val model = ScriptedVideoModel(
            start = {
                VideoStartResult(
                    operation = JsonPrimitive("op-1"),
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

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        // A model that clamped `fps` says so when the job is submitted, and the status response has no
        // reason to repeat it — so dropping either half loses the explanation entirely.
        assertEquals(
            listOf(Warning.Other("start warning"), Warning.Other("status warning")),
            result.warnings,
        )
    }

    @Test
    fun `provider metadata from the completed status wins over the start call's`() = runTest {
        val model = ScriptedVideoModel(
            start = {
                VideoStartResult(
                    operation = JsonPrimitive("op-1"),
                    providerMetadata = mapOf("p" to buildJsonObject { put("stage", "start") }),
                )
            },
            status = {
                VideoStatusResult.Completed(
                    videos = listOf(clip),
                    providerMetadata = mapOf("p" to buildJsonObject { put("stage", "done") }),
                )
            },
        )

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        assertEquals(mapOf("p" to buildJsonObject { put("stage", "done") }), result.providerMetadata)
    }

    @Test
    fun `the start call's provider metadata survives when the status reported none`() = runTest {
        val metadata: ProviderMetadata = mapOf("p" to buildJsonObject { put("jobId", "abc") })
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1"), providerMetadata = metadata) },
            status = { VideoStatusResult.Completed(videos = listOf(clip)) },
        )

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        assertEquals(metadata, result.providerMetadata)
    }

    /**
     * DEFECT — see the report. `awaitVideo` maps a [VideoStatusResult.Pending] to a bare
     * `JobStatus.InProgress()`, discarding its `warnings` and `providerMetadata`. The specification
     * declares both on the `Pending` variant on purpose — our own KDoc argues the point — and a vendor
     * that reports a clamped setting or a request id on the first poll and nothing afterwards has that
     * information dropped on the floor. The reference merges pending results into the final one.
     */
    @Test
    fun `warnings and metadata reported by a pending status are carried to the result`() = runTest {
        var checks = 0
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = {
                if (++checks == 1) {
                    VideoStatusResult.Pending(
                        warnings = listOf(Warning.Other("pending warning")),
                        providerMetadata = mapOf("p" to buildJsonObject { put("requestId", "req-001") }),
                    )
                } else {
                    VideoStatusResult.Completed(
                        videos = listOf(clip),
                        warnings = listOf(Warning.Other("completed warning")),
                    )
                }
            },
        )

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        assertEquals(
            listOf(Warning.Other("pending warning"), Warning.Other("completed warning")),
            result.warnings,
        )
        assertEquals("req-001", result.providerMetadata?.get("p")?.get("requestId")?.toString()?.trim('"'))
    }

    // -----------------------------------------------------------------------------------------------
    // startVideo / videoStatus, used on their own
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `startVideo hands the model the whole option surface and returns without waiting`() = runTest {
        var seen: VideoCallOptions? = null
        val model = ScriptedVideoModel(
            start = { options ->
                seen = options
                VideoStartResult(operation = JsonPrimitive("op-1"))
            },
            status = { error("doStatus must not be called by startVideo") },
        )

        val started = startVideo(
            model,
            VideoCallOptions(
                prompt = "a wave",
                n = 1,
                aspectRatio = "16:9",
                resolution = "1280x720",
                durationInSeconds = 5.0,
                fps = 24,
                seed = 7,
                generateAudio = true,
                providerOptions = mapOf("p" to buildJsonObject { put("k", "v") }),
                headers = mapOf("x-caller" to "aide"),
            ),
        )

        assertEquals("a wave", seen?.prompt)
        assertEquals("16:9", seen?.aspectRatio)
        assertEquals("1280x720", seen?.resolution)
        assertEquals(5.0, seen?.durationInSeconds)
        assertEquals(24, seen?.fps)
        assertEquals(7, seen?.seed)
        assertEquals(true, seen?.generateAudio)
        assertEquals(mapOf("x-caller" to "aide"), seen?.headers)
        assertEquals(JsonPrimitive("op-1"), started.operation)
        assertEquals(0, model.statusCalls)
    }

    @Test
    fun `startVideo surfaces the job reference and the metadata that came with it`() = runTest {
        val model = ScriptedVideoModel(
            start = {
                VideoStartResult(
                    operation = buildJsonObject { put("jobId", "job-42") },
                    warnings = listOf(Warning.Unsupported("fps")),
                    providerMetadata = mapOf(
                        "p" to buildJsonObject {
                            put("jobId", "job-42")
                            put("signingSecret", "s3cret")
                        },
                    ),
                    response = ModalityResponse(id = "res-1"),
                )
            },
        )

        val started = startVideo(model, VideoCallOptions(prompt = "a wave"))

        assertEquals(listOf(Warning.Unsupported("fps")), started.warnings)
        assertEquals("job-42", started.providerMetadata?.get("p")?.get("jobId")?.toString()?.trim('"'))
        assertEquals("res-1", started.response.id)
    }

    @Test
    fun `videoStatus checks once and reports pending without waiting`() = runTest {
        var checks = 0
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = {
                checks++
                VideoStatusResult.Pending()
            },
        )

        val status = videoStatus(model, JsonPrimitive("op-1"))

        assertTrue(status is VideoStatusResult.Pending)
        assertEquals(1, checks)
    }

    @Test
    fun `videoStatus returns the completed payload with its clips`() = runTest {
        val model = ScriptedVideoModel(
            start = { VideoStartResult(operation = JsonPrimitive("op-1")) },
            status = {
                VideoStatusResult.Completed(
                    videos = listOf(clip, VideoData.Url("https://example.test/a.mp4", "video/mp4")),
                )
            },
        )

        val status = videoStatus(model, JsonPrimitive("op-1"))

        assertEquals(2, (status as VideoStatusResult.Completed).videos.size)
    }

    /**
     * DEFECT — see the report. `startVideo` forwards `n` untouched, so a request for four clips against
     * a model whose `maxVideosPerCall` is one is submitted anyway. The reference refuses both a
     * non-positive `n` and one past the model's own limit before spending a request, which matters more
     * here than anywhere else: a video render is the most expensive call in the library, and the vendor
     * answers a bad `n` with a 400 that names a limit the model could have been asked for.
     */
    @Test
    fun `startVideo refuses an n the model cannot serve, before spending a render`() = runTest {
        val model = ScriptedVideoModel(start = { VideoStartResult(operation = JsonPrimitive("op-1")) })

        assertFailsWith<InvalidArgumentError> {
            startVideo(model, VideoCallOptions(prompt = "a wave", n = 0))
        }
        assertFailsWith<InvalidArgumentError> {
            startVideo(model, VideoCallOptions(prompt = "a wave", n = 4))
        }
        assertEquals(0, model.startCalls)
    }
}

// ---------------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------------

/**
 * A model whose three entry points are supplied per case.
 *
 * `null` for one of them is the interesting state rather than a missing feature: the specification says
 * a provider implements EITHER `doGenerate` or the start/status pair, so "this model has no synchronous
 * endpoint" is what the default null MEANS, and several cases below exist to pin what the wrapper does
 * with each combination.
 */
private class ScriptedVideoModel(
    private val generate: (suspend (VideoCallOptions) -> VideoResult)? = null,
    private val start: (suspend (VideoCallOptions) -> VideoStartResult)? = null,
    private val status: (suspend (JsonElement) -> VideoStatusResult)? = null,
    private val statusHeaders: ((Map<String, String>?) -> Unit)? = null,
) : VideoModel {

    override val provider: String = "test-provider"
    override val modelId: String = "video-1"

    var startCalls: Int = 0
        private set
    var statusCalls: Int = 0
        private set

    override suspend fun maxVideosPerCall(): Int = 1

    override suspend fun doGenerate(options: VideoCallOptions): VideoResult? = generate?.invoke(options)

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult? {
        val answer = start ?: return null
        startCalls++
        return answer(options)
    }

    override suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>?,
    ): VideoStatusResult? {
        val answer = status ?: return null
        statusCalls++
        statusHeaders?.invoke(headers)
        return answer(operation)
    }
}
