package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult as SdkImageResult
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.RerankingModel
import com.sabreware.aide.aisdk.RerankingResult
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.VideoStartResult
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.core.domain.audio.AudioSynthesisOptions
import com.sabreware.aide.core.domain.image.ImageOptions
import com.sabreware.aide.core.domain.image.ImageResult
import com.sabreware.aide.core.domain.video.GeneratedVideo
import com.sabreware.aide.core.domain.video.VideoJob
import com.sabreware.aide.core.domain.video.VideoStatus
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The translation between AIDE's modality ports and `:aisdk`, exercised against fake models: what the
 * adapters own is the mapping, and an HTTP double would test the SDK's wire rather than this layer's.
 *
 * The video handle gets the most attention because it is the only piece with state that has to outlive
 * the process — a handle that cannot be turned back into a status call is a render someone paid for and
 * lost.
 */
class AiSdkModalityEnginesTest {

    @Test
    fun `an unconfigured vendor says what to do about it`() = runTest {
        val engine = AiSdkImageEngine(providerFactory = { null }, vendor = "OpenAI")
        val failure = runCatching { engine.generate("gpt-image-1", "a cat") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue("OpenAI isn't set up" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a vendor that serves none of a modality is distinct from a missing key`() = runTest {
        val engine = AiSdkImageEngine(providerFactory = { FakeProvider() }, vendor = "OpenAI")
        val failure = runCatching { engine.generate("gpt-image-1", "a cat") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("fake serves no image models", failure.message)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `base64 images are decoded, and the requested count reaches the model`() = runTest {
        val model = FakeImageModel(BinaryData.Base64(Base64.encode(byteArrayOf(1, 2, 3))))
        val engine = AiSdkImageEngine({ FakeProvider(image = model) }, vendor = "OpenAI")

        val results = engine.generate("gpt-image-1", "a cat", ImageOptions(count = 1, size = "512x512"))

        val image = assertIs<ImageResult.Bytes>(results.single())
        assertEquals(listOf<Byte>(1, 2, 3), image.bytes.toList())
        assertEquals("png", image.format)
        assertEquals("512x512", model.lastOptions?.size)
        assertEquals("a cat", model.lastOptions?.prompt)
    }

    @Test
    fun `the whole batch goes to embedMany, which splits it against the model's own ceiling`() = runTest {
        val model = FakeEmbeddingModel(maxPerCall = 2)
        val engine = AiSdkEmbeddingEngine({ FakeProvider(embedding = model) }, vendor = "OpenAI")

        val vectors = engine.embed("text-embedding-3-small", listOf("a", "b", "c"))

        assertEquals(3, vectors.size)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), model.calls)
    }

    @Test
    fun `the vendor's own content type wins over what the container name implies`() = runTest {
        val model = FakeSpeechModel(contentType = "audio/wav; codecs=1")
        val engine = AiSdkSpeechEngine({ FakeProvider(speech = model) }, vendor = "ElevenLabs")

        val audio = engine.synthesize("eleven_v3", "hello", AudioSynthesisOptions(outputFormat = "mp3"))

        assertEquals("audio/wav", audio.mediaType)
    }

    @Test
    fun `a vendor that reports no content type falls back to the requested container`() = runTest {
        val model = FakeSpeechModel(contentType = null)
        val engine = AiSdkSpeechEngine({ FakeProvider(speech = model) }, vendor = "ElevenLabs")

        // ElevenLabs names a codec, a rate and a bitrate at once, so the prefix is what carries the codec.
        assertEquals("audio/pcm", engine.synthesize("eleven_v3", "hi", AudioSynthesisOptions(outputFormat = "pcm_44100")).mediaType)
        assertEquals("audio/mpeg", engine.synthesize("eleven_v3", "hi").mediaType)
    }

    @Test
    fun `transcripts keep their timings`() = runTest {
        val model = FakeTranscriptionModel()
        val engine = AiSdkTranscriptionEngine({ FakeProvider(transcription = model) }, vendor = "OpenAI")

        val transcript = engine.transcribe("whisper-1", byteArrayOf(9), "audio/mpeg")

        assertEquals("hello there", transcript.text)
        assertEquals(1.5, transcript.segments.single().endSecond)
        assertEquals("en", transcript.language)
        assertEquals(1.5, transcript.durationInSeconds)
    }

    @Test
    fun `ranks come back as positions into the submitted list`() = runTest {
        val engine = AiSdkRerankEngine({ FakeProvider(reranking = FakeRerankingModel()) }, vendor = "Cohere")

        val ranked = engine.rerank("rerank-v3.5", "q", listOf("a", "b", "c"), topN = 2)

        assertEquals(listOf(2, 0), ranked.map { it.index })
        assertEquals(0.9, ranked.first().relevanceScore)
    }

    @Test
    fun `a started job survives as text and resolves the same model when polled back`() = runTest {
        val model = FakeVideoModel()
        val provider = FakeProvider(video = model)
        val engine = AiSdkVideoEngine({ provider }, providerId = "fal", vendor = "fal")

        val job = engine.start("veo-3", "a kite")
        // The property the split exists for: nothing but these two strings crosses a process boundary.
        val restored = VideoJob(providerId = job.providerId, handle = "" + job.handle)

        assertEquals(VideoStatus.Pending, engine.status(restored))
        assertEquals("veo-3", provider.lastVideoModelId)
        assertEquals("job-1", model.lastStatusOperation?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }

    @Test
    fun `a finished job hands back its clips`() = runTest {
        val model = FakeVideoModel(finished = true)
        val engine = AiSdkVideoEngine({ FakeProvider(video = model) }, providerId = "fal", vendor = "fal")

        val ready = assertIs<VideoStatus.Ready>(engine.status(engine.start("veo-3", "a kite")))

        assertEquals(GeneratedVideo.Url("https://fal/1.mp4", "video/mp4"), ready.videos.single())
    }

    @Test
    fun `a handle this engine did not write is refused rather than polled`() = runTest {
        val engine = AiSdkVideoEngine({ FakeProvider(video = FakeVideoModel()) }, providerId = "fal", vendor = "fal")

        val failure = runCatching { engine.status(VideoJob("fal", "not-json")) }.exceptionOrNull()

        assertEquals("Video job handle is not a fal job", failure?.message)
    }
}

private class FakeProvider(
    private val image: ImageModel? = null,
    private val embedding: EmbeddingModel? = null,
    private val speech: SpeechModel? = null,
    private val transcription: TranscriptionModel? = null,
    private val reranking: RerankingModel? = null,
    private val video: VideoModel? = null,
) : Provider {
    override val providerId: String = "fake"
    override fun imageModel(modelId: String): ImageModel? = image
    override fun embeddingModel(modelId: String): EmbeddingModel? = embedding
    override fun speechModel(modelId: String): SpeechModel? = speech
    override fun transcriptionModel(modelId: String): TranscriptionModel? = transcription
    override fun rerankingModel(modelId: String): RerankingModel? = reranking
    var lastVideoModelId: String? = null

    override fun videoModel(modelId: String): VideoModel? {
        lastVideoModelId = modelId
        return video
    }
}

private class FakeImageModel(private val payload: BinaryData) : ImageModel {
    override val provider: String = "fake"
    override val modelId: String = "fake-image"
    var lastOptions: ImageCallOptions? = null

    override suspend fun doGenerate(options: ImageCallOptions): SdkImageResult {
        lastOptions = options
        return SdkImageResult(images = List(options.n) { payload }, response = ModalityResponse())
    }
}

private class FakeEmbeddingModel(private val maxPerCall: Int) : EmbeddingModel {
    override val provider: String = "fake"
    override val modelId: String = "fake-embedding"
    val calls = mutableListOf<List<String>>()

    override suspend fun maxEmbeddingsPerCall(): Int = maxPerCall

    // Serial, so the recorded call order is the chunk order the adapter is being checked for.
    override suspend fun supportsParallelCalls(): Boolean = false

    override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
        calls += options.values
        return EmbeddingResult(embeddings = options.values.map { listOf(it.length.toDouble()) })
    }
}

private class FakeSpeechModel(private val contentType: String?) : SpeechModel {
    override val provider: String = "fake"
    override val modelId: String = "fake-speech"

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult = SpeechResult(
        audio = BinaryData.Bytes(byteArrayOf(7)),
        response = ModalityResponse(headers = contentType?.let { mapOf("Content-Type" to it) }),
    )
}

private class FakeTranscriptionModel : TranscriptionModel {
    override val provider: String = "fake"
    override val modelId: String = "fake-transcription"

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult =
        TranscriptionResult(
            text = "hello there",
            segments = listOf(TranscriptionResult.Segment("hello there", 0.0, 1.5)),
            language = "en",
            durationInSeconds = 1.5,
            response = ModalityResponse(),
        )
}

private class FakeRerankingModel : RerankingModel {
    override val provider: String = "fake"
    override val modelId: String = "fake-reranking"

    override suspend fun doRerank(options: RerankingCallOptions): RerankingResult = RerankingResult(
        ranking = listOf(RerankingResult.Rank(2, 0.9), RerankingResult.Rank(0, 0.4)),
    )
}

private class FakeVideoModel(private val finished: Boolean = false) : VideoModel {
    override val provider: String = "fake"
    override val modelId: String = "fake-video"
    var lastStatusOperation: JsonElement? = null

    override suspend fun doStart(options: VideoCallOptions, webhookUrl: String?): VideoStartResult =
        VideoStartResult(operation = buildJsonObject { put("id", "job-1") })

    override suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>?,
    ): VideoStatusResult {
        lastStatusOperation = operation
        return if (finished) {
            VideoStatusResult.Completed(listOf(VideoData.Url("https://fal/1.mp4", "video/mp4")))
        } else {
            VideoStatusResult.Pending()
        }
    }
}
