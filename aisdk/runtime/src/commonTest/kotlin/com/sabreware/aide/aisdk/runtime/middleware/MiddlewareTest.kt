package com.sabreware.aide.aisdk.runtime.middleware

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.GenerateResult
import com.sabreware.aide.aisdk.LanguageModel
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.StreamResult
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.withMiddleware
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * What each middleware does to the request the model actually receives, or to the result it returns.
 *
 * A middleware whose test only asserts that a call still succeeds has tested the pass-through defaults
 * on the interface, not the middleware. Every case here reads the [CapturingModel]'s recorded options
 * or the transformed content.
 */
class MiddlewareTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hello"))))

    // -----------------------------------------------------------------------------------------------
    // defaultSettings / defaultInstructions
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a default fills a setting the call left unset`() = runTest {
        val model = CapturingModel()

        model.withMiddleware(defaultSettings(temperature = 0.2, maxOutputTokens = 512))
            .doGenerate(CallOptions(prompt = prompt))

        assertEquals(0.2, model.received?.temperature)
        assertEquals(512, model.received?.maxOutputTokens)
    }

    @Test
    fun `the call wins over the default`() = runTest {
        val model = CapturingModel()

        model.withMiddleware(defaultSettings(temperature = 0.2))
            .doGenerate(CallOptions(prompt = prompt, temperature = 0.9))

        // The other direction would make a per-call setting silently do nothing, which reads as the
        // provider ignoring it.
        assertEquals(0.9, model.received?.temperature)
    }

    @Test
    fun `provider options merge per namespace rather than replacing each other`() = runTest {
        val model = CapturingModel()
        val defaults = mapOf("anthropic" to buildJsonObject { put("betas", JsonPrimitive("x")) })
        val call = mapOf("anthropic" to buildJsonObject { put("thinking", JsonPrimitive("on")) })

        model.withMiddleware(defaultSettings(providerOptions = defaults))
            .doGenerate(CallOptions(prompt = prompt, providerOptions = call))

        val merged = model.received?.providerOptions?.get("anthropic")
        assertEquals(JsonPrimitive("x"), merged?.get("betas"))
        assertEquals(JsonPrimitive("on"), merged?.get("thinking"))
    }

    @Test
    fun `default instructions become the leading system turn`() = runTest {
        val model = CapturingModel()

        model.withMiddleware(defaultInstructions("Be terse."))
            .doGenerate(CallOptions(prompt = prompt))

        assertEquals(ModelMessage.System("Be terse."), model.received?.prompt?.first())
    }

    @Test
    fun `a call that brought its own instructions is left alone`() = runTest {
        val model = CapturingModel()
        val own = listOf(ModelMessage.System("Be exhaustive.")) + prompt

        model.withMiddleware(defaultInstructions("Be terse."))
            .doGenerate(CallOptions(prompt = own))

        assertEquals(own, model.received?.prompt)
    }

    // -----------------------------------------------------------------------------------------------
    // extractReasoning
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `tagged reasoning becomes a reasoning part, not text`() = runTest {
        val model = CapturingModel(
            content = listOf(Content.Text("<think>weighing it up</think>The answer is 4.")),
        )

        val result = model.withMiddleware(extractReasoning()).doGenerate(CallOptions(prompt = prompt))

        assertEquals("weighing it up", (result.content[0] as Content.Reasoning).text)
        assertEquals("The answer is 4.", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `a tag split across two chunks is still a tag`() = runTest {
        val model = CapturingModel(
            stream = listOf(
                StreamPart.TextStart("0"),
                // The split is the case that matters: a splitter reading whole deltas emits "<thi"
                // to the user and then loses the switch entirely.
                StreamPart.TextDelta("0", "<thi"),
                StreamPart.TextDelta("0", "nk>secretly"),
                StreamPart.TextDelta("0", " unsure</think>Four."),
                StreamPart.TextEnd("0"),
            ),
        )

        val parts = model.withMiddleware(extractReasoning())
            .doStream(CallOptions(prompt = prompt)).stream.toList()

        assertEquals("secretly unsure", parts.reasoningText())
        assertEquals("Four.", parts.text())
        // The text block opens only once text actually arrives, so it never precedes the reasoning.
        assertTrue(parts.indexOfFirst { it is StreamPart.ReasoningStart } < parts.indexOfFirst { it is StreamPart.TextStart })
    }

    @Test
    fun `text that merely looks like the start of a tag is not eaten`() = runTest {
        val model = CapturingModel(
            stream = listOf(
                StreamPart.TextStart("0"),
                StreamPart.TextDelta("0", "3 < 4 and 5 > 2"),
                StreamPart.TextEnd("0"),
            ),
        )

        val parts = model.withMiddleware(extractReasoning())
            .doStream(CallOptions(prompt = prompt)).stream.toList()

        assertEquals("3 < 4 and 5 > 2", parts.text())
    }

    @Test
    fun `a model whose opening tag lives in its prompt template starts in reasoning`() = runTest {
        val model = CapturingModel(content = listOf(Content.Text("thinking aloud</think>Done.")))

        val result = model.withMiddleware(extractReasoning(startWithReasoning = true))
            .doGenerate(CallOptions(prompt = prompt))

        assertEquals("thinking aloud", (result.content[0] as Content.Reasoning).text)
        assertEquals("Done.", (result.content[1] as Content.Text).text)
    }

    // -----------------------------------------------------------------------------------------------
    // extractJson
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a fenced, narrated answer is reduced to its JSON`() = runTest {
        val model = CapturingModel(
            content = listOf(Content.Text("Sure!\n```json\n{\"a\": 1}\n```\nHope that helps.")),
        )

        val result = model.withMiddleware(extractJson()).doGenerate(CallOptions(prompt = prompt))

        assertEquals("""{"a":1}""", (result.content.single() as Content.Text).text)
    }

    @Test
    fun `text with no JSON in it survives`() = runTest {
        val model = CapturingModel(content = listOf(Content.Text("I could not answer that.")))

        val result = model.withMiddleware(extractJson()).doGenerate(CallOptions(prompt = prompt))

        assertEquals("I could not answer that.", (result.content.single() as Content.Text).text)
    }

    @Test
    fun `a streamed answer is unwrapped once the block ends`() = runTest {
        val model = CapturingModel(
            stream = listOf(
                StreamPart.TextStart("0"),
                StreamPart.TextDelta("0", "```json\n{\"a\":"),
                StreamPart.TextDelta("0", " 1}\n```"),
                StreamPart.TextEnd("0"),
            ),
        )

        val parts = model.withMiddleware(extractJson())
            .doStream(CallOptions(prompt = prompt)).stream.toList()

        assertEquals("""{"a":1}""", parts.text())
    }

    // -----------------------------------------------------------------------------------------------
    // simulateStreaming
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a model with no stream endpoint is streamed from its generate result`() = runTest {
        val signature = mapOf("anthropic" to buildJsonObject { put("signature", JsonPrimitive("sig-1")) })
        val model = CapturingModel(
            content = listOf(
                Content.Reasoning("weighing it up", providerMetadata = signature),
                Content.Text("Four."),
            ),
            stream = null,
        )

        val parts = model.withMiddleware(simulateStreaming())
            .doStream(CallOptions(prompt = prompt)).stream.toList()

        assertIs<StreamPart.StreamStart>(parts.first())
        assertEquals("Four.", parts.text())
        assertEquals("weighing it up", parts.reasoningText())
        // The signature rides on the END part, which is where the assembler reads it. Losing it makes
        // the next round's replayed thinking block one Anthropic rejects.
        assertEquals(signature, parts.filterIsInstance<StreamPart.ReasoningEnd>().single().providerMetadata)
        assertIs<StreamPart.Finish>(parts.last())
    }
}

private fun List<StreamPart>.text(): String =
    filterIsInstance<StreamPart.TextDelta>().joinToString("") { it.delta }

private fun List<StreamPart>.reasoningText(): String =
    filterIsInstance<StreamPart.ReasoningDelta>().joinToString("") { it.delta }

/**
 * A model that records the options it was handed, so a middleware's effect on the REQUEST is
 * observable — the half a result assertion cannot see.
 */
private class CapturingModel(
    private val content: List<Content> = listOf(Content.Text("ok")),
    private val stream: List<StreamPart>? = null,
    private val providerMetadata: ProviderMetadata? = null,
) : LanguageModel {

    override val provider: String = "test"
    override val modelId: String = "capture-1"

    var received: CallOptions? = null
        private set

    override suspend fun doGenerate(options: CallOptions): GenerateResult {
        received = options
        return GenerateResult(
            content = content,
            finishReason = FinishReason(FinishReason.Unified.Stop),
            usage = Usage(),
            providerMetadata = providerMetadata,
        )
    }

    override suspend fun doStream(options: CallOptions): StreamResult {
        received = options
        return StreamResult(
            stream = flowOf(*(stream ?: error("This model has no stream endpoint.")).toTypedArray()),
        )
    }
}
