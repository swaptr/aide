package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The modality middleware contracts, held to the language one's rules: first listed is outermost, a
 * capability override returning null leaves the model's own answer standing, and an undeclared member
 * passes through untouched.
 */
class ModalityMiddlewareTest {

    private class RecordingEmbedder(val calls: MutableList<String>) : EmbeddingModel {
        override val provider: String = "base"
        override val modelId: String = "embed-1"
        override suspend fun maxEmbeddingsPerCall(): Int? = 96
        override suspend fun supportsParallelCalls(): Boolean = false

        override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
            calls += "model:${options.values.joinToString(",")}"
            return EmbeddingResult(embeddings = options.values.map { listOf(1.0) })
        }
    }

    private class EmbedTracer(private val tag: String, private val calls: MutableList<String>) :
        EmbeddingModelMiddleware {
        override suspend fun wrapEmbed(
            params: EmbeddingCallOptions,
            model: EmbeddingModel,
            doEmbed: suspend () -> EmbeddingResult,
        ): EmbeddingResult {
            calls += "$tag:enter"
            return doEmbed().also { calls += "$tag:exit" }
        }
    }

    private class RecordingImager(val calls: MutableList<String>) : ImageModel {
        override val provider: String = "base"
        override val modelId: String = "image-1"
        override suspend fun maxImagesPerCall(): Int? = 4

        override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
            calls += "model:${options.prompt}"
            return ImageResult(
                images = listOf(BinaryData.Base64("aGk=")),
                response = ModalityResponse(modelId = modelId),
            )
        }
    }

    @Test
    fun `embedding middleware runs outermost first`() = runTest {
        val calls = mutableListOf<String>()
        val model = RecordingEmbedder(calls)
            .withMiddleware(EmbedTracer("outer", calls), EmbedTracer("inner", calls))

        model.doEmbed(EmbeddingCallOptions(values = listOf("a")))

        assertEquals(
            listOf("outer:enter", "inner:enter", "model:a", "inner:exit", "outer:exit"),
            calls,
        )
    }

    @Test
    fun `embedding transformParams reaches the model, and overrides apply`() = runTest {
        val calls = mutableListOf<String>()
        val model = RecordingEmbedder(calls).withMiddleware(
            object : EmbeddingModelMiddleware {
                override fun overrideProvider(model: EmbeddingModel): String = "rebranded"
                override suspend fun overrideMaxEmbeddingsPerCall(model: EmbeddingModel): Int = 8
                override suspend fun transformParams(
                    params: EmbeddingCallOptions,
                    model: EmbeddingModel,
                ): EmbeddingCallOptions = params.copy(values = params.values.map { it.uppercase() })
            },
        )

        model.doEmbed(EmbeddingCallOptions(values = listOf("a", "b")))

        assertEquals(listOf("model:A,B"), calls)
        assertEquals("rebranded", model.provider)
        assertEquals("embed-1", model.modelId)
        assertEquals(8, model.maxEmbeddingsPerCall())
    }

    @Test
    fun `a null capability override leaves the model's own answer standing`() = runTest {
        val model = RecordingEmbedder(mutableListOf())
            .withMiddleware(object : EmbeddingModelMiddleware {})

        // The middleware had no opinion, so the ceiling and the concurrency answer are the model's.
        assertEquals(96, model.maxEmbeddingsPerCall())
        assertEquals(false, model.supportsParallelCalls())
    }

    @Test
    fun `image middleware runs outermost first and undeclared members pass through`() = runTest {
        val calls = mutableListOf<String>()
        val tracer = { tag: String ->
            object : ImageModelMiddleware {
                override suspend fun wrapGenerate(
                    params: ImageCallOptions,
                    model: ImageModel,
                    doGenerate: suspend () -> ImageResult,
                ): ImageResult {
                    calls += "$tag:enter"
                    return doGenerate().also { calls += "$tag:exit" }
                }
            }
        }
        val model = RecordingImager(calls).withMiddleware(tracer("outer"), tracer("inner"))

        model.doGenerate(ImageCallOptions(prompt = "a fox"))

        assertEquals(
            listOf("outer:enter", "inner:enter", "model:a fox", "inner:exit", "outer:exit"),
            calls,
        )
        assertEquals("base", model.provider)
        assertEquals(4, model.maxImagesPerCall())
    }

    @Test
    fun `an image middleware can short-circuit without reaching the model`() = runTest {
        val calls = mutableListOf<String>()
        val model = RecordingImager(calls).withMiddleware(
            object : ImageModelMiddleware {
                override suspend fun wrapGenerate(
                    params: ImageCallOptions,
                    model: ImageModel,
                    doGenerate: suspend () -> ImageResult,
                ): ImageResult = ImageResult(
                    images = listOf(BinaryData.Base64("Y2FjaGVk")),
                    response = ModalityResponse(),
                )
            },
        )

        val result = model.doGenerate(ImageCallOptions(prompt = "a fox"))

        assertEquals(emptyList(), calls)
        assertEquals(BinaryData.Base64("Y2FjaGVk"), result.images.single())
    }
}
