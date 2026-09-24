package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.assembleGenerateResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Conformance against the REFERENCE IMPLEMENTATION'S OWN recorded wire.
 *
 * Every other fixture in this module was written by us from documentation. That makes the suite prove
 * internal consistency — the code does what we believe the wire is — without proving the belief. If our
 * idea of a `signature_delta` frame were wrong, every one of those tests would still pass and production
 * would still fail.
 *
 * The chunks below are copied verbatim from `packages/anthropic/src/anthropic-language-model.test.ts` in
 * the vendored `third_party/vercel-ai`, at the pinned commit. They are what a separate team, testing a
 * separate implementation against the live API, recorded as the real thing. Agreeing with them is a
 * genuinely independent check.
 *
 * It is not a substitute for a live call — only Anthropic's server can verify that a replayed signature
 * is cryptographically valid — but it is the strongest verification available without a key.
 */
class AnthropicConformanceTest {

    private fun model(vararg chunks: String) = AnthropicLanguageModel(
        modelId = "claude-3-haiku-20240307",
        http = ProviderHttp(
            HttpClient(
                MockEngine {
                    respond(
                        content = chunks.joinToString(""),
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            ),
        ),
    )

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi")))))

    // --- verbatim from the reference's own test suite -------------------------------------------

    private val messageStart =
        """data: {"type":"message_start","message":{"id":"msg_01KfpJoAEabmH2iHRRFjQMAG","type":"message",""" +
            """"role":"assistant","content":[],"model":"claude-3-haiku-20240307","stop_reason":null,""" +
            """"stop_sequence":null,"usage":{"input_tokens":17,"output_tokens":1}}}""" + "\n\n"

    private val thinkingStart =
        """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""" +
            "\n\n"

    private val thinkingDelta1 =
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"I am"}}""" +
            "\n\n"

    private val thinkingDelta2 =
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thinking..."}}""" +
            "\n\n"

    private val signatureDelta0 =
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"1234567890"}}""" +
            "\n\n"

    private val blockStop0 = """data: {"type":"content_block_stop","index":0}""" + "\n\n"

    private val textStart1 =
        """data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""" + "\n\n"

    private val textDelta1 =
        """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello, World!"}}""" +
            "\n\n"

    private val textStart0 =
        """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""" + "\n\n"

    private val textDelta0 =
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, World!"}}""" +
            "\n\n"

    private val messageDelta =
        """data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},""" +
            """"usage":{"output_tokens":227}}""" + "\n\n"

    private val messageStop = """data: {"type":"message_stop"}""" + "\n\n"

    // --- the checks ------------------------------------------------------------------------------

    @Test
    fun `their recorded thinking stream maps to reasoning carrying the signature`() = runTest {
        val result = assembleGenerateResult(
            model(
                messageStart, thinkingStart, thinkingDelta1, thinkingDelta2, signatureDelta0, blockStop0,
                textStart1, textDelta1, messageDelta, messageStop,
            ).doStream(call).stream,
        )

        // Their own recording, our parser: the two deltas concatenate and the signature survives.
        val reasoning = result.content[0] as Content.Reasoning
        assertEquals("I amthinking...", reasoning.text)
        assertEquals(
            "1234567890",
            reasoning.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)
                ?.get(ANTHROPIC_SIGNATURE_KEY)?.toString()?.trim('"'),
        )
        assertEquals("Hello, World!", (result.content[1] as Content.Text).text)
    }

    @Test
    fun `a signature on a TEXT block is ignored, matching their documented behaviour`() = runTest {
        // Their suite has a test named exactly "should ignore signatures on text deltas". A signature
        // belongs to a thinking block; attaching one to text would replay a block Anthropic never signed.
        val parts = model(
            messageStart, textStart0, textDelta0, signatureDelta0, blockStop0, messageDelta, messageStop,
        ).doStream(call).stream.toList()

        assertTrue(parts.filterIsInstance<StreamPart.ReasoningEnd>().isEmpty())
        val textEnd = parts.filterIsInstance<StreamPart.TextEnd>().single()
        assertNull(textEnd.providerMetadata)

        val result = assembleGenerateResult(
            model(
                messageStart, textStart0, textDelta0, signatureDelta0, blockStop0, messageDelta, messageStop,
            ).doStream(call).stream,
        )
        val text = result.content.single() as Content.Text
        assertEquals("Hello, World!", text.text)
        assertNull(text.providerMetadata)
    }

    @Test
    fun `their message_start and message_delta usage fields land where we read them`() = runTest {
        val parts = model(
            messageStart, textStart0, textDelta0, blockStop0, messageDelta, messageStop,
        ).doStream(call).stream.toList()

        // input_tokens on message_start, output_tokens on message_delta — different events, and reading
        // either from the wrong one yields a plausible-looking wrong number.
        val finish = parts.filterIsInstance<StreamPart.Finish>().single()
        assertEquals(17, finish.usage.inputTokens.total)
        assertEquals(227, finish.usage.outputTokens.total)
        assertEquals(FinishReason.Unified.Stop, finish.finishReason.unified)
        assertEquals("end_turn", finish.finishReason.raw)

        val metadata = parts.filterIsInstance<StreamPart.ResponseMetadataPart>().single().metadata
        assertEquals("msg_01KfpJoAEabmH2iHRRFjQMAG", metadata.id)
        assertEquals("claude-3-haiku-20240307", metadata.modelId)
    }

    @Test
    fun `block indices from their recording separate reasoning from text`() = runTest {
        // Index 0 is the thinking block and index 1 the text; keying both under one map would let the
        // second overwrite the first.
        val parts = model(
            messageStart, thinkingStart, thinkingDelta1, signatureDelta0, blockStop0,
            textStart1, textDelta1, messageDelta, messageStop,
        ).doStream(call).stream.toList()

        assertEquals(1, parts.filterIsInstance<StreamPart.ReasoningStart>().size)
        assertEquals(1, parts.filterIsInstance<StreamPart.TextStart>().size)
        assertEquals("0", parts.filterIsInstance<StreamPart.ReasoningEnd>().single().id)
        assertEquals("1", parts.filterIsInstance<StreamPart.TextStart>().single().id)
    }
}
