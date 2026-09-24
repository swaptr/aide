package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Every modality result can carry a vendor payload, a warning, the request and the response.
 *
 * That quartet is the reason this port exists: a neutral layer that cannot carry what it does not
 * understand loses it, and the loss is silent — a field the type does not have is a field no compiler
 * and no test will ever mention. So the guard cannot be a round trip or a call against a fake vendor;
 * it has to be a construction of each result with all four populated, which stops compiling the moment
 * one of them is dropped from a signature.
 *
 * The values are deliberately unrecognisable. `providerMetadata` is filed under a provider id this
 * module has never heard of, because the property under test is that no part of the specification has
 * to recognise a payload in order to keep it.
 */
class ModalityContractTest {

    private val metadata: ProviderMetadata =
        mapOf("vendor-that-does-not-exist" to buildJsonObject { put("signature", "opaque") })

    private val warnings = listOf(Warning.Unsupported(feature = "topK", details = "ignored"))

    private val request = RequestInfo(body = """{"model":"x"}""")

    private val response = ModalityResponse(
        modelId = "x",
        timestamp = 1_700_000_000_000,
        id = "resp-1",
        headers = mapOf("x-request-id" to "abc"),
        body = "{}",
    )

    @Test
    fun `an embedding result carries the quartet`() {
        val result = EmbeddingResult(
            embeddings = listOf(listOf(0.1, 0.2)),
            usage = 7,
            warnings = warnings,
            providerMetadata = metadata,
            response = response,
            request = request,
        )

        assertEquals(metadata, result.providerMetadata)
        assertEquals(warnings, result.warnings)
        assertEquals(request, result.request)
        assertEquals(response, result.response)
    }

    @Test
    fun `an image result carries the quartet, and usage keeps the vendor's own total`() {
        val result = ImageResult(
            images = listOf(BinaryData.Base64("aGk=")),
            warnings = warnings,
            // The reference reports `totalTokens` separately rather than as a sum, and so do we: a
            // vendor that prices image tokens on their own bills a total the two halves do not add to.
            usage = ImageUsage(inputTokens = 3, outputTokens = 4, totalTokens = 12),
            providerMetadata = metadata,
            response = response,
            request = request,
        )

        assertEquals(12, result.usage?.totalTokens)
        assertEquals(metadata, result.providerMetadata)
        assertEquals(warnings, result.warnings)
        assertEquals(request, result.request)
    }

    @Test
    fun `an input image carries per-file provider options`() {
        // Where a vendor takes per-image settings — a strength, a role — the only place to put them is
        // on the file. Hanging them off the call's options makes two files in one edit indistinguishable.
        val file = ImageFile.Data(
            data = BinaryData.Bytes(byteArrayOf(1, 2, 3)),
            mediaType = "image/png",
            providerOptions = mapOf("acme" to buildJsonObject { put("strength", 0.8) }),
        )

        assertEquals(0.8, file.providerOptions?.get("acme")?.get("strength")?.toString()?.toDouble())
    }

    @Test
    fun `a speech result carries the quartet`() {
        val result = SpeechResult(
            audio = BinaryData.Bytes(byteArrayOf(0)),
            warnings = warnings,
            request = request,
            providerMetadata = metadata,
            response = response,
        )

        assertEquals(metadata, result.providerMetadata)
        assertEquals(request, result.request)
    }

    @Test
    fun `a transcription result carries the quartet`() {
        val result = TranscriptionResult(
            text = "hello",
            segments = listOf(TranscriptionResult.Segment("hello", 0.0, 1.0)),
            language = "en",
            durationInSeconds = 1.0,
            warnings = warnings,
            request = request,
            providerMetadata = metadata,
            response = response,
        )

        assertEquals(metadata, result.providerMetadata)
        assertEquals(request, result.request)
    }

    @Test
    fun `a reranking result carries the quartet`() {
        val result = RerankingResult(
            ranking = listOf(RerankingResult.Rank(index = 2, relevanceScore = 0.9)),
            warnings = warnings,
            providerMetadata = metadata,
            response = response,
            request = request,
        )

        assertEquals(2, result.ranking.single().index)
        assertEquals(metadata, result.providerMetadata)
        assertEquals(request, result.request)
    }

    @Test
    fun `every video operation result carries the quartet`() {
        val start = VideoStartResult(
            operation = buildJsonObject { put("taskId", "t-1") },
            warnings = warnings,
            providerMetadata = metadata,
            response = response,
            request = request,
        )
        assertEquals(request, start.request)

        // The refusal case is the one the reference leaves without warnings, and the one that needs them
        // most: "your resolution was clamped" is why the job came back rejected.
        val failed: VideoStatusResult = VideoStatusResult.Failed(
            error = "content policy",
            warnings = warnings,
            providerMetadata = metadata,
            response = response,
            request = request,
        )
        assertEquals(warnings, failed.warnings)
        assertEquals(metadata, failed.providerMetadata)
        assertEquals(request, failed.request)

        val pending: VideoStatusResult = VideoStatusResult.Pending()
        assertEquals(emptyList(), pending.warnings)
    }

    @Test
    fun `an operation reference is opaque JSON the caller can persist`() {
        // A typed handle would force this specification to know what a task id looks like on every
        // vendor. A URL, an object and a bare null all have to survive being handed back unread.
        listOf(
            JsonNull,
            buildJsonObject { put("url", "https://example.invalid/predictions/1") },
        ).forEach { operation ->
            assertEquals(operation, VideoStartResult(operation = operation).operation)
        }
    }

    @Test
    fun `speech translation usage keeps audio and text tokens apart`() {
        // Folded into one input/output pair these cannot be priced: the split is not recoverable once
        // the numbers have been added, and the two halves bill at different rates on every vendor.
        val usage = SpeechTranslationUsage(
            inputAudioSeconds = 12.5,
            inputAudioTokens = 400,
            outputAudioTokens = 512,
            inputTextTokens = 30,
            outputTextTokens = 44,
        )

        assertEquals(400, usage.inputAudioTokens)
        assertEquals(30, usage.inputTextTokens)
    }

    @Test
    fun `a live stream's response identity carries the headers it arrives with`() {
        // A live session is already open by the time the provider says what it is; there is no other
        // event to read the response headers from.
        val part = TranscriptionStreamPart.ResponseMetadataPart(
            metadata = ResponseMetadata(id = "r-1", timestamp = 1_700_000_000_000, modelId = "x"),
            headers = mapOf("x-request-id" to "abc"),
            body = "{}",
        )

        assertEquals("abc", part.headers?.get("x-request-id"))
        assertEquals("r-1", part.metadata.id)
    }

    @Test
    fun `a video call may ask for an audio track`() {
        // Null is not false: a model that always produces audio must not be told to turn it off because
        // the caller said nothing.
        assertEquals(null, VideoCallOptions(prompt = "a wave").generateAudio)
        assertEquals(true, VideoCallOptions(prompt = "a wave", generateAudio = true).generateAudio)
    }
}
