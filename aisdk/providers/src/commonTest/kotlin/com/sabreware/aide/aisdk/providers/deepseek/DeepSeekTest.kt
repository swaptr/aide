package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FinishReason
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openaicompatible.reasoningTrace
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * DeepSeek, validated against the vendor's own current documentation rather than only the vendored
 * reference — every field asserted here carries a doc URL in the code it pins.
 */
class DeepSeekTest {

    private fun provider(server: TestServer, baseUrl: String = DEEPSEEK_DEFAULT_BASE_URL) =
        DeepSeekProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-key",
            baseUrl = baseUrl,
        )

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello"))))

    private val call = CallOptions(prompt = prompt)

    /** `TestServer.sse` joins verbatim, so the framing is the caller's to add. */
    private fun sse(vararg objects: String) = TestServer(
        TestServer.sse(objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"),
    )

    private fun textServer() = sse(
        """{"id":"c","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"hi"},""" +
            """"finish_reason":"stop"}]}""",
    )

    // --- usage: the defect this provider exists for ---------------------------------------------------

    @Test
    fun `cache hits are read from DeepSeek's own counters, not OpenAI's nested one`() = runTest {
        // The exact shape from https://api-docs.deepseek.com/api/create-chat-completion. The shared
        // reader looks for prompt_tokens_details.cached_tokens, finds nothing, and reports a full-price
        // miss on every call — on the vendor whose headline feature is the hit/miss price gap.
        val server = sse(
            """{"id":"c","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":30,"prompt_cache_hit_tokens":80,""" +
                """"prompt_cache_miss_tokens":20,"total_tokens":130,""" +
                """"completion_tokens_details":{"reasoning_tokens":10}}}""",
        )

        val finish = provider(server).languageModel("deepseek-chat")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(80, finish.usage.inputTokens.cacheRead)
        // The vendor's own miss counter, preferred over prompt − hit.
        assertEquals(20, finish.usage.inputTokens.noCache)
        assertEquals(100, finish.usage.inputTokens.total)
        assertEquals(10, finish.usage.outputTokens.reasoning)
        assertEquals(20, finish.usage.outputTokens.text)
        // DeepSeek's cache is platform-written: never reported is not the same fact as reported-zero.
        assertNull(finish.usage.inputTokens.cacheWrite)
    }

    @Test
    fun `the miss counter is derived when the vendor omits it`() = runTest {
        val server = sse(
            """{"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_cache_hit_tokens":60}}""",
        )

        val finish = provider(server).languageModel("deepseek-chat")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(40, finish.usage.inputTokens.noCache)
    }

    // --- finish reasons -------------------------------------------------------------------------------

    @Test
    fun `insufficient_system_resource is an error, not an ordinary end of turn`() = runTest {
        // In DeepSeek's documented finish_reason list; in no shared table. Unmapped it arrives as
        // Other, and a loop keyed on the finish reason reads a truncated answer as a complete one.
        val server = sse(
            """{"id":"c","choices":[{"index":0,"delta":{"content":"par"},""" +
                """"finish_reason":"insufficient_system_resource"}]}""",
        )

        val finish = provider(server).languageModel("deepseek-chat")
            .doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(FinishReason.Unified.Error, finish.finishReason.unified)
        assertEquals("insufficient_system_resource", finish.finishReason.raw)
    }

    // --- thinking and the samplers it voids -----------------------------------------------------------

    @Test
    fun `thinking voids temperature and topP, and says so`() = runTest {
        // "will not trigger an error but will also have no effect"
        // https://api-docs.deepseek.com/guides/thinking_mode — the worst failure available, since the
        // call succeeds and the knob silently does nothing.
        val server = textServer()

        val parts = provider(server).languageModel("deepseek-reasoner")
            .doStream(call.copy(temperature = 0.2, topP = 0.9)).stream.toList()

        val body = server.request().bodyJson()
        assertNull(body["temperature"])
        assertNull(body["top_p"])
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        warnings.assertUnsupported("temperature")
        warnings.assertUnsupported("topP")
    }

    @Test
    fun `a non-thinking model keeps its samplers`() = runTest {
        val server = textServer()

        provider(server).languageModel("deepseek-chat")
            .doStream(call.copy(temperature = 0.2, topP = 0.9)).stream.toList()

        val body = server.request().bodyJson()
        assertEquals("0.2", body["temperature"].string())
        assertEquals("0.9", body["top_p"].string())
    }

    @Test
    fun `thinking disabled hands the samplers back`() = runTest {
        val server = textServer()

        provider(server).languageModel("deepseek-v4-pro").doStream(
            call.copy(
                temperature = 0.2,
                providerOptions = mapOf(
                    DEEPSEEK_PROVIDER_ID to buildJsonObject {
                        putJsonObject("thinking") { put("type", "disabled") }
                    },
                ),
            ),
        ).stream.toList()

        val body = server.request().bodyJson()
        assertEquals("0.2", body["temperature"].string())
        assertEquals("disabled", body.obj("thinking")?.get("type").string())
        // Effort is meaningless with thinking off, so it is not sent.
        assertNull(body["reasoning_effort"])
    }

    // --- penalties ------------------------------------------------------------------------------------

    @Test
    fun `the deprecated penalties are dropped with a deprecation warning`() = runTest {
        // Both are marked Deprecated (no longer supported) on
        // https://api-docs.deepseek.com/api/create-chat-completion.
        val server = textServer()

        val parts = provider(server).languageModel("deepseek-chat")
            .doStream(call.copy(frequencyPenalty = 0.5, presencePenalty = 0.5)).stream.toList()

        val body = server.request().bodyJson()
        assertNull(body["frequency_penalty"])
        assertNull(body["presence_penalty"])
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        assertTrue(warnings.any { it is Warning.Deprecated && it.setting == "frequencyPenalty" })
        assertTrue(warnings.any { it is Warning.Deprecated && it.setting == "presencePenalty" })
    }

    // --- reasoning effort -----------------------------------------------------------------------------

    @Test
    fun `the neutral effort levels fold onto the three DeepSeek accepts`() = runTest {
        // low | high | max, per https://api-docs.deepseek.com/api/create-chat-completion. The shared
        // model would send "medium" verbatim, which is not one of them.
        val cases = mapOf(
            ReasoningEffort.Minimal to "low",
            ReasoningEffort.Low to "low",
            ReasoningEffort.Medium to "high",
            ReasoningEffort.High to "high",
            ReasoningEffort.XHigh to "max",
        )

        for ((level, expected) in cases) {
            val server = textServer()
            provider(server).languageModel("deepseek-v4-pro")
                .doStream(call.copy(reasoning = level)).stream.toList()

            val body = server.request().bodyJson()
            assertEquals(expected, body["reasoning_effort"].string(), "for $level")
            // Top-level, beside `thinking` — not nested inside it.
            assertEquals("enabled", body.obj("thinking")?.get("type").string())
        }
    }

    @Test
    fun `ReasoningEffort None turns thinking off rather than asking for less of it`() = runTest {
        val server = textServer()

        provider(server).languageModel("deepseek-v4-pro")
            .doStream(call.copy(reasoning = ReasoningEffort.None)).stream.toList()

        val body = server.request().bodyJson()
        assertEquals("disabled", body.obj("thinking")?.get("type").string())
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `a legacy effort spelling is remapped with a warning, and an unknown one is refused`() = runTest {
        val server = textServer()

        val parts = provider(server).languageModel("deepseek-v4-pro").doStream(
            call.copy(
                providerOptions = mapOf(
                    DEEPSEEK_PROVIDER_ID to buildJsonObject { put("reasoningEffort", "xhigh") },
                ),
            ),
        ).stream.toList()

        assertEquals("max", server.request().bodyJson()["reasoning_effort"].string())
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        assertTrue(warnings.any { it is Warning.Compatibility && it.feature == "reasoningEffort" })

        assertFailsWith<InvalidArgumentError> {
            provider(textServer()).languageModel("deepseek-v4-pro").doStream(
                call.copy(
                    providerOptions = mapOf(
                        DEEPSEEK_PROVIDER_ID to buildJsonObject { put("reasoningEffort", "turbo") },
                    ),
                ),
            ).stream.toList()
        }
    }

    // --- vendor options -------------------------------------------------------------------------------

    @Test
    fun `topLogprobs forces logprobs on, because DeepSeek rejects the one without the other`() = runTest {
        val server = textServer()

        provider(server).languageModel("deepseek-chat").doStream(
            call.copy(
                providerOptions = mapOf(
                    DEEPSEEK_PROVIDER_ID to buildJsonObject {
                        put("topLogprobs", 5)
                        put("userId", "user_42")
                    },
                ),
            ),
        ).stream.toList()

        val body = server.request().bodyJson()
        assertEquals("true", body["logprobs"].string())
        assertEquals("5", body["top_logprobs"].string())
        assertEquals("user_42", body["user_id"].string())
    }

    @Test
    fun `an out-of-range topLogprobs and a malformed userId are refused before the request`() = runTest {
        // 0-20 and ^[a-zA-Z0-9_-]+$ / 512 chars, per the reference's schema.
        assertFailsWith<InvalidArgumentError> {
            provider(textServer()).languageModel("deepseek-chat").doStream(
                call.copy(
                    providerOptions = mapOf(
                        DEEPSEEK_PROVIDER_ID to buildJsonObject { put("topLogprobs", 99) },
                    ),
                ),
            ).stream.toList()
        }
        assertFailsWith<InvalidArgumentError> {
            provider(textServer()).languageModel("deepseek-chat").doStream(
                call.copy(
                    providerOptions = mapOf(
                        DEEPSEEK_PROVIDER_ID to buildJsonObject { put("userId", "has spaces") },
                    ),
                ),
            ).stream.toList()
        }
    }

    // --- prefix completion, which the base URL gates --------------------------------------------------

    @Test
    fun `a prefixed final assistant message reaches the wire on the beta base`() = runTest {
        // https://api-docs.deepseek.com/guides/chat_prefix_completion
        val server = textServer()
        val prefixed = listOf(
            ModelMessage.User(listOf(UserPart.Text("Please write quick sort code"))),
            ModelMessage.Assistant(
                listOf(AssistantPart.Text("```python\n")),
                providerOptions = mapOf(
                    DEEPSEEK_PROVIDER_ID to buildJsonObject { put("prefix", true) },
                ),
            ),
        )

        provider(server, DEEPSEEK_BETA_BASE_URL).languageModel("deepseek-chat")
            .doStream(CallOptions(prompt = prefixed)).stream.toList()

        val call = server.request()
        assertTrue(call.url.contains("/beta/chat/completions"))
        val assistant = call.bodyJson()["messages"]!!.toString()
        assertTrue(assistant.contains("\"prefix\":true"), "prefix must reach the message: $assistant")
    }

    @Test
    fun `prefix is refused off the beta base, on a non-final message, and on the wrong role`() = runTest {
        val prefixOption = mapOf(DEEPSEEK_PROVIDER_ID to buildJsonObject { put("prefix", true) })

        // Off beta: the feature does not exist at this endpoint at all.
        assertFailsWith<UnsupportedFunctionalityError> {
            provider(textServer()).languageModel("deepseek-chat").doStream(
                CallOptions(
                    prompt = listOf(
                        ModelMessage.User(listOf(UserPart.Text("go"))),
                        ModelMessage.Assistant(listOf(AssistantPart.Text("x")), providerOptions = prefixOption),
                    ),
                ),
            ).stream.toList()
        }

        // Not last: DeepSeek continues the FINAL message, so anywhere else is meaningless.
        assertFailsWith<InvalidPromptError> {
            provider(textServer(), DEEPSEEK_BETA_BASE_URL).languageModel("deepseek-chat").doStream(
                CallOptions(
                    prompt = listOf(
                        ModelMessage.Assistant(listOf(AssistantPart.Text("x")), providerOptions = prefixOption),
                        ModelMessage.User(listOf(UserPart.Text("go"))),
                    ),
                ),
            ).stream.toList()
        }

        // Wrong role.
        assertFailsWith<InvalidPromptError> {
            provider(textServer(), DEEPSEEK_BETA_BASE_URL).languageModel("deepseek-chat").doStream(
                CallOptions(
                    prompt = listOf(ModelMessage.User(listOf(UserPart.Text("go")), providerOptions = prefixOption)),
                ),
            ).stream.toList()
        }
    }

    // --- strict tools ---------------------------------------------------------------------------------

    @Test
    fun `strict tools are beta-only and cannot be mixed with non-strict ones`() = runTest {
        val strict = Tool.Function(
            name = "a",
            inputSchema = buildJsonObject { put("type", "object") },
            strict = true,
        )
        val loose = Tool.Function(name = "b", inputSchema = buildJsonObject { put("type", "object") })

        assertFailsWith<UnsupportedFunctionalityError> {
            provider(textServer()).languageModel("deepseek-chat")
                .doStream(call.copy(tools = listOf(strict))).stream.toList()
        }
        assertFailsWith<UnsupportedFunctionalityError> {
            provider(textServer(), DEEPSEEK_BETA_BASE_URL).languageModel("deepseek-chat")
                .doStream(call.copy(tools = listOf(strict, loose))).stream.toList()
        }
    }

    // --- retryability ---------------------------------------------------------------------------------

    @Test
    fun `an exhausted balance is not retried, where a rate limit is`() = runTest {
        // https://api-docs.deepseek.com/quick_start/error_codes — 402 out of balance fails identically
        // forever; 429 clears. On a stream both arrive inside an HTTP 200, so the code is the only
        // signal available.
        val quota = sse("""{"error":{"message":"out of balance","code":"insufficient_quota"}}""")
        val quotaError = provider(quota).languageModel("deepseek-chat")
            .doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Error>().single().error as APICallError
        assertEquals(false, quotaError.isRetryable)

        val limited = sse("""{"error":{"message":"slow down","code":"rate_limit_exceeded"}}""")
        val limitedError = provider(limited).languageModel("deepseek-chat")
            .doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Error>().single().error as APICallError
        assertEquals(true, limitedError.isRetryable)
    }

    @Test
    fun `a numeric code restates the HTTP status a streamed error otherwise lacks`() = runTest {
        val server = sse("""{"error":{"message":"overloaded","code":"503"}}""")

        val error = provider(server).languageModel("deepseek-chat")
            .doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Error>().single().error as APICallError

        assertEquals(true, error.isRetryable)
    }

    // --- unchanged shared behaviour -------------------------------------------------------------------

    @Test
    fun `seed is refused and topK warns, since neither is in DeepSeek's schema`() = runTest {
        val server = textServer()

        val parts = provider(server).languageModel("deepseek-chat")
            .doStream(call.copy(seed = 7, topK = 5)).stream.toList()

        val body = server.request().bodyJson()
        assertNull(body["seed"])
        assertNull(body["top_k"])
        val warnings = parts.filterIsInstance<StreamPart.StreamStart>().single().warnings
        warnings.assertUnsupported("seed")
        warnings.assertUnsupported("topK")
    }

    @Test
    fun `the request goes to the documented base and carries bearer auth`() = runTest {
        val server = textServer()

        provider(server).languageModel("deepseek-chat").doStream(call).stream.toList()

        val request = server.request()
        assertEquals("https://api.deepseek.com/chat/completions", request.url.substringBefore('?'))
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("deepseek-chat", request.bodyJson()["model"].string())
    }

    @Test
    fun `chat is the only modality, because advertising more is how a 404 gets promised`() {
        // Moved here when the `Vendors.deepSeek` row was deleted. The honesty rule outlives the row: a
        // capability the vendor does not serve is bound NOWHERE, so a caller reads null and omits the
        // affordance rather than meeting a 404 at runtime.
        val deepSeek = provider(textServer())

        assertNull(deepSeek.imageModel("anything"))
        assertNull(deepSeek.embeddingModel("anything"))
        assertNull(deepSeek.speechModel("anything"))
        assertNull(deepSeek.transcriptionModel("anything"))
    }

    // --- ai@7.0.102 -----------------------------------------------------------------------------

    @Test
    fun `the deepseek-flash alias is a V4 model, so thinking is on by default`() = runTest {
        // https://api-docs.deepseek.com/quick_start/pricing — `deepseek-flash` currently serves V4.1
        // Flash. Missing the alias meant a caller's temperature went out and was silently ignored.
        val server = textServer()

        val parts = provider(server).languageModel("deepseek-flash")
            .doStream(call.copy(temperature = 0.2)).stream.toList()

        assertNull(server.request().bodyJson()["temperature"])
        parts.filterIsInstance<StreamPart.StreamStart>().single().warnings.assertUnsupported("temperature")
    }

    @Test
    fun `V4 is every versioned id and both unversioned aliases, and neither legacy id`() {
        // The reference's table (`is-deepseek-v4-model.test.ts`).
        for (id in listOf(
            "deepseek-v4-pro", "deepseek-v4-pro-0813", "deepseek-v4-flash", "deepseek-v4-flash-0731",
            "deepseek-v4-flash-vision-exp", "deepseek-flash", "deepseek-pro",
        )) {
            assertTrue(isDeepSeekV4Model(id), id)
        }
        for (id in listOf("deepseek-chat", "deepseek-reasoner")) {
            assertFalse(isDeepSeekV4Model(id), id)
        }
    }

    @Test
    fun `reasoning stays one block across deltas that carry an empty tool_calls array`() = runTest {
        // The reference's fixture (`0096850`); the fix is the compat engine's, this pins it on this wire.
        val server = sse(
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":"Think ","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"","reasoning_content":"more...","tool_calls":[]},""" +
                """"finish_reason":null}]}""",
            """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"delta":{"content":"Hello","reasoning_content":"","tool_calls":[]},""" +
                """"finish_reason":"stop"}]}""",
        )

        val parts = provider(server).languageModel("deepseek-reasoner").doStream(call).stream.toList()

        assertEquals(
            listOf("start reasoning-0", "delta reasoning-0 Think ", "delta reasoning-0 more...", "end reasoning-0"),
            parts.reasoningTrace(),
        )
    }
}
