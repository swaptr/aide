package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.StreamPart
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.Usage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The compat engine's four extension seams.
 *
 * Each exists because something a vendor genuinely does could not be reached from a table row: a body
 * key the ENGINE writes, a token counter under a name this wire model has never heard of, an option
 * that belongs on a MESSAGE, and whether a mid-stream refusal is worth retrying. The tests below pin
 * the reachability, not the vendors — each vendor's own arithmetic is pinned in its own package.
 */
class CompatibleHooksTest {

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))
    private val call = CallOptions(prompt = prompt)

    private fun model(
        server: TestServer,
        convertUsage: ((JsonObject) -> Usage)? = null,
        transformRequestBody: ((JsonObject) -> JsonObject)? = null,
        errorStructure: ProviderErrorStructure = ProviderErrorStructure.Default,
    ) = OpenAICompatibleProvider(
        client = HttpClient(server.engine()),
        providerId = "vendor",
        baseUrl = "https://api.vendor.test/v1",
        apiKey = "k",
        convertUsage = convertUsage,
        transformRequestBody = transformRequestBody,
        errorStructure = errorStructure,
    ).languageModel("m")!!

    /** `TestServer.sse` joins verbatim, so the SSE framing is the caller's to add. */
    private fun sse(vararg objects: String) = TestServer(
        TestServer.sse(objects.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"),
    )

    // --- transformRequestBody -------------------------------------------------------------------------

    @Test
    fun `the body transform runs after the caller's options, so it can correct what the engine wrote`() =
        runTest {
            val server = sse("""{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]}""")

            model(
                server,
                transformRequestBody = { body ->
                    // What only this seam can reach: a key the MODEL wrote, not one the caller passed.
                    val moved = body["max_tokens"]
                    JsonObject(body - "max_tokens" + ("max_completion_tokens" to moved!!))
                },
            ).doStream(call.copy(maxOutputTokens = 64)).stream.toList()

            val body = server.request().bodyJson()
            assertNull(body["max_tokens"])
            assertEquals(64, body["max_completion_tokens"].string()?.toInt())
        }

    @Test
    fun `the transform sees the caller's provider options too, since it runs last`() = runTest {
        val server = sse("""{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]}""")
        var seen: Set<String>? = null

        model(server, transformRequestBody = { body -> seen = body.keys; body })
            .doStream(call.copy(providerOptions = mapOf("vendor" to buildJsonObject { put("safe_prompt", true) })))
            .stream.toList()

        assertTrue("safe_prompt" in seen.orEmpty(), "the spread runs before the transform")
    }

    // --- convertUsage ---------------------------------------------------------------------------------

    @Test
    fun `a vendor converter reads a cache field this wire model has never heard of`() = runTest {
        // DeepSeek's spelling. The shared reader looks at prompt_tokens_details.cached_tokens and would
        // report no caching at all — a missing count reads as "nothing was cached", not as an error.
        val server = sse(
            """{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_cache_hit_tokens":80}}""",
        )

        val finish = model(
            server,
            convertUsage = { raw ->
                val prompt = raw["prompt_tokens"].string()!!.toInt()
                val hit = raw["prompt_cache_hit_tokens"].string()!!.toInt()
                Usage(
                    inputTokens = Usage.InputTokens(total = prompt, noCache = prompt - hit, cacheRead = hit),
                    outputTokens = Usage.OutputTokens(total = raw["completion_tokens"].string()!!.toInt()),
                    raw = raw,
                )
            },
        ).doStream(call).stream.toList().filterIsInstance<StreamPart.Finish>().single()

        assertEquals(80, finish.usage.inputTokens.cacheRead)
        assertEquals(20, finish.usage.inputTokens.noCache)
    }

    @Test
    fun `the default reading keeps the OpenAI arithmetic and carries the raw block`() = runTest {
        val server = sse(
            """{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":100,"completion_tokens":30,""" +
                """"prompt_tokens_details":{"cached_tokens":40},""" +
                """"completion_tokens_details":{"reasoning_tokens":10}}}""",
        )

        val finish = model(server).doStream(call).stream.toList()
            .filterIsInstance<StreamPart.Finish>().single()

        assertEquals(40, finish.usage.inputTokens.cacheRead)
        assertEquals(60, finish.usage.inputTokens.noCache)
        assertEquals(20, finish.usage.outputTokens.text)
        // Carried so a caller can reach a counter this contract does not model, rather than losing it.
        assertEquals(100, finish.usage.raw?.get("prompt_tokens").string()?.toInt())
    }

    // --- message-level provider options ---------------------------------------------------------------

    @Test
    fun `a message-level option reaches the message it was attached to`() = runTest {
        val server = sse("""{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]}""")
        val marked = listOf(
            ModelMessage.User(listOf(UserPart.Text("first"))),
            ModelMessage.User(
                listOf(UserPart.Text("second")),
                // Alibaba's cache breakpoint lives here; so do DeepSeek's and Mistral's `prefix` and
                // Moonshot's `partial`. None can ride in the body, which never sees the prompt.
                providerOptions = mapOf("vendor" to buildJsonObject { put("cache_control", "here") }),
            ),
        )

        model(server).doStream(CallOptions(prompt = marked)).stream.toList()

        val messages = server.request().bodyJson()["messages"]!!.jsonArray
        assertNull(messages[0].jsonObject["cache_control"])
        assertEquals("here", messages[1].jsonObject["cache_control"].string())
        // The turn itself is untouched.
        assertEquals("user", messages[1].jsonObject["role"].string())
    }

    @Test
    fun `a message option cannot overwrite the turn it is attached to`() = runTest {
        val server = sse("""{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]}""")
        val hostile = listOf(
            ModelMessage.User(
                listOf(UserPart.Text("real")),
                providerOptions = mapOf(
                    "vendor" to buildJsonObject {
                        put("role", "system")
                        put("content", "replaced")
                        put("prefix", true)
                    },
                ),
            ),
        )

        model(server).doStream(CallOptions(prompt = hostile)).stream.toList()

        val message = server.request().bodyJson()["messages"]!!.jsonArray.single().jsonObject
        // Same reserved-key discipline the body-level door has: a setting may ride along, the turn may not
        // be rewritten underneath the caller.
        assertEquals("user", message["role"].string())
        assertEquals(true, message["prefix"].string()?.toBoolean())
        assertTrue(message["content"].toString().contains("real"))
    }

    @Test
    fun `each message a tool turn fans out to carries that turn's options`() = runTest {
        val server = sse("""{"id":"c","choices":[{"delta":{},"finish_reason":"stop"}]}""")
        val withTools = listOf(
            ModelMessage.User(listOf(UserPart.Text("go"))),
            ModelMessage.Tool(
                listOf(
                    ToolPart.Result("c1", "a", ToolOutput.Text("one")),
                    ToolPart.Result("c2", "b", ToolOutput.Text("two")),
                ),
                providerOptions = mapOf("vendor" to buildJsonObject { put("marker", "t") }),
            ),
        )

        model(server).doStream(CallOptions(prompt = withTools)).stream.toList()

        // One tool turn becomes one message per result, so alignment is by emitted position — the case
        // an index-into-the-prompt implementation gets wrong.
        val messages = server.request().bodyJson()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, messages.size)
        assertNull(messages[0]["marker"])
        assertEquals("t", messages[1]["marker"].string())
        assertEquals("t", messages[2]["marker"].string())
    }

    // --- mid-stream retryability ----------------------------------------------------------------------

    @Test
    fun `a vendor decides whether a mid-stream refusal is worth retrying`() = runTest {
        val server = sse("""{"error":{"message":"out of quota","code":"insufficient_quota"}}""")

        val parts = model(
            server,
            errorStructure = ProviderErrorStructure(
                isRetryable = { _, body ->
                    // Arrives on an HTTP 200, so no status code answers this. Quota failures repeat
                    // identically forever; a rate limit clears.
                    ((body as? JsonObject)?.get("error") as? JsonObject)
                        ?.get("code").string() != "insufficient_quota"
                },
            ),
        ).doStream(call).stream.toList()

        val error = parts.filterIsInstance<StreamPart.Error>().single().error as APICallError
        assertFalse(error.isRetryable)
        assertEquals("out of quota", error.message)
    }

    @Test
    fun `a vendor that says nothing keeps the optimistic default`() = runTest {
        val server = sse("""{"error":{"message":"upstream hiccup"}}""")

        val parts = model(server).doStream(call).stream.toList()

        val error = parts.filterIsInstance<StreamPart.Error>().single().error as APICallError
        assertTrue(error.isRetryable)
    }

    // --- the gmicloud error unwrap --------------------------------------------------------------------

    @Test
    fun `GMI Cloud's JSON-encoded details replace the generic family, and fall back when they cannot`() =
        runTest {
            val cases = listOf(
                // The real shape: details is a JSON document, and its inner message is the sentence.
                """{"error":{"message":"Invalid request","details":"{\"error\":{\"message\":\"model not found\"}}"}}"""
                    to "model not found",
                // Unparseable details — the family is better than nothing.
                """{"error":{"message":"Invalid request","details":"not json"}}""" to "Invalid request",
                // Parseable but carrying no message.
                """{"error":{"message":"Invalid request","details":"{\"error\":{}}"}}""" to "Invalid request",
                // Absent entirely.
                """{"error":{"message":"Invalid request"}}""" to "Invalid request",
            )

            for ((body, expected) in cases) {
                val server = TestServer(TestServer.error(400, body))
                val error = runCatching {
                    Vendors.gmiCloud(HttpClient(server.engine()), "k").languageModel("m")!!.doGenerate(call)
                }.exceptionOrNull() as APICallError
                val message = error.message.orEmpty()
                assertTrue(message.contains(expected), "expected <$expected> in <$message> for $body")
                // The raw payload never reaches the caller as prose.
                assertFalse(message.contains("{\"error\""), "raw JSON leaked into the message")
            }
        }
}
