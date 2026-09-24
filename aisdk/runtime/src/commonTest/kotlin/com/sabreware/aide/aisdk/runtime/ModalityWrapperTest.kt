package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.Embedding
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobTimeoutError
import com.sabreware.aide.aisdk.util.PollPolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What each wrapper exists for, rather than that it forwards a call.
 *
 * The cases here are the ones a caller would otherwise get wrong by hand: a batch that crosses the
 * model's per-call ceiling, a fan-out that must not exceed it, and a video job resumed from a handle
 * that was serialized and read back rather than held in memory.
 */
class ModalityWrapperTest {

    // -----------------------------------------------------------------------------------------------
    // embed / embedMany
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `embedMany splits a batch that crosses the model's per-call ceiling`() = runTest {
        val model = RecordingEmbeddingModel(maxPerCall = 2)

        val result = embedMany(model, EmbeddingCallOptions(listOf("a", "b", "c", "d", "e")))

        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")), model.calls)
        // The vectors come back in the caller's own order, not in the order the waves completed —
        // a caller that cannot pair a vector to its value has nothing.
        assertEquals(listOf("a", "b", "c", "d", "e"), result.embeddings.map { it.tag() })
        assertEquals(5, result.usage)
        assertEquals(3, result.responses.size)
    }

    @Test
    fun `a model with no ceiling is called once`() = runTest {
        val model = RecordingEmbeddingModel(maxPerCall = null)

        embedMany(model, EmbeddingCallOptions(listOf("a", "b", "c")))

        assertEquals(listOf(listOf("a", "b", "c")), model.calls)
    }

    @Test
    fun `a model that refuses parallel calls is never given two at once`() = runTest {
        val model = RecordingEmbeddingModel(maxPerCall = 1, parallel = false)

        embedMany(model, EmbeddingCallOptions(listOf("a", "b", "c", "d")))

        assertEquals(4, model.calls.size)
        assertEquals(1, model.maxConcurrent)
    }

    @Test
    fun `a model that allows parallel calls gets them`() = runTest {
        val model = RecordingEmbeddingModel(maxPerCall = 1, parallel = true)

        embedMany(model, EmbeddingCallOptions(listOf("a", "b", "c", "d")))

        assertEquals(4, model.maxConcurrent)
    }

    @Test
    fun `embed reports a model that returned no vector`() = runTest {
        val model = RecordingEmbeddingModel(maxPerCall = null, vectors = false)

        assertFailsWith<InvalidResponseDataError> { embed(model, "a") }
    }

    @Test
    fun `cosine similarity ranks an identical vector above an orthogonal one`() {
        val query = listOf(1.0, 0.0)

        assertTrue(abs(cosineSimilarity(query, listOf(2.0, 0.0)) - 1.0) < 1e-9)
        assertEquals(0.0, cosineSimilarity(query, listOf(0.0, 1.0)))
        // A zero vector has no direction; 0.0 rather than the NaN that would sort arbitrarily.
        assertEquals(0.0, cosineSimilarity(query, listOf(0.0, 0.0)))
    }

    // -----------------------------------------------------------------------------------------------
    // generateImage
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a request past the model's per-call limit becomes several calls, not an error`() = runTest {
        val model = CountingImageModel(maxPerCall = 3)

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 8))

        // Full batches then the remainder — never padded up, because vendors bill per image returned.
        assertEquals(listOf(3, 3, 2), model.requested)
        assertEquals(8, result.images.size)
    }

    @Test
    fun `a model that documents no limit is asked for one image at a time`() = runTest {
        val model = CountingImageModel(maxPerCall = null)

        generateImage(model, ImageCallOptions(prompt = "a cat", n = 3))

        assertEquals(listOf(1, 1, 1), model.requested)
    }

    @Test
    fun `the same warning from every call in a fan-out is reported once`() = runTest {
        val model = CountingImageModel(maxPerCall = 1)

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 4, seed = 7))

        assertEquals(listOf(Warning.Unsupported("seed")), result.warnings)
    }

    // -----------------------------------------------------------------------------------------------
    // transcribe
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `an empty transcript is a failure, not an empty answer`() = runTest {
        val model = EmptyTranscriptionModel

        assertFailsWith<NoContentGeneratedError> {
            transcribe(model, TranscriptionCallOptions(BinaryData.Bytes(byteArrayOf(1, 2, 3)), "audio/wav"))
        }
    }

    // -----------------------------------------------------------------------------------------------
    // generateVideo
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a job is resumed from a handle that was serialized and read back`() = runTest {
        val model = AsyncVideoModel(pendingChecks = 2)
        val started = startVideo(model, VideoCallOptions(prompt = "a wave"))

        // The round trip is the point: what a caller persists is text, and the model must accept its
        // own handle back from storage rather than only from the object it just returned.
        val persisted = Json.encodeToString(JsonElement.serializer(), started.operation)
        val restored = Json.decodeFromString(JsonElement.serializer(), persisted)

        val result = awaitVideo(model, restored, poll = PollPolicy.Fast)

        assertEquals(3, model.statusChecks)
        assertEquals("https://example.test/job-1.mp4", (result.videos.single() as VideoData.Url).url)
    }

    @Test
    fun `a single status check does not wait`() = runTest {
        val model = AsyncVideoModel(pendingChecks = 5)
        val started = startVideo(model, VideoCallOptions(prompt = "a wave"))

        val status = videoStatus(model, started.operation)

        assertTrue(status is VideoStatusResult.Pending)
        assertEquals(1, model.statusChecks)
    }

    @Test
    fun `generateVideo carries the start call's warnings onto the finished clip`() = runTest {
        val model = AsyncVideoModel(pendingChecks = 1, startWarning = Warning.Unsupported("fps"))

        val result = generateVideo(model, VideoCallOptions(prompt = "a wave"), poll = PollPolicy.Fast)

        assertEquals(listOf(Warning.Unsupported("fps")), result.warnings)
    }

    @Test
    fun `a refused job fails immediately rather than being polled again`() = runTest {
        val model = AsyncVideoModel(pendingChecks = 1, failure = "The prompt was rejected.")
        val started = startVideo(model, VideoCallOptions(prompt = "a wave"))

        assertFailsWith<JobFailedError> { awaitVideo(model, started.operation, poll = PollPolicy.Fast) }
        // Two: the pending check, then the refusal. A refusal that was retried would show more.
        assertEquals(2, model.statusChecks)
    }

    @Test
    fun `giving up on the wait abandons the wait, not the job`() = runTest {
        val model = AsyncVideoModel(pendingChecks = Int.MAX_VALUE)
        val started = startVideo(model, VideoCallOptions(prompt = "a wave"))
        var clock = 0L

        assertFailsWith<JobTimeoutError> {
            awaitVideo(
                model = model,
                operation = started.operation,
                poll = PollPolicy(initialDelayMillis = 10, timeoutMillis = 100),
                elapsedMillis = { clock += 60; clock },
            )
        }

        // The handle is still valid — nothing about giving up cancelled the render.
        assertTrue(videoStatus(model, started.operation) is VideoStatusResult.Pending)
    }
}

// ---------------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------------

/** Tags each vector with the value it embedded, so ordering across chunks is checkable. */
private fun Embedding.tag(): String = TAGS[this[0].toInt()]

private val TAGS = ('a'..'z').map { it.toString() }

private class RecordingEmbeddingModel(
    private val maxPerCall: Int?,
    private val parallel: Boolean = true,
    private val vectors: Boolean = true,
) : EmbeddingModel {

    override val provider: String = "test"
    override val modelId: String = "embed-1"

    val calls = mutableListOf<List<String>>()
    var maxConcurrent = 0
        private set

    private var inFlight = 0

    override suspend fun maxEmbeddingsPerCall(): Int? = maxPerCall

    override suspend fun supportsParallelCalls(): Boolean = parallel

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        calls += options.values
        inFlight++
        // The suspension is what makes overlap observable: without one, a call started concurrently
        // still runs to completion before its sibling begins, and the count would read 1 either way.
        yield()
        maxConcurrent = maxOf(maxConcurrent, inFlight)
        inFlight--
        return EmbeddingResult(
            embeddings = if (!vectors) {
                emptyList()
            } else {
                options.values.map { listOf(TAGS.indexOf(it).toDouble(), 0.0) }
            },
            usage = options.values.size,
            response = ModalityResponse(modelId = modelId),
        )
    }
}

private class CountingImageModel(private val maxPerCall: Int?) : ImageModel {

    override val provider: String = "test"
    override val modelId: String = "image-1"

    val requested = mutableListOf<Int>()

    override suspend fun maxImagesPerCall(): Int? = maxPerCall

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        requested += options.n
        return ImageResult(
            images = List(options.n) { BinaryData.Base64("image-$it") },
            warnings = if (options.seed == null) emptyList() else listOf(Warning.Unsupported("seed")),
            response = ModalityResponse(modelId = modelId),
        )
    }
}

private object EmptyTranscriptionModel : TranscriptionModel {

    override val provider: String = "test"
    override val modelId: String = "transcribe-1"

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult =
        TranscriptionResult(text = "   ", response = ModalityResponse(modelId = modelId))
}

/** A model whose job is pending for [pendingChecks] status calls and then settles. */
private class AsyncVideoModel(
    private val pendingChecks: Int,
    private val startWarning: Warning? = null,
    private val failure: String? = null,
) : VideoModel {

    override val provider: String = "test"
    override val modelId: String = "video-1"

    var statusChecks = 0
        private set

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult =
        VideoStartResult(
            operation = buildJsonObject { put("jobId", "job-1") },
            warnings = listOfNotNull(startWarning),
        )

    override suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>?,
    ): VideoStatusResult {
        statusChecks++
        val jobId = requireNotNull(operation.jobId()) { "The handle did not survive persistence." }
        if (statusChecks <= pendingChecks) return VideoStatusResult.Pending()
        failure?.let { return VideoStatusResult.Failed(it) }
        return VideoStatusResult.Completed(
            videos = listOf(VideoData.Url("https://example.test/$jobId.mp4", "video/mp4")),
        )
    }
}

private fun JsonElement.jobId(): String? =
    (this as? kotlinx.serialization.json.JsonObject)
        ?.get("jobId")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
