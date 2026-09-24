package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the port's one deliberate structural divergence from the reference, plus the retry rule.
 *
 * `ProviderV4` makes `languageModel`/`embeddingModel`/`imageModel` required, so a vendor with no image
 * models must ship one that throws. Here absence is `null`, and only a *wrong id* throws. That
 * distinction is the whole contract, so it is tested rather than merely documented.
 */
class ProviderContractTest {

    private class TextOnlyProvider : Provider {
        override val providerId: String = "textonly"

        override fun languageModel(modelId: String): LanguageModel =
            if (modelId == "known-1") StubLanguageModel(providerId, modelId)
            else throw NoSuchModelError(modelId, NoSuchModelError.ModelType.LanguageModel)
        // imageModel / speechModel / … deliberately not overridden: this vendor has none.
    }

    private class StubLanguageModel(
        override val provider: String,
        override val modelId: String,
    ) : LanguageModel {
        override suspend fun doGenerate(options: CallOptions): GenerateResult =
            throw UnsupportedFunctionalityError("doGenerate")

        override suspend fun doStream(options: CallOptions): StreamResult =
            throw UnsupportedFunctionalityError("doStream")
    }

    @Test
    fun `a modality the provider does not offer is null, not an exception`() {
        val provider = TextOnlyProvider()

        // The reference forces a throwing stub here. A caller can now just omit the affordance.
        assertNull(provider.imageModel("anything"))
        assertNull(provider.speechModel("anything"))
        assertNull(provider.embeddingModel("anything"))
        assertNull(provider.rerankingModel("anything"))
        assertNull(provider.transcriptionModel("anything"))
        // Video and speech translation have no accessor on `ProviderV4` at all — they are experimental
        // there — so a caller asking either question of a text-only vendor is the case most likely to
        // have been left unimplemented, and the one where a thrown stub would be least expected.
        assertNull(provider.videoModel("anything"))
        assertNull(provider.speechTranslationModel("anything"))
    }

    @Test
    fun `an unknown id within an offered modality throws`() {
        val provider = TextOnlyProvider()

        // Distinct from the case above: this vendor DOES do language models, so a bad id is a mistake.
        val error = assertFailsWith<NoSuchModelError> { provider.languageModel("typo-4o") }
        assertEquals("typo-4o", error.modelId)
        assertEquals(NoSuchModelError.ModelType.LanguageModel, error.modelType)

        // Non-null return type here: this override narrows it, which is exactly what a real provider does.
        assertEquals("known-1", provider.languageModel("known-1").modelId)
    }

    @Test
    fun `requireLanguageModel turns an absent modality into a loud failure`() {
        val silent = object : Provider {
            override val providerId: String = "images-only"
        }

        assertFailsWith<NoSuchModelError> { silent.requireLanguageModel("anything") }
    }

    @Test
    fun `registry reports the providers it knows when one is missed`() {
        val registry = ProviderRegistry(listOf(TextOnlyProvider()))

        assertEquals(setOf("textonly"), registry.providerIds)
        assertEquals("known-1", registry.languageModel("textonly", "known-1").modelId)

        val error = assertFailsWith<NoSuchProviderError> { registry.provider("nope", "some-model") }
        assertEquals("nope", error.providerId)
        // Listing the alternatives is what turns a typo into a one-look fix.
        assertTrue(error.message!!.contains("textonly"), "expected available providers in: ${error.message}")
        // A caller catching "model not found" must catch this too: `nope:some-model` resolved no model,
        // and which half of the id was wrong is not something that caller has to care about. The `is`
        // check would be statically true, so the assertion that carries information is the catch itself.
        assertFailsWith<NoSuchModelError> { registry.provider("nope", "some-model") }
    }

    @Test
    fun `retryable status codes follow the reference rule`() {
        // 408 timeout, 409 conflict, 429 rate limit, and every 5xx.
        listOf(408, 409, 429, 500, 503, 599).forEach {
            assertTrue(APICallError.defaultIsRetryable(it), "$it should be retryable")
        }
        listOf(400, 401, 403, 404, 422).forEach {
            assertFalse(APICallError.defaultIsRetryable(it), "$it should not be retryable")
        }
        // The DEFAULT for a missing status is false, and stays false: this rule reads a status code, and
        // a rule with no input cannot conclude anything. A transport failure is retryable because the
        // transport says so explicitly at the call site, not because this function guessed.
        assertFalse(APICallError.defaultIsRetryable(null))
    }

    @Test
    fun `a provider may override retryability it knows better than the default`() {
        // Some vendors return 400 for a transient overload; the default rule would give up on it.
        val error = APICallError(
            message = "overloaded",
            url = "https://example.invalid/v1/messages",
            statusCode = 400,
            isRetryable = true,
        )

        assertTrue(error.isRetryable)
        assertEquals("AI_APICallError", error.errorName)
    }

    @Test
    fun `error message never renders as the string null`() {
        assertEquals("unknown error", getErrorMessage(null))
        assertEquals("boom", getErrorMessage(IllegalStateException("boom")))
        // A throwable carrying no message falls back to its type rather than an empty string.
        assertTrue(getErrorMessage(IllegalStateException()).isNotEmpty())
    }

    @Test
    fun `too many embedding values names the ceiling and the overage`() {
        val error = TooManyEmbeddingValuesForCallError(
            provider = "acme",
            modelId = "embed-1",
            maxEmbeddingsPerCall = 2,
            values = listOf("a", "b", "c"),
        )

        assertTrue(error.message!!.contains("at most 2"))
        assertTrue(error.message!!.contains("3 values"))
    }
}
