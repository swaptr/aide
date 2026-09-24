package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeToolDefinition
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * xAI's realtime voice protocol, pinned against `xai-realtime-model.ts` and its event mapper.
 *
 * Two properties carry the session: the token travels as a SUBPROTOCOL (a browser cannot set a header,
 * so it is the only field such a client can populate), and the model id travels in the URL's query
 * string — xAI falls back to its default voice model when it is absent, so a missing one is a silently
 * different voice rather than an error.
 */
class XaiRealtimeModelTest {

    private fun xai(server: TestServer) = XaiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    private fun model(server: TestServer) = xai(server).realtimeModel("grok-realtime-1")

    private fun parse(json: String) = parseJsonObject(json)

    @Test
    fun `a client secret is minted and points at the socket with the model in the query`() = runTest {
        val server = TestServer(TestServer.json("""{"value":"cs-abc","expires_at":1750000000}"""))

        val secret = model(server).doCreateClientSecret(RealtimeClientSecretOptions(expiresAfterSeconds = 60))

        val call = server.request()
        assertEquals("v1/realtime/client_secrets", call.path)
        assertEquals(
            "60",
            (call.bodyJson()["expires_after"] as JsonObject)["seconds"].toString(),
        )
        assertEquals("cs-abc", secret.token)
        assertEquals(1750000000L, secret.expiresAt)
        assertTrue(secret.url.startsWith("wss://"))
        assertTrue(secret.url.endsWith("/realtime?model=grok-realtime-1"))
    }

    @Test
    fun `the token authenticates as a subprotocol, never as a header`() = runTest {
        val server = TestServer(TestServer.json("""{"value":"cs-abc"}"""))

        val connection = model(server).webSocketConfig("cs-abc", "wss://api.x.ai/v1/realtime")

        assertEquals(listOf("xai-client-secret.cs-abc"), connection.protocols)
        assertEquals("wss://api.x.ai/v1/realtime", connection.url)
    }

    @Test
    fun `server frames map onto the neutral events`() = runTest {
        val server = TestServer(TestServer.json("{}"))
        val subject = model(server)

        assertIs<RealtimeServerEvent.SessionCreated>(
            subject.parseServerEvent(parse("""{"type":"session.created","session":{"id":"s1"}}""")).single(),
        )
        val delta = subject.parseServerEvent(
            parse("""{"type":"response.output_audio.delta","response_id":"r1","item_id":"i1","delta":"AQID"}"""),
        ).single()
        assertEquals("AQID", assertIs<RealtimeServerEvent.AudioDelta>(delta).delta)

        val done = subject.parseServerEvent(
            parse(
                """{"type":"response.function_call_arguments.done","response_id":"r1","item_id":"i1",
                   "call_id":"c1","name":"lookup","arguments":"{}"}""",
            ),
        ).single()
        assertEquals("lookup", assertIs<RealtimeServerEvent.FunctionCallArgumentsDone>(done).name)
    }

    @Test
    fun `an error reads from either the nested or the flat shape`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        val nested = subject.parseServerEvent(
            parse("""{"type":"error","error":{"message":"quota","code":"429"}}"""),
        ).single()
        assertEquals("quota", assertIs<RealtimeServerEvent.Error>(nested).message)

        // Flattened on some frames; a reader that knows only one shape reports "Unknown error" for half.
        val flat = subject.parseServerEvent(parse("""{"type":"error","message":"boom"}""")).single()
        assertEquals("boom", assertIs<RealtimeServerEvent.Error>(flat).message)
    }

    @Test
    fun `an unmodelled frame keeps its payload rather than becoming a synthetic event`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        val event = subject.parseServerEvent(parse("""{"type":"mcp_list_tools.completed"}""")).single()

        assertEquals("mcp_list_tools.completed", assertIs<RealtimeServerEvent.Custom>(event).rawType)
    }

    @Test
    fun `client events serialize into xAI's frame names`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        assertEquals(
            """{"type":"input_audio_buffer.append","audio":"AQID"}""",
            subject.serializeClientEvent(RealtimeClientEvent.InputAudioAppend("AQID")).toString(),
        )
        assertEquals(
            """{"type":"input_audio_buffer.commit"}""",
            subject.serializeClientEvent(RealtimeClientEvent.InputAudioCommit).toString(),
        )
        val item = subject.serializeClientEvent(
            RealtimeClientEvent.ConversationItemCreate(RealtimeConversationItem.TextMessage("hi")),
        )
        assertTrue(item.toString().contains(""""type":"input_text""""))
    }

    @Test
    fun `a truncate xAI has no frame for serializes as null rather than a guess`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        val frame = subject.serializeClientEvent(
            RealtimeClientEvent.ConversationItemTruncate(itemId = "i1", contentIndex = 0, audioEndMs = 100),
        )

        // Null lets a session layer tell "nothing to send" from "the vendor rejected it".
        assertEquals(JsonNull, frame)
    }

    @Test
    fun `push-to-talk is an explicit null, not an omitted key`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        val disabled = subject.buildSessionConfig(
            RealtimeSessionConfig(turnDetection = RealtimeTurnDetection.Disabled),
        ) as JsonObject
        // Omitting the key would take xAI's automatic VAD, which is the opposite of what was asked.
        assertEquals(JsonNull, disabled["turn_detection"])

        val vad = subject.buildSessionConfig(
            RealtimeSessionConfig(
                turnDetection = RealtimeTurnDetection.ServerVad(threshold = 0.4, silenceDurationMs = 500),
            ),
        ) as JsonObject
        val detection = vad["turn_detection"] as JsonObject
        assertEquals("server_vad", detection["type"].string())
        assertEquals("500", detection["silence_duration_ms"].toString())
    }

    @Test
    fun `audio is emitted only when a side of it was configured`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        val bare = subject.buildSessionConfig(RealtimeSessionConfig(instructions = "be brief")) as JsonObject
        // An empty `audio` object would override xAI's defaults with nothing, which is audible.
        assertTrue(!bare.containsKey("audio"))

        val configured = subject.buildSessionConfig(
            RealtimeSessionConfig(inputAudioFormat = AudioFormat("audio/pcm", rate = 24_000)),
        ) as JsonObject
        val input = (configured["audio"] as JsonObject)["input"] as JsonObject
        assertEquals("24000", (input["format"] as JsonObject)["rate"].toString())
    }

    @Test
    fun `vendor tools are appended to the declared ones, not substituted for them`() = runTest {
        val subject = model(TestServer(TestServer.json("{}")))

        val config = subject.buildSessionConfig(
            RealtimeSessionConfig(
                tools = listOf(
                    RealtimeToolDefinition(name = "lookup", parameters = buildJsonObject { }),
                ),
                providerOptions = mapOf(
                    XAI_PROVIDER_ID to buildJsonObject {
                        put("tools", kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject { put("type", "web_search") })
                        })
                        put("temperature", 0.4)
                    },
                ),
            ),
        ) as JsonObject

        // They are different kinds of tool — the caller's own and xAI's server-run ones — so offering
        // both means both.
        assertEquals(2, (config["tools"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("0.4", config["temperature"].toString())
    }

    @Test
    fun `the continuous-session commands have no xAI frame and are refused`() = runTest {
        val model = model(TestServer(TestServer.json("{}")))
        listOf(
            RealtimeClientEvent.SessionStart(RealtimeSessionConfig()),
            RealtimeClientEvent.SessionClose(),
            RealtimeClientEvent.InputAudioMute(),
            RealtimeClientEvent.InputAudioUnmute(),
            RealtimeClientEvent.ContextAppend("context"),
        ).forEach { event ->
            assertFailsWith<UnsupportedFunctionalityError>("$event") { model.serializeClientEvent(event) }
        }
    }
}
