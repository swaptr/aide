package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.EmbeddingModel
import com.sabreware.aide.aisdk.EmbeddingResult
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.ImageModel
import com.sabreware.aide.aisdk.ImageModelMiddleware
import com.sabreware.aide.aisdk.ImageResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.LanguageModelMiddleware
import com.sabreware.aide.aisdk.ModalityResponse
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [wrapProvider] applies policy at the provider, so a model constructed later still gets it; and
 * [defaultEmbeddingSettings] keeps the one composable precedence — the call always wins.
 */
class WrapProviderTest {

    private val calls = mutableListOf<String>()

    private inner class FakeProvider : Provider {
        override val providerId: String = "fake"

        override fun languageModel(modelId: String): LanguageModel = object : LanguageModel {
            override val provider: String = "fake"
            override val modelId: String = modelId
            override suspend fun doGenerate(options: CallOptions): GenerateResult {
                calls += "generate"
                return GenerateResult(
                    content = listOf(Content.Text("hi")),
                    finishReason = FinishReason(FinishReason.Unified.Stop),
                    usage = Usage(),
                )
            }

            override suspend fun doStream(options: CallOptions): StreamResult =
                StreamResult(emptyFlow())
        }

        override fun embeddingModel(modelId: String): EmbeddingModel = object : EmbeddingModel {
            override val provider: String = "fake"
            override val modelId: String = modelId
            override suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult {
                calls += "embed:headers=${options.headers}:options=${options.providerOptions}"
                return EmbeddingResult(embeddings = options.values.map { listOf(0.0) })
            }
        }

        override fun imageModel(modelId: String): ImageModel = object : ImageModel {
            override val provider: String = "fake"
            override val modelId: String = modelId
            override suspend fun doGenerate(options: ImageCallOptions): ImageResult {
                calls += "image:${options.prompt}"
                return ImageResult(images = emptyList(), response = ModalityResponse())
            }
        }
    }

    @Test
    fun `every model the provider hands out is wrapped`() = runTest {
        val wrapped = wrapProvider(
            provider = FakeProvider(),
            languageModelMiddleware = listOf(
                object : LanguageModelMiddleware {
                    override suspend fun wrapGenerate(
                        params: CallOptions,
                        model: LanguageModel,
                        doGenerate: suspend () -> GenerateResult,
                    ): GenerateResult {
                        calls += "lm-middleware"
                        return doGenerate()
                    }
                },
            ),
            imageModelMiddleware = listOf(
                object : ImageModelMiddleware {
                    override suspend fun transformParams(
                        params: ImageCallOptions,
                        model: ImageModel,
                    ): ImageCallOptions = params.copy(prompt = "rewritten")
                },
            ),
        )

        wrapped.languageModel("m")!!.doGenerate(CallOptions(prompt = listOf(ModelMessage.System("s"))))
        wrapped.imageModel("i")!!.doGenerate(ImageCallOptions(prompt = "original"))

        assertEquals(listOf("lm-middleware", "generate", "image:rewritten"), calls)
        assertEquals("fake", wrapped.providerId)
    }

    @Test
    fun `an absent modality stays absent through the wrapper`() {
        val wrapped = wrapProvider(
            provider = object : Provider {
                override val providerId: String = "narrow"
            },
            languageModelMiddleware = emptyList(),
        )

        assertNull(wrapped.languageModel("m"))
        assertNull(wrapped.speechModel("s"))
        assertNull(wrapped.videoModel("v"))
    }

    @Test
    fun `default embedding settings fill gaps and the call wins collisions, deeply`() = runTest {
        val model = wrapProvider(
            provider = FakeProvider(),
            embeddingModelMiddleware = listOf(
                defaultEmbeddingSettings(
                    headers = mapOf("x-team" to "default", "x-region" to "eu"),
                    providerOptions = mapOf(
                        "voyage" to buildJsonObject {
                            put("inputType", "document")
                            put("truncation", true)
                        },
                    ),
                ),
            ),
        ).embeddingModel("e")!!

        val result = model.doEmbed(
            EmbeddingCallOptions(
                values = listOf("v"),
                headers = mapOf("x-team" to "call"),
                providerOptions = mapOf("voyage" to buildJsonObject { put("outputDimension", 256) }),
            ),
        )

        assertEquals(1, result.embeddings.size)
        val recorded = calls.single()
        // The call's header spelling wins; the default it did not touch survives.
        assertEquals(true, recorded.contains("x-team=call"))
        assertEquals(true, recorded.contains("x-region=eu"))
        // Namespaces merged deeply: the default's two keys and the call's one coexist.
        assertEquals(true, recorded.contains("inputType"))
        assertEquals(true, recorded.contains("outputDimension"))
    }

    @Test
    fun `default embedding settings leave an unconfigured call untouched`() = runTest {
        val model = wrapProvider(
            provider = FakeProvider(),
            embeddingModelMiddleware = listOf(defaultEmbeddingSettings()),
        ).embeddingModel("e")!!

        model.doEmbed(EmbeddingCallOptions(values = listOf("v")))

        assertEquals("embed:headers=null:options=null", calls.single())
    }
}
