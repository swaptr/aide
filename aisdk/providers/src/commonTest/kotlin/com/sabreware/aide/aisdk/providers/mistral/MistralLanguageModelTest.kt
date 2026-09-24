package com.sabreware.aide.aisdk.providers.mistral

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Mistral's chat departures, pinned against docs.mistral.ai.
 *
 * All four fail quietly: an ignored image, a lost train of thought, an unrecognized seed, and a
 * truncation that reads as a clean stop.
 */
class MistralLanguageModelTest {

    private fun provider(server: TestServer) = MistralProvider(
        client = HttpClient(server.engine()),
        apiKey = "k",
    )

    private fun sse(vararg objects: String) = TestServer(
        TestServer.sse(objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"),
    )

    private fun done(finish: String = "stop", usage: String? = null) =
        """{"id":"c","choices":[{"delta":{},"finish_reason":"$finish"}]""" +
            (usage?.let { ""","usage":$it""" } ?: "") + "}"

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))
    private val call = CallOptions(prompt = prompt)

    @Test
    fun `an image part goes as a bare url, not OpenAI's object`() = runTest {
        val server = sse(done())
        val withImage = listOf(
            ModelMessage.User(
                listOf(
                    UserPart.Text("what is this?"),
                    UserPart.File(FileData.Url("https://example.test/cat.png"), "image/png"),
                ),
            ),
        )

        provider(server).languageModel("pixtral-large-latest")
            .doStream(CallOptions(prompt = withImage)).stream.toList()

        val parts = server.request().bodyJson()["messages"]!!.jsonArray.single()
            .jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        val image = parts.single { it["type"].string() == "image_url" }
        // The object form is accepted and then ignored — the model simply cannot see the image.
        assertEquals("https://example.test/cat.png", image["image_url"].string())
    }

    @Test
    fun `a PDF goes as document_url, which the shared encoder already knows`() = runTest {
        val server = sse(done())
        val withPdf = listOf(
            ModelMessage.User(
                listOf(UserPart.File(FileData.Url("https://example.test/p.pdf"), "application/pdf")),
            ),
        )

        provider(server).languageModel("mistral-large-latest")
            .doStream(CallOptions(prompt = withPdf)).stream.toList()

        val part = server.request().bodyJson()["messages"]!!.jsonArray.single()
            .jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("document_url", part["type"].string())
        assertEquals("https://example.test/p.pdf", part["document_url"].string())
    }

    @Test
    fun `a trailing assistant message is marked as a prefill`() = runTest {
        val server = sse(done())
        val prefilled = listOf(
            ModelMessage.User(listOf(UserPart.Text("write a haiku"))),
            ModelMessage.Assistant(listOf(AssistantPart.Text("Silent"))),
        )

        provider(server).languageModel("mistral-large-latest")
            .doStream(CallOptions(prompt = prefilled)).stream.toList()

        val messages = server.request().bodyJson()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(true, messages.last()["prefix"].string()?.toBoolean())
        // Only the trailing one: an earlier assistant turn is history, not a prefill.
        assertNull(messages.first()["prefix"])
    }

    @Test
    fun `an assistant turn in the middle is not a prefill`() = runTest {
        val server = sse(done())
        val history = listOf(
            ModelMessage.User(listOf(UserPart.Text("hi"))),
            ModelMessage.Assistant(listOf(AssistantPart.Text("hello"))),
            ModelMessage.User(listOf(UserPart.Text("again"))),
        )

        provider(server).languageModel("mistral-large-latest")
            .doStream(CallOptions(prompt = history)).stream.toList()

        assertTrue(
            server.request().bodyJson()["messages"]!!.jsonArray
                .none { it.jsonObject["prefix"] != null },
        )
    }

    @Test
    fun `assistant reasoning replays as a thinking part, which Magistral requires`() = runTest {
        val server = sse(done())
        val replayed = listOf(
            ModelMessage.User(listOf(UserPart.Text("solve it"))),
            ModelMessage.Assistant(
                listOf(
                    AssistantPart.Reasoning("first I check the units"),
                    AssistantPart.Text("42"),
                ),
            ),
        )

        provider(server).languageModel("magistral-medium-latest")
            .doStream(CallOptions(prompt = replayed)).stream.toList()

        val assistant = server.request().bodyJson()["messages"]!!.jsonArray.last().jsonObject
        // Mistral's reasoning guide: "always replay the full assistant message (including ThinkChunk)".
        // On the `reasoning_content` channel it is simply not read, and the model re-derives from zero.
        assertNull(assistant["reasoning_content"])
        val parts = assistant["content"]!!.jsonArray.map { it.jsonObject }
        val thinking = parts.first()
        assertEquals("thinking", thinking["type"].string())
        assertEquals(
            "first I check the units",
            thinking["thinking"]!!.jsonArray.single().jsonObject["text"].string(),
        )
        assertEquals(true, thinking["closed"].string()?.toBoolean())
        assertEquals("42", parts.last()["text"].string())
    }

    @Test
    fun `the seed is random_seed, the only spelling Mistral reads`() = runTest {
        val server = sse(done())

        provider(server).languageModel("mistral-large-latest")
            .doStream(call.copy(seed = 7)).stream.toList()

        val body = server.request().bodyJson()
        assertEquals(7, body["random_seed"].string()?.toInt())
        // Sent as `seed` it is an unrecognized field: sampling is not reproducible and nothing says so.
        assertNull(body["seed"])
    }

    @Test
    fun `caller options translate to Mistral's own field names`() = runTest {
        val server = sse(done())

        provider(server).languageModel("mistral-medium-latest").doStream(
            call.copy(
                providerOptions = mapOf(
                    MISTRAL_PROVIDER_ID to buildJsonObject {
                        put("safePrompt", true)
                        put("documentImageLimit", 4)
                        put("documentPageLimit", 12)
                        put("promptCacheKey", "shared-prefix")
                        put("reasoningEffort", "high")
                    },
                ),
            ),
        ).stream.toList()

        val body = server.request().bodyJson()
        assertEquals(true, body["safe_prompt"].string()?.toBoolean())
        assertEquals(4, body["document_image_limit"].string()?.toInt())
        assertEquals(12, body["document_page_limit"].string()?.toInt())
        assertEquals("shared-prefix", body["prompt_cache_key"].string())
        assertEquals("high", body["reasoning_effort"].string())
        assertNull(body["safePrompt"])
    }

    @Test
    fun `an effort Mistral does not offer warns rather than going out`() = runTest {
        val server = sse(done())

        val parts = provider(server).languageModel("mistral-medium-latest").doStream(
            call.copy(
                providerOptions = mapOf(
                    MISTRAL_PROVIDER_ID to buildJsonObject { put("reasoningEffort", "medium") },
                ),
            ),
        ).stream.toList()

        assertNull(server.request().bodyJson()["reasoning_effort"])
        assertTrue(
            parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.isNotEmpty(),
        )
    }

    @Test
    fun `model_length is a length stop, not an unremarkable one`() = runTest {
        val server = sse(done(finish = "model_length"))

        val finish = provider(server).languageModel("mistral-large-latest")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason.Unified.Length, finish.finishReason.unified)
        // The vendor's own word is kept, as everywhere.
        assertEquals("model_length", finish.finishReason.raw)
    }

    @Test
    fun `the documented cache field is read, and an absent one is not reported as zero`() = runTest {
        val server = sse(
            done(usage = """{"prompt_tokens":1013,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":1008}}"""),
        )

        val finish = provider(server).languageModel("mistral-large-latest")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        // Mistral bills prompt_tokens − cached_tokens at full rate; the rest at a tenth.
        assertEquals(1008, finish.usage.inputTokens.cacheRead)
        assertEquals(5, finish.usage.inputTokens.noCache)
        // No reasoning counter on this wire, so the whole completion is text.
        assertEquals(30, finish.usage.outputTokens.text)
        assertNull(finish.usage.outputTokens.reasoning)
    }

    @Test
    fun `the undocumented cache spellings are read too, since one of them is a vendor typo`() = runTest {
        // `num_cached_tokens` (top level) and `prompt_token_details` (SINGULAR "token") appear in the
        // reference but in none of Mistral's published docs. Reading a field that is not sent costs
        // nothing; missing one that is costs the entire cache accounting.
        val flat = sse(done(usage = """{"prompt_tokens":100,"completion_tokens":10,"num_cached_tokens":60}"""))
        val flatFinish = provider(flat).languageModel("mistral-large-latest")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()
        assertEquals(60, flatFinish.usage.inputTokens.cacheRead)

        val typo = sse(
            done(usage = """{"prompt_tokens":100,"completion_tokens":10,"prompt_token_details":{"cached_tokens":40}}"""),
        )
        val typoFinish = provider(typo).languageModel("mistral-large-latest")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()
        assertEquals(40, typoFinish.usage.inputTokens.cacheRead)
    }

    @Test
    fun `no cache at all reports absent rather than a hard zero`() = runTest {
        val server = sse(done(usage = """{"prompt_tokens":100,"completion_tokens":10}"""))

        val finish = provider(server).languageModel("mistral-large-latest")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        // Mistral omits the field when nothing was cached; a reported zero would claim it said so.
        assertNull(finish.usage.inputTokens.cacheRead)
        assertEquals(100, finish.usage.inputTokens.noCache)
    }

    @Test
    fun `a named tool choice narrows the tool list, which is how Mistral expresses it`() = runTest {
        val server = sse(done())
        val tools = listOf(
            com.sabreware.aide.aisdk.Tool.Function(name = "a", inputSchema = buildJsonObject { }),
            com.sabreware.aide.aisdk.Tool.Function(name = "b", inputSchema = buildJsonObject { }),
        )

        provider(server).languageModel("mistral-large-latest").doStream(
            call.copy(tools = tools, toolChoice = com.sabreware.aide.aisdk.ToolChoice.Specific("b")),
        ).stream.toList()

        val body = server.request().bodyJson()
        // `ToolChoiceDialect.Mistral`, reused rather than reimplemented: there is no named-tool form,
        // so the pin is expressed by sending only the tool that was pinned.
        assertEquals(JsonPrimitive("any"), body["tool_choice"])
        assertEquals(1, body["tools"]!!.jsonArray.size)
        assertEquals(
            "b",
            body["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"].string(),
        )
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `reasoning_effort goes to the models Mistral lists for it, spelled from the neutral level`() = runTest {
        // The reference's table (`f87010e`), from https://api.mistral.ai/v1/models (2026-09-10).
        for (id in listOf(
            "mistral-medium-3-5", "mistral-medium-latest", "mistral-vibe-cli-fast", "zai-glm-5-2", "glm-5-2",
            "labs-leanstral-1-5",
        )) {
            val server = sse(done())

            val parts = provider(server).languageModel(id)
                .doStream(call.copy(reasoning = ReasoningEffort.High)).stream.toList()

            assertEquals("high", server.request().bodyJson()["reasoning_effort"].string(), id)
            val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            assertTrue(warnings.none { it is Warning.Unsupported && it.feature == "reasoning" }, id)
        }
    }

    @Test
    fun `off that list no reasoning_effort goes out, and either way of asking is told so`() = runTest {
        val neutral = sse(done())
        val neutralParts = provider(neutral).languageModel("mistral-large-latest")
            .doStream(call.copy(reasoning = ReasoningEffort.High)).stream.toList()
        assertNull(neutral.request().bodyJson()["reasoning_effort"])
        neutralParts.filterIsInstance<StreamPart.StreamStart>().single().warnings
            .assertUnsupported("reasoning", "This model does not support reasoning configuration.")

        // The reference drops an explicit spelling silently here; a warning is the house rule.
        val explicit = sse(done())
        val explicitParts = provider(explicit).languageModel("mistral-large-latest").doStream(
            call.copy(
                providerOptions = mapOf(MISTRAL_PROVIDER_ID to buildJsonObject { put("reasoningEffort", "high") }),
            ),
        ).stream.toList()
        assertNull(explicit.request().bodyJson()["reasoning_effort"])
        explicitParts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertUnsupported("reasoningEffort")
    }

    @Test
    fun `the neutral level folds onto the two values Mistral offers, and the default sends none`() = runTest {
        suspend fun sent(effort: ReasoningEffort): String? {
            val server = sse(done())
            provider(server).languageModel("magistral-medium-latest")
                .doStream(call.copy(reasoning = effort)).stream.toList()
            return server.request().bodyJson()["reasoning_effort"].string()
        }

        assertEquals("none", sent(ReasoningEffort.None))
        assertEquals("high", sent(ReasoningEffort.Low))
        assertEquals("high", sent(ReasoningEffort.XHigh))
        // The delegate would otherwise spell this in OpenAI's vocabulary, which Mistral rejects.
        assertEquals("high", sent(ReasoningEffort.Minimal))
        assertNull(sent(ReasoningEffort.ProviderDefault))
    }
}
