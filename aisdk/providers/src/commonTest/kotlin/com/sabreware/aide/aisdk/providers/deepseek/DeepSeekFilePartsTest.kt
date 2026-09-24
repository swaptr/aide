package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * DeepSeek's image input: the FLAT `file_id` part the vendor documents, and the two checks that run
 * before a request — ported from `convert-to-deepseek-chat-messages.ts` and the `file_id` case of
 * `deepseek-chat-language-model.test.ts`.
 */
class DeepSeekFilePartsTest {

    private fun model(server: TestServer) = DeepSeekProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    ).languageModel("deepseek-chat")

    private fun textServer() = TestServer(
        TestServer.sse(
            "data: " +
                """{"id":"c","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"hi"},""" +
                """"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n",
        ),
    )

    private fun prompt(vararg parts: UserPart) = listOf(ModelMessage.User(parts.toList()))

    @Test
    fun `an uploaded image is named by a flat file_id part`() = runTest {
        val server = textServer()

        model(server).doStream(
            CallOptions(
                prompt = prompt(
                    UserPart.Text("Describe the image."),
                    UserPart.File(
                        data = FileData.Reference(mapOf(DEEPSEEK_PROVIDER_ID to "file-api-deepseek")),
                        mediaType = "image/png",
                    ),
                ),
            ),
        ).stream.toList()

        val content = server.request().bodyJson()["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"].string())
        // Flat, as DeepSeek reads it — NOT OpenAI's `{"type":"file","file":{"file_id":…}}`.
        assertEquals(
            buildJsonObject {
                put("type", "file")
                put("file_id", "file-api-deepseek")
            },
            content[1].jsonObject,
        )
    }

    @Test
    fun `inline image bytes still go as an image_url data URL`() = runTest {
        val server = textServer()

        model(server).doStream(
            CallOptions(
                prompt = prompt(
                    UserPart.File(
                        data = FileData.Bytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
                        mediaType = "image/png",
                    ),
                ),
            ),
        ).stream.toList()

        val content = server.request().bodyJson()["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val part = content[0].jsonObject
        assertEquals("image_url", part["type"].string())
        assertTrue(part["image_url"]!!.jsonObject["url"].string()!!.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `a body without messages passes the transform untouched`() {
        val body = buildJsonObject { put("model", "deepseek-chat") }

        assertEquals(body, deepSeekRequestBody(body))
    }

    @Test
    fun `an image format DeepSeek does not take is refused before the request`() = runTest {
        val server = textServer()

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStream(
                CallOptions(
                    prompt = prompt(
                        UserPart.File(
                            data = FileData.Url("https://example.com/photo.bmp"),
                            mediaType = "image/bmp",
                        ),
                    ),
                ),
            )
        }

        assertEquals("DeepSeek image media type image/bmp", error.functionality)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the bytes outrank the declared type`() = runTest {
        val server = textServer()

        assertFailsWith<UnsupportedFunctionalityError> {
            model(server).doStream(
                CallOptions(
                    prompt = prompt(
                        // Declared PNG, actually a PDF.
                        UserPart.File(data = FileData.Bytes("%PDF-1.7".encodeToByteArray()), mediaType = "image/png"),
                    ),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `an image URL past 8192 characters is refused before the request`() = runTest {
        val server = textServer()

        assertFailsWith<InvalidPromptError> {
            model(server).doStream(
                CallOptions(
                    prompt = prompt(
                        UserPart.File(
                            data = FileData.Url("https://example.com/" + "a".repeat(8192)),
                            mediaType = "image/png",
                        ),
                    ),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }
}
