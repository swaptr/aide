package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.google.ToolResultDownloads
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

/**
 * What Vertex's Gemini path adds to the shared request builder: the reference's Vertex AI case of the
 * schema test, and the tool-result download `google-vertex-provider-base.test.ts` configures.
 */
@OptIn(ExperimentalEncodingApi::class)
class VertexGeminiRequestTest {

    private val finished = TestServer.sse("""data: {"candidates":[{"finishReason":"STOP"}]}""" + "\n\n")

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    private fun provider(server: TestServer, downloads: ToolResultDownloads? = null) = if (downloads == null) {
        VertexProvider(HttpClient(server.engine()), "test-project", "test-location", accessToken = { "test-token" })
    } else {
        VertexProvider(
            HttpClient(server.engine()),
            "test-project",
            "test-location",
            accessToken = { "test-token" },
            toolResultDownloads = downloads,
        )
    }

    private val call = CallOptions(prompt = listOf(ModelMessage.User(listOf(UserPart.Text("Hello")))))

    private fun toolResultCall(url: String) = call.copy(
        prompt = call.prompt + ModelMessage.Tool(
            listOf(
                ToolPart.Result(
                    toolCallId = "tool-call-id",
                    toolName = "get-image",
                    output = ToolOutput.Multipart(listOf(ToolOutput.Multipart.Item.File(FileData.Url(url), mediaType = "image"))),
                ),
            ),
        ),
    )

    @Test
    fun `a tool schema is preserved verbatim on Vertex too`() = runTest {
        // "should preserve local JSON Schema references in Vertex AI tool requests".
        val schema = parseJsonObject(
            """{"type":"object","properties":{"locale":{"${'$'}ref":"#/${'$'}defs/Locale",""" +
                """"description":"Locale for formatting"}},"required":["locale"],"additionalProperties":false,""" +
                """"${'$'}defs":{"Locale":{"type":"string","enum":["de","en"]}}}""",
        )
        val server = TestServer(finished)

        provider(server).languageModel("gemini-pro").doStream(
            call.copy(tools = listOf(Tool.Function("format-date", schema, description = "Format a date"))),
        ).stream.toList()

        val declaration = server.request().bodyJson().arr("tools")!!.single().jsonObject
            .arr("functionDeclarations")!!.single().jsonObject
        assertEquals(schema, declaration.obj("parametersJsonSchema"))
    }

    @Test
    fun `a tool result's URL file is downloaded and sent inline`() = runTest {
        // Vertex function responses take inline data only; the URL a tool returned is fetched first,
        // with no credential attached, and rides `functionResponse.parts` as the bytes it named.
        val server = TestServer(TestServer.bytes(jpeg, contentType = "image/jpeg"), finished)

        provider(server).languageModel("gemini-3-pro").doStream(toolResultCall("https://example.com/tool-image.jpg"))
            .stream.toList()

        val download = server.request(0)
        assertEquals("https://example.com/tool-image.jpg", download.url)
        download.assertNoHeader("Authorization")
        // The tool turn merges into the user turn — Gemini wants alternating roles — so find the part.
        val response = server.request(1).bodyJson().arr("contents")!!.last().jsonObject
            .arr("parts")!!.map { it.jsonObject }.single { "functionResponse" in it }.obj("functionResponse")!!
        assertEquals(
            parseJsonObject("""{"mimeType":"image/jpeg","data":"${Base64.encode(jpeg)}"}"""),
            response.arr("parts")!!.single().jsonObject.obj("inlineData"),
        )
        assertEquals("Tool executed successfully.", response.obj("response")!!["result"].string())
    }

    @Test
    fun `the download cap is the host's to set`() = runTest {
        // "should configure the tool result download size limit".
        val server = TestServer(TestServer.bytes(jpeg, contentType = "image/jpeg"), finished)

        assertFailsWith<DownloadError> {
            provider(server, ToolResultDownloads(maxBytes = 2)).languageModel("gemini-3-pro")
                .doStream(toolResultCall("https://example.com/tool-image.jpg"))
        }
        assertEquals(1, server.callCount, "nothing reached Vertex")
    }
}
