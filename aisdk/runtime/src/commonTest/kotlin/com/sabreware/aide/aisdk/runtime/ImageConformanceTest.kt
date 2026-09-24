package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.ImageUsage
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.NoImageGeneratedError
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.SpeechModel
import com.sabreware.aide.aisdk.SpeechResult
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionModel
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.RetryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The reference's `ai/src/generate-image`, `generate-speech` and `transcribe` cases, translated.
 *
 * The image wrapper is the one with real logic in it: `maxImagesPerCall` is one on DALL-E 3 and on
 * every Imagen variant, so a request for eight images is eight calls on the models people actually
 * use, and everything a caller wants back — the images, the warnings, the token count, the per-image
 * metadata — has to be reassembled from those calls without losing any of it. The reference's cases
 * are mostly about what survives that reassembly, and two of them do not survive ours.
 *
 * One reference case is NOT here, for a reason that is a gap in the contract rather than in the test:
 * at `ai@7.0.85` upstream attaches provider metadata to each returned image individually, so a
 * two-image fan-out can report a different revised prompt per image. `GeneratedImages.images` is a
 * list of raw payloads with nowhere to carry it, so that case cannot be written against the types we
 * have — a specification change, not a test.
 *
 * Speech and transcription have no fan-out, so their cases pin the other half: that the wrapper does
 * not let a 200-with-nothing-in-it through as a successful result. A vendor returning an empty
 * transcript for an unsupported codec it accepted anyway, or zero bytes of audio, is common enough that
 * a caller writing the result to disk discovers it only when someone presses play.
 */
class ImageConformanceTest {

    private val pngBase64 = "iVBORw0KGgo="
    private val jpegBase64 = "/9j/4AAQSkZJRg=="
    private val gifBase64 = "R0lGODlhAQ=="

    // -----------------------------------------------------------------------------------------------
    // generateImage — fan-out
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a request past the ceiling splits into full batches then the remainder`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = 2) { options ->
            val images = when (call++) {
                0 -> listOf(pngBase64, jpegBase64)
                else -> listOf(gifBase64)
            }
            ImageResult(images = images.map { BinaryData.Base64(it) }, response = ModalityResponse())
        }

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 3))

        assertEquals(listOf(2, 1), model.requested)
        // Images come back in call order, so the caller's n-th image is the n-th image.
        assertEquals(
            listOf(pngBase64, jpegBase64, gifBase64),
            result.images.map { (it as BinaryData.Base64).value },
        )
    }

    @Test
    fun `every call in a fan-out gets the same options apart from n`() = runTest {
        val model = ScriptedImageModel(maxPerCall = 2) { options ->
            ImageResult(
                images = List(options.n) { BinaryData.Base64(pngBase64) },
                response = ModalityResponse(),
            )
        }

        generateImage(
            model,
            ImageCallOptions(
                prompt = "a cat",
                n = 3,
                size = "1024x1024",
                aspectRatio = "16:9",
                seed = 12345,
                providerOptions = mapOf("mock-provider" to buildJsonObject { put("style", "vivid") }),
                headers = mapOf("custom-request-header" to "request-header-value"),
            ),
        )

        assertEquals(2, model.seen.size)
        // A splitter that rebuilt the options rather than copying them would silently drop the seed on
        // the second call, and two images of the same prompt would come back from different seeds.
        model.seen.forEach { options ->
            assertEquals("a cat", options.prompt)
            assertEquals("1024x1024", options.size)
            assertEquals("16:9", options.aspectRatio)
            assertEquals(12345, options.seed)
            assertEquals(
                buildJsonObject { put("style", "vivid") },
                options.providerOptions?.get("mock-provider"),
            )
            assertEquals(mapOf("custom-request-header" to "request-header-value"), options.headers)
        }
        assertEquals(listOf(2, 1), model.requested)
    }

    @Test
    fun `n below one is refused rather than sent as a request for nothing`() = runTest {
        val model = ScriptedImageModel(maxPerCall = 1) {
            ImageResult(images = emptyList(), response = ModalityResponse())
        }

        assertFailsWith<InvalidArgumentError> {
            generateImage(model, ImageCallOptions(prompt = "a cat", n = 0))
        }
        assertEquals(0, model.requested.size)
    }

    // -----------------------------------------------------------------------------------------------
    // generateImage — what survives the reassembly
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `warnings raised by different calls all arrive`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = 2) { options ->
            ImageResult(
                images = List(options.n) { BinaryData.Base64(pngBase64) },
                warnings = listOf(Warning.Other(if (call++ == 0) "1" else "2")),
                response = ModalityResponse(),
            )
        }

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 3))

        // Our wrapper de-duplicates identical warnings — a documented divergence, since a fan-out of
        // eight against a model that ignores `seed` would otherwise say so eight times — but two
        // DIFFERENT warnings must both survive, which is what the reference is pinning here.
        assertEquals(listOf(Warning.Other("1"), Warning.Other("2")), result.warnings)
    }

    @Test
    fun `there is one response per call, carrying that call's own identity`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = 1) { options ->
            ImageResult(
                images = List(options.n) { BinaryData.Base64(pngBase64) },
                response = ModalityResponse(
                    modelId = "test-model",
                    timestamp = 1_700_000_000_000,
                    headers = mapOf("x-test" to "value-${call++}"),
                ),
            )
        }

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 2))

        assertEquals(listOf("test-model", "test-model"), result.responses.map { it.modelId })
        assertEquals(
            listOf("value-0", "value-1"),
            result.responses.map { it.headers?.get("x-test") },
        )
    }

    @Test
    fun `provider metadata from a single call is returned verbatim`() = runTest {
        val metadata: ProviderMetadata = mapOf(
            "testProvider" to buildJsonObject { put("revisedPrompt", "test-revised-prompt") },
        )
        val model = ScriptedImageModel(maxPerCall = 2) { options ->
            ImageResult(
                images = List(options.n) { BinaryData.Base64(pngBase64) },
                providerMetadata = metadata,
                response = ModalityResponse(),
            )
        }

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 2))

        assertEquals(metadata, result.providerMetadata)
    }

    @Test
    fun `no images at all is an error, not an empty list handed back as success`() = runTest {
        val model = ScriptedImageModel(maxPerCall = null) {
            ImageResult(images = emptyList(), response = ModalityResponse())
        }

        assertFailsWith<NoContentGeneratedError> {
            generateImage(model, ImageCallOptions(prompt = "a cat"))
        }
    }

    // -----------------------------------------------------------------------------------------------
    // generateImage — empty results, and what a retry keeps
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `an empty result is asked again under the policy, and every attempt stays on the result`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = null) {
            if (call++ == 0) {
                ImageResult(
                    images = emptyList(),
                    usage = ImageUsage(inputTokens = 10, outputTokens = 0, totalTokens = 10),
                    providerMetadata = mapOf("testProvider" to buildJsonObject { put("attempt", "empty") }),
                    response = ModalityResponse(modelId = "empty-attempt-model", timestamp = 1),
                )
            } else {
                ImageResult(
                    images = listOf(BinaryData.Base64(pngBase64)),
                    usage = ImageUsage(inputTokens = 20, outputTokens = 1, totalTokens = 21),
                    providerMetadata = mapOf("testProvider" to buildJsonObject { put("attempt", "success") }),
                    response = ModalityResponse(modelId = "successful-attempt-model", timestamp = 2),
                )
            }
        }

        val result = generateImage(
            model,
            ImageCallOptions(prompt = "a cat"),
            retry = RetryPolicy(maxRetries = 2, initialDelayMillis = 0),
        )

        assertEquals(2, model.seen.size)
        assertEquals(1, result.images.size)
        assertEquals(listOf(0, 1), result.calls.map { it.images.size })
        assertEquals(
            listOf("empty", "success"),
            result.calls.map { it.providerMetadata?.get("testProvider")?.get("attempt")?.jsonPrimitive?.content },
        )
        assertEquals(listOf("empty-attempt-model", "successful-attempt-model"), result.responses.map { it.modelId })
        assertEquals(ImageUsage(inputTokens = 30, outputTokens = 1, totalTokens = 31), result.usage)
    }

    @Test
    fun `a result the provider marks final is not asked again`() = runTest {
        val model = ScriptedImageModel(maxPerCall = null) {
            ImageResult(images = emptyList(), isRetryable = false, response = ModalityResponse(id = "res-1"))
        }

        val error = assertFailsWith<NoImageGeneratedError> {
            generateImage(model, ImageCallOptions(prompt = "a cat"), retry = RetryPolicy(maxRetries = 2, initialDelayMillis = 0))
        }

        assertEquals(1, model.seen.size)
        assertEquals(listOf(ModalityResponse(id = "res-1")), error.responses)
    }

    @Test
    fun `no images once the retries are spent is an error carrying every attempt's response`() = runTest {
        var call = 0L
        val model = ScriptedImageModel(maxPerCall = null) {
            ImageResult(images = emptyList(), response = ModalityResponse(modelId = "m", timestamp = ++call))
        }

        val error = assertFailsWith<NoImageGeneratedError> {
            generateImage(model, ImageCallOptions(prompt = "a cat"), retry = RetryPolicy(maxRetries = 1, initialDelayMillis = 0))
        }

        assertEquals("No image generated.", error.message)
        assertEquals(2, model.seen.size)
        assertEquals(listOf(1L, 2L), error.responses.map { it.timestamp })
    }

    @Test
    fun `the diagnostics of an empty call survive onto the error`() = runTest {
        val model = ScriptedImageModel(maxPerCall = null) {
            ImageResult(
                images = emptyList(),
                isRetryable = false,
                warnings = listOf(Warning.Other("prompt was blocked")),
                usage = ImageUsage(inputTokens = 7, outputTokens = 0, totalTokens = 7),
                response = ModalityResponse(modelId = "m", timestamp = 1, headers = mapOf("x-request-id" to "request-id")),
            )
        }

        val error = assertFailsWith<NoImageGeneratedError> { generateImage(model, ImageCallOptions(prompt = "a cat")) }

        // The response identity of the empty call — the request id a vendor ticket asks for — is on the
        // error. (The reference also attaches each call's warnings and usage; see the lane report.)
        val response = error.responses.single()
        assertEquals("request-id", response.headers?.get("x-request-id"))
        assertEquals(1L, response.timestamp)
    }

    @Test
    fun `transport failures and empty results draw on one retry budget`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = null) {
            if (call++ == 0) {
                throw APICallError("Overloaded", url = "https://images.example.com/v1", statusCode = 503, isRetryable = true)
            }
            ImageResult(images = emptyList(), response = ModalityResponse(id = "empty"))
        }

        val error = assertFailsWith<NoImageGeneratedError> {
            generateImage(model, ImageCallOptions(prompt = "a cat"), retry = RetryPolicy(maxRetries = 1, initialDelayMillis = 0))
        }

        // The one retry went on the 503; the empty answer that followed had no budget left.
        assertEquals(2, model.seen.size)
        assertEquals(listOf("empty"), error.responses.map { it.id })
    }

    @Test
    fun `usage stays absent when the provider reported none`() = runTest {
        val model = ScriptedImageModel(maxPerCall = null) {
            ImageResult(images = listOf(BinaryData.Base64(pngBase64)), response = ModalityResponse())
        }

        assertNull(generateImage(model, ImageCallOptions(prompt = "a cat")).usage)
    }

    /**
     * `totalTokens` is carried rather than derived, because several vendors bill a third number that is
     * not the sum — image tokens priced separately, a minimum charge — so a wrapper that rebuilds the
     * usage block from the two counts it understands reports no cost at all for those vendors.
     */
    @Test
    fun `usage adds up across calls, total tokens included`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = 1) { options ->
            val first = call++ == 0
            ImageResult(
                images = List(options.n) { BinaryData.Base64(pngBase64) },
                usage = ImageUsage(
                    inputTokens = if (first) 10 else 5,
                    outputTokens = 0,
                    totalTokens = if (first) 10 else 5,
                ),
                response = ModalityResponse(),
            )
        }

        val usage = generateImage(model, ImageCallOptions(prompt = "a cat", n = 2)).usage

        assertEquals(15, usage?.inputTokens)
        assertEquals(0, usage?.outputTokens)
        assertEquals(15, usage?.totalTokens)
    }

    /**
     * DEFECT — see the report. `generateImage` keeps `firstNotNullOfOrNull { it.providerMetadata }`,
     * so on a model with a per-call ceiling of one — DALL-E 3, every Imagen variant — a request for two
     * images returns only the first image's revised prompt, and the second image's is gone. The
     * reference merges per provider id across calls.
     */
    @Test
    fun `provider metadata is merged across calls rather than taken from the first`() = runTest {
        var call = 0
        val model = ScriptedImageModel(maxPerCall = 1) { options ->
            val index = call++
            ImageResult(
                images = List(options.n) { BinaryData.Base64(pngBase64) },
                providerMetadata = mapOf(
                    "testProvider" to buildJsonObject { put("revisedPrompt", "prompt-$index") },
                ),
                response = ModalityResponse(),
            )
        }

        val result = generateImage(model, ImageCallOptions(prompt = "a cat", n = 2))

        assertEquals(
            "prompt-1",
            result.providerMetadata?.get("testProvider")?.get("revisedPrompt")?.toString()?.trim('"'),
        )
    }

    // -----------------------------------------------------------------------------------------------
    // generateSpeech
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `generateSpeech forwards the whole option surface and returns the audio`() = runTest {
        var seen: SpeechCallOptions? = null
        val model = ScriptedSpeechModel { options ->
            seen = options
            SpeechResult(
                audio = BinaryData.Bytes(byteArrayOf(1, 2, 3)),
                warnings = listOf(Warning.Unsupported("speed")),
                response = ModalityResponse(modelId = "speech-1", id = "res-1"),
            )
        }

        val result = generateSpeech(
            model,
            SpeechCallOptions(
                text = "hello",
                voice = "alloy",
                outputFormat = "mp3",
                instructions = "read it slowly",
                speed = 1.5,
                language = "en",
                providerOptions = mapOf("p" to buildJsonObject { put("k", "v") }),
                headers = mapOf("custom-request-header" to "request-header-value"),
            ),
        )

        assertEquals("hello", seen?.text)
        assertEquals("alloy", seen?.voice)
        assertEquals("mp3", seen?.outputFormat)
        assertEquals("read it slowly", seen?.instructions)
        assertEquals(1.5, seen?.speed)
        assertEquals("en", seen?.language)
        assertEquals(mapOf("custom-request-header" to "request-header-value"), seen?.headers)
        assertEquals(BinaryData.Bytes(byteArrayOf(1, 2, 3)), result.audio)
        assertEquals(listOf(Warning.Unsupported("speed")), result.warnings)
        assertEquals("res-1", result.response.id)
    }

    @Test
    fun `zero bytes of audio is an error, not a file no player will open`() = runTest {
        val model = ScriptedSpeechModel {
            SpeechResult(audio = BinaryData.Bytes(byteArrayOf()), response = ModalityResponse())
        }

        assertFailsWith<NoContentGeneratedError> {
            generateSpeech(model, SpeechCallOptions(text = "hello"))
        }
    }

    @Test
    fun `an empty base64 audio payload is caught too, not only empty bytes`() = runTest {
        val model = ScriptedSpeechModel {
            SpeechResult(audio = BinaryData.Base64(""), response = ModalityResponse())
        }

        assertFailsWith<NoContentGeneratedError> {
            generateSpeech(model, SpeechCallOptions(text = "hello"))
        }
    }

    @Test
    fun `blank text is refused before a request goes out`() = runTest {
        var calls = 0
        val model = ScriptedSpeechModel {
            calls++
            SpeechResult(audio = BinaryData.Bytes(byteArrayOf(1)), response = ModalityResponse())
        }

        assertFailsWith<InvalidArgumentError> { generateSpeech(model, SpeechCallOptions(text = "   ")) }
        assertEquals(0, calls)
    }

    // -----------------------------------------------------------------------------------------------
    // transcribe
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `transcribe forwards the audio and media type and returns the whole transcript`() = runTest {
        var seen: TranscriptionCallOptions? = null
        val model = ScriptedTranscriptionModel { options ->
            seen = options
            TranscriptionResult(
                text = "Hello, world!",
                segments = listOf(TranscriptionResult.Segment("Hello, world!", 0.0, 1.5)),
                language = "en",
                durationInSeconds = 1.5,
                warnings = listOf(Warning.Unsupported("diarization")),
                response = ModalityResponse(modelId = "transcribe-1", id = "res-1"),
            )
        }

        val result = transcribe(
            model,
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(byteArrayOf(1, 2, 3)),
                mediaType = "audio/wav",
                providerOptions = mapOf("p" to buildJsonObject { put("k", "v") }),
                headers = mapOf("custom-request-header" to "request-header-value"),
            ),
        )

        assertEquals(BinaryData.Bytes(byteArrayOf(1, 2, 3)), seen?.audio)
        assertEquals("audio/wav", seen?.mediaType)
        assertEquals(mapOf("custom-request-header" to "request-header-value"), seen?.headers)
        assertEquals("Hello, world!", result.text)
        assertEquals(1, result.segments.size)
        assertEquals("en", result.language)
        assertEquals(1.5, result.durationInSeconds)
        assertEquals(listOf(Warning.Unsupported("diarization")), result.warnings)
        assertEquals("res-1", result.response.id)
    }

    @Test
    fun `segments with no joined transcript are a legitimate answer, not an empty one`() = runTest {
        val model = ScriptedTranscriptionModel {
            TranscriptionResult(
                text = "",
                segments = listOf(TranscriptionResult.Segment("speaker one", 0.0, 1.0)),
                response = ModalityResponse(),
            )
        }

        // A diarizing vendor may return per-speaker spans and no joined text; failing that call would
        // discard a transcript that is present, just not where the wrapper looked first.
        val result = transcribe(
            model,
            TranscriptionCallOptions(BinaryData.Bytes(byteArrayOf(1)), "audio/wav"),
        )

        assertEquals(1, result.segments.size)
    }
}

// ---------------------------------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------------------------------

private class ScriptedImageModel(
    private val maxPerCall: Int?,
    private val answer: suspend (ImageCallOptions) -> ImageResult,
) : ImageModel {

    override val provider: String = "test-provider"
    override val modelId: String = "image-1"

    val seen: MutableList<ImageCallOptions> = mutableListOf()
    val requested: List<Int> get() = seen.map { it.n }

    override suspend fun maxImagesPerCall(): Int? = maxPerCall

    override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
        seen += options
        return answer(options)
    }
}

private class ScriptedSpeechModel(
    private val answer: suspend (SpeechCallOptions) -> SpeechResult,
) : SpeechModel {

    override val provider: String = "test-provider"
    override val modelId: String = "speech-1"

    override suspend fun doGenerate(options: SpeechCallOptions): SpeechResult = answer(options)
}

private class ScriptedTranscriptionModel(
    private val answer: suspend (TranscriptionCallOptions) -> TranscriptionResult,
) : TranscriptionModel {

    override val provider: String = "test-provider"
    override val modelId: String = "transcribe-1"

    override suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult =
        answer(options)
}
