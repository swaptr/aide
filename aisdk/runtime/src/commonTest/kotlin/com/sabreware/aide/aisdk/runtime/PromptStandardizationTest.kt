package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.MissingToolResultsError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun user(text: String) = ModelMessage.User(listOf(UserPart.Text(text)))

private fun assistantCall(id: String, providerExecuted: Boolean = false) = ModelMessage.Assistant(
    listOf(AssistantPart.ToolCall(id, "search", """{"q":"a"}""", providerExecuted = providerExecuted)),
)

private fun toolResult(id: String, options: Map<String, JsonObject>? = null) = ModelMessage.Tool(
    listOf(ToolPart.Result(id, "search", ToolOutput.Text("ok"))),
    providerOptions = options,
)

class PromptStandardizationTest {

    @Test
    fun `instructions become the leading system message`() {
        val out = standardizePrompt(listOf(user("hi")), instructions = "be terse")
        assertEquals(ModelMessage.System("be terse"), out.first())
        assertEquals(2, out.size)
    }

    @Test
    fun `an empty prompt with no instructions is rejected`() {
        assertFailsWith<InvalidPromptError> { standardizePrompt(emptyList()) }
    }

    @Test
    fun `a system message after the conversation has started is rejected`() {
        assertFailsWith<InvalidPromptError> {
            standardizePrompt(listOf(user("hi"), ModelMessage.System("and be terse")))
        }
    }

    @Test
    fun `leading system messages are allowed`() {
        val prompt = listOf(ModelMessage.System("a"), ModelMessage.System("b"), user("hi"))
        assertEquals(prompt, standardizePrompt(prompt))
    }

    @Test
    fun `consecutive tool messages merge into one`() {
        val out = standardizePrompt(
            listOf(
                user("hi"),
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.ToolCall("1", "search", "{}"),
                        AssistantPart.ToolCall("2", "search", "{}"),
                    ),
                ),
                toolResult("1"),
                toolResult("2"),
            ),
        )
        val merged = out.filterIsInstance<ModelMessage.Tool>()
        assertEquals(1, merged.size)
        assertEquals(listOf("1", "2"), merged.single().content.map { (it as ToolPart.Result).toolCallId })
    }

    @Test
    fun `an absorbed tool message keeps its provider options on its last part`() {
        val cacheControl = mapOf("anthropic" to JsonObject(mapOf("cacheControl" to JsonPrimitive("ephemeral"))))
        val out = standardizePrompt(
            listOf(
                user("hi"),
                ModelMessage.Assistant(
                    listOf(
                        AssistantPart.ToolCall("1", "search", "{}"),
                        AssistantPart.ToolCall("2", "search", "{}"),
                    ),
                ),
                toolResult("1", options = cacheControl),
                toolResult("2"),
            ),
        )
        val merged = out.filterIsInstance<ModelMessage.Tool>().single()
        assertEquals(cacheControl, merged.content.first().providerOptions)
    }

    @Test
    fun `an empty tool message is dropped`() {
        val out = standardizePrompt(listOf(user("hi"), ModelMessage.Tool(emptyList())))
        assertEquals(listOf(user("hi")), out)
    }

    @Test
    fun `an unanswered tool call before the next user turn is rejected`() {
        val error = assertFailsWith<MissingToolResultsError> {
            standardizePrompt(listOf(user("hi"), assistantCall("1"), user("still there?")))
        }
        assertEquals(listOf("1"), error.toolCallIds)
    }

    @Test
    fun `an unanswered tool call at the end of the prompt is rejected`() {
        assertFailsWith<MissingToolResultsError> {
            standardizePrompt(listOf(user("hi"), assistantCall("1")))
        }
    }

    @Test
    fun `a provider-executed call needs no result`() {
        val prompt = listOf(user("hi"), assistantCall("1", providerExecuted = true))
        assertEquals(prompt, standardizePrompt(prompt))
    }

    @Test
    fun `a denied call counts as answered`() {
        val prompt = listOf(
            user("hi"),
            assistantCall("1"),
            ModelMessage.Tool(listOf(ToolPart.Result("1", "search", ToolOutput.ExecutionDenied("no")))),
            user("ok"),
        )
        assertEquals(prompt, standardizePrompt(prompt))
    }
}

class AssetDownloadTest {

    private val remote = listOf(
        ModelMessage.User(
            listOf(UserPart.File(FileData.Url("https://example.com/A.PDF"), "application/pdf")),
        ),
    )

    @Test
    fun `a url the model fetches itself is left alone`() = runTest {
        val out = remote.withDownloadedAssets(
            supportedUrls = mapOf("application/pdf" to listOf(Regex("^https://"))),
            download = { _, _ -> error("should not download") },
        )
        assertEquals(remote, out)
    }

    @Test
    fun `a wildcard media type matches`() = runTest {
        val out = remote.withDownloadedAssets(
            supportedUrls = mapOf("*" to listOf(Regex("^https://"))),
            download = { _, _ -> error("should not download") },
        )
        assertEquals(remote, out)
    }

    @Test
    fun `an unsupported url is downloaded into bytes`() = runTest {
        val out = remote.withDownloadedAssets(
            supportedUrls = mapOf("image/*" to listOf(Regex("^https://"))),
            download = { url, _ -> DownloadedAsset(url.encodeToByteArray(), "application/pdf") },
        )
        val part = (out.single() as ModelMessage.User).content.single() as UserPart.File
        val data = assertIs<FileData.Bytes>(part.data)
        assertEquals("https://example.com/A.PDF", data.bytes.decodeToString())
    }

    @Test
    fun `patterns match the lower-cased url`() = runTest {
        var seen: String? = null
        remote.withDownloadedAssets(
            supportedUrls = mapOf("application/pdf" to listOf(Regex("\\.pdf$"))),
            download = { url, _ -> seen = url; DownloadedAsset(ByteArray(0), null) },
        )
        assertEquals(null, seen)
    }

    @Test
    fun `a failed download leaves the url for the provider to try`() = runTest {
        val out = remote.withDownloadedAssets(
            supportedUrls = emptyMap(),
            download = { _, _ -> throw IllegalStateException("404") },
        )
        assertEquals(remote, out)
    }
}

class CallOptionValidationTest {

    private val prompt = listOf(user("hi"))

    @Test
    fun `a zero output-token budget is rejected`() {
        val error = assertFailsWith<InvalidArgumentError> {
            CallOptions(prompt = prompt, maxOutputTokens = 0).validated()
        }
        assertEquals("maxOutputTokens", error.argument)
    }

    @Test
    fun `a non-finite sampler is rejected`() {
        val error = assertFailsWith<InvalidArgumentError> {
            CallOptions(prompt = prompt, temperature = Double.NaN).validated()
        }
        assertEquals("temperature", error.argument)
    }

    @Test
    fun `unset settings pass`() {
        val options = CallOptions(prompt = prompt)
        assertTrue(options.validated() === options)
    }

    @Test
    fun `a deferred vendor tool's unanswered call is not an error`() {
        val messages = listOf(
            ModelMessage.User(listOf(UserPart.Text("go"))),
            ModelMessage.Assistant(
                listOf(AssistantPart.ToolCall(toolCallId = "c1", toolName = "code_exec", input = "{}")),
            ),
            ModelMessage.User(listOf(UserPart.Text("next turn"))),
        )

        // Without the exemption this is MissingToolResultsError — but a deferred vendor tool may
        // legitimately answer a turn later, so the same prompt passes when the tool declares it.
        assertFailsWith<MissingToolResultsError> { standardizePrompt(messages) }
        standardizePrompt(messages, deferredToolNames = setOf("code_exec"))
    }
}
