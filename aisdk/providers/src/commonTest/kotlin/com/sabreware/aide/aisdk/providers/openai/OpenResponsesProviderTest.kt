package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The two `open-responses` knobs of the 2026-09 delta, against `convert-to-open-responses-input.test.ts`:
 * strict assistant-history serialization, and `detail` on every image input.
 */
class OpenResponsesProviderTest {

    private val url = "http://localhost:1234/v1/responses"

    private fun model(server: TestServer, strictResponseInput: Boolean = false) = OpenResponsesProvider(
        client = HttpClient(server.engine()),
        url = url,
        name = "lmstudio",
        strictResponseInput = strictResponseInput,
    ).languageModel("gemma-7b-it")

    private fun server() = TestServer(TestServer.json("""{"id":"resp_1","output":[]}"""))

    @Test
    fun `strict input sends assistant history in the spec's own shapes`() = runTest {
        val server = server()
        model(server, strictResponseInput = true).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.Text("hi"))),
                    ModelMessage.Assistant(listOf(AssistantPart.Text("Hello from assistant"))),
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Text(
                                "Hello again",
                                providerOptions = mapOf("lmstudio" to buildJsonObject { put(OPENAI_ITEM_ID_KEY, "msg_123") }),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val input = server.request().bodyJson().arr("input")!!.map { it.jsonObject }
        // No id: the "easy" message, content as a bare string, and no id invented for it.
        assertEquals(
            parseJsonObject("""{"type":"message","role":"assistant","content":"Hello from assistant"}"""),
            input[1],
        )
        // An id: the complete output item, with the empty collections the schema requires.
        assertEquals(
            parseJsonObject(
                """{"id":"msg_123","type":"message","status":"completed","role":"assistant",""" +
                    """"content":[{"type":"output_text","text":"Hello again","annotations":[],"logprobs":[]}]}""",
            ),
            input[2],
        )
    }

    @Test
    fun `without strict input the OpenAI shape is unchanged`() = runTest {
        val server = server()
        model(server).doGenerate(
            CallOptions(prompt = listOf(ModelMessage.Assistant(listOf(AssistantPart.Text("Hello from assistant"))))),
        )

        assertEquals(
            parseJsonObject("""{"role":"assistant","content":[{"type":"output_text","text":"Hello from assistant"}]}"""),
            server.request().bodyJson().arr("input")!!.single().jsonObject,
        )
    }

    @Test
    fun `an image input carries detail auto unless the part says otherwise`() = runTest {
        val server = server()
        model(server).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(
                        listOf(
                            UserPart.File(FileData.Url("https://example.com/image.png"), "image/png"),
                            UserPart.File(
                                FileData.Bytes("fake-data".encodeToByteArray()),
                                "image/png",
                                providerOptions = mapOf("lmstudio" to buildJsonObject { put("imageDetail", "low") }),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val content = server.request().bodyJson().arr("input")!!.single().jsonObject.arr("content")!!
        assertEquals(listOf("auto", "low"), content.map { it.jsonObject["detail"].string() })
    }

    @Test
    fun `OpenAI's own endpoint leaves detail to the server`() = runTest {
        val server = server()
        OpenAIResponsesLanguageModel(modelId = "gpt-5", http = server.http()).doGenerate(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.File(FileData.Url("https://example.com/image.png"), "image/png"))),
                ),
            ),
        )

        val part = server.request().bodyJson().arr("input")!!.single().jsonObject.arr("content")!!.single().jsonObject
        assertNull(part["detail"])
    }
}
