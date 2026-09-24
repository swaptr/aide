package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.RealtimeCapabilities
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeToolDefinition
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.testing.TestServer
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The Live model, against `openai-realtime-model-live.test.ts` and `openai-live-session-config.test.ts`
 * (the WebSocket half; the contract has no WebRTC hook).
 *
 * What these pin is the startup payload and its validation: the settings are immutable once sent, so
 * every refusal below happens before a frame goes out.
 */
class OpenAILiveModelTest {

    private val model = OpenAILiveModel(modelId = "gpt-live-1", apiKey = "test-key")

    private fun session(config: RealtimeSessionConfig): JsonObject = model.buildSessionConfig(config) as JsonObject

    private fun openai(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        RealtimeSessionConfig(providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject(build)))

    private val pcm24 = AudioFormat("audio/pcm", 24_000)

    @Test
    fun `declares a continuous conversation on a server-owned socket`() {
        assertEquals("gpt-live-1", model.modelId)
        assertEquals(OPENAI_PROVIDER_ID, model.provider)
        assertEquals(
            RealtimeCapabilities(
                conversation = RealtimeCapabilities.Conversation.Continuous,
                transports = listOf(RealtimeCapabilities.Transport.WebSocket),
                connections = listOf(RealtimeCapabilities.Connection.ServerWebSocket),
                startup = RealtimeCapabilities.Startup.SessionStart,
                finalization = RealtimeCapabilities.Finalization.SessionClose,
            ),
            model.capabilities,
        )
    }

    @Test
    fun `the server socket is authenticated with the key and names no model in the URL`() = runTest {
        val config = model.serverWebSocketConfig()

        assertEquals("wss://api.openai.com/v1/live/sessions", config.url)
        assertEquals("Bearer test-key", config.headers["Authorization"])
    }

    @Test
    fun `a custom base path and headers survive, and http maps to ws`() = runTest {
        val custom = OpenAILiveModel(
            modelId = "gpt-live-1",
            apiKey = "test-key",
            baseUrl = "https://example.com/proxy/v1/",
            extraHeaders = mapOf("OpenAI-Organization" to "org-test", "X-Custom" to "value"),
        ).serverWebSocketConfig()
        assertEquals("wss://example.com/proxy/v1/live/sessions", custom.url)
        assertEquals("org-test", custom.headers["OpenAI-Organization"])
        assertEquals("value", custom.headers["X-Custom"])

        assertEquals(
            "ws://localhost:3000/v1/live/sessions",
            OpenAILiveModel("gpt-live-1", apiKey = "k", baseUrl = "http://localhost:3000/v1").serverWebSocketConfig().url,
        )
    }

    @Test
    fun `the provider routes gpt-live-1 here and keeps gpt-realtime on the Realtime model`() {
        val provider = OpenAIProvider(HttpClient(TestServer(TestServer.json("{}")).engine()), "test-key")

        assertIs<OpenAILiveModel>(provider.realtimeModel("gpt-live-1"))
        assertIs<OpenAIRealtimeModel>(provider.realtimeModel("gpt-realtime"))
        assertIs<OpenAILiveModel>(provider.realtimeModel("not-yet-released", OpenAIRealtimeApi.Live))
        assertIs<OpenAIRealtimeModel>(provider.realtimeModel("gpt-live-1", OpenAIRealtimeApi.Realtime))
    }

    @Test
    fun `builds the documented session start payload`() {
        val frame = model.serializeClientEvent(
            RealtimeClientEvent.SessionStart(
                config = RealtimeSessionConfig(
                    instructions = "Be concise.",
                    voice = "marin",
                    providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { putJsonObject("delegation") { put("type", "client") } }),
                ),
                eventId = "start-1",
            ),
        )

        assertEquals(
            parseJsonObject(
                """{"type":"session.start","event_id":"start-1","session":{"model":"gpt-live-1","instructions":"Be concise.",""" +
                    """"audio":{"format":{"type":"audio/pcm","rate":24000},"output":{"voice":"marin"}},""" +
                    """"delegation":{"type":"client"}}}""",
            ),
            frame,
        )
    }

    @Test
    fun `startup history, client delegation, storage and a custom voice go out as given`() {
        val input = buildJsonArray {
            add(parseJsonObject("""{"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}"""))
            add(parseJsonObject("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hi"}]}"""))
        }

        val config = session(
            openai {
                putJsonObject("delegation") { put("type", "client") }
                put("input", input)
                put("store", true)
                putJsonObject("voice") { put("id", "voice-test") }
            },
        )

        assertEquals(
            buildJsonObject {
                put("model", "gpt-live-1")
                put("audio", parseJsonObject("""{"format":{"type":"audio/pcm","rate":24000},"output":{"voice":{"id":"voice-test"}}}"""))
                put("delegation", parseJsonObject("""{"type":"client"}"""))
                put("input", input)
                put("store", true)
            },
            config,
        )
        // `delegation: null` is a statement, kept; so is `store: false`.
        val nulled = session(openai { put("delegation", JsonNull); put("store", false) })
        assertEquals(JsonNull, nulled["delegation"])
        assertEquals(false, nulled["store"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toBoolean() })
    }

    @Test
    fun `the shared audio formats are the three families at their rates, and one format serves both ways`() {
        listOf(
            AudioFormat("audio/pcm", 16_000),
            AudioFormat("audio/pcm", 24_000),
            AudioFormat("audio/pcmu", 8_000),
            AudioFormat("audio/pcma", 8_000),
        ).forEach { format ->
            val expected = buildJsonObject {
                put("type", format.type)
                put("rate", format.rate)
            }
            assertEquals(expected, session(RealtimeSessionConfig(inputAudioFormat = format, outputAudioFormat = format)).audioFormat())
            assertEquals(expected, session(RealtimeSessionConfig(outputAudioFormat = format)).audioFormat())
        }
    }

    @Test
    fun `mismatched formats, unsupported formats and an ambiguous voice are refused`() {
        assertFailsWith<InvalidArgumentError> {
            session(RealtimeSessionConfig(inputAudioFormat = pcm24, outputAudioFormat = AudioFormat("audio/pcm", 16_000)))
        }
        assertFailsWith<InvalidArgumentError> { session(RealtimeSessionConfig(inputAudioFormat = AudioFormat("audio/pcm", 48_000))) }
        assertFailsWith<InvalidArgumentError> { session(RealtimeSessionConfig(inputAudioFormat = AudioFormat("audio/mp3", null))) }
        assertFailsWith<InvalidArgumentError> {
            session(RealtimeSessionConfig(voice = "marin", providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { putJsonObject("voice") { put("id", "voice-test") } })))
        }
    }

    @Test
    fun `malformed startup options are refused before any frame`() {
        listOf<kotlinx.serialization.json.JsonObjectBuilder.() -> Unit>(
            { put("input", buildJsonArray { add(parseJsonObject("""{"type":"message","role":"system","content":[{"type":"input_text","text":"Hello"}]}""")) }) },
            { put("input", buildJsonArray { add(parseJsonObject("""{"type":"message","role":"user","content":[{"type":"input_image","image_url":"https://example.com/image.png"}]}""")) }) },
            { put("store", "yes") },
            { put("unknown", true) },
            { putJsonObject("delegation") { put("type", "client"); putJsonObject("client") {} } },
        ).forEach { build ->
            assertFailsWith<InvalidArgumentError> { session(openai(build)) }
        }
    }

    @Test
    fun `Responses delegation and WebRTC-only client permissions are refused as unsupported`() {
        assertFailsWith<UnsupportedFunctionalityError> {
            session(openai { putJsonObject("delegation") { put("type", "responses") } })
        }
        val error = assertFailsWith<UnsupportedFunctionalityError> {
            model.serializeClientEvent(
                RealtimeClientEvent.SessionStart(openai { putJsonObject("delegation") { put("type", "responses"); putJsonObject("responses") { put("model", "test-model") } } }),
            )
        }
        assertTrue("only client delegation is supported" in error.message.orEmpty())
        assertFailsWith<UnsupportedFunctionalityError> {
            session(openai { putJsonObject("client") { putJsonObject("dataChannel") {} } })
        }
    }

    @Test
    fun `turn-based session settings have no Live counterpart and are refused`() {
        assertFailsWith<UnsupportedFunctionalityError> { session(RealtimeSessionConfig(turnDetection = RealtimeTurnDetection.Disabled)) }
        assertFailsWith<UnsupportedFunctionalityError> { session(RealtimeSessionConfig(tools = emptyList<RealtimeToolDefinition>())) }
    }

    @Test
    fun `the continuous-session commands serialize with their event ids`() {
        listOf(
            RealtimeClientEvent.SessionClose(eventId = "close-1") to """{"type":"session.close","event_id":"close-1"}""",
            RealtimeClientEvent.InputAudioAppend(audio = "AAAA") to """{"type":"session.input_audio.append","audio":"AAAA"}""",
            RealtimeClientEvent.InputAudioMute(eventId = "mute-1") to """{"type":"session.input_audio.mute","event_id":"mute-1"}""",
            RealtimeClientEvent.InputAudioUnmute() to """{"type":"session.input_audio.unmute"}""",
        ).forEach { (event, expected) ->
            assertEquals(parseJsonObject(expected), model.serializeClientEvent(event), "$event")
        }
    }

    @Test
    fun `context lands on the named channel, thinking by default, with a null or opaque delegation id`() {
        listOf("instructions", "thinking", "commentary").forEach { channel ->
            listOf(null, "opaque-delegation").forEach { delegationId ->
                assertEquals(
                    parseJsonObject(
                        """{"type":"session.$channel.append","content":"Context",""" +
                            """"delegation_id":${delegationId?.let { "\"$it\"" } ?: "null"},"event_id":"append-1"}""",
                    ),
                    model.serializeClientEvent(
                        RealtimeClientEvent.ContextAppend(
                            content = "Context",
                            delegationId = delegationId,
                            providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("channel", channel) }),
                            eventId = "append-1",
                        ),
                    ),
                )
            }
        }
        assertEquals(
            parseJsonObject("""{"type":"session.thinking.append","content":"","delegation_id":null}"""),
            model.serializeClientEvent(RealtimeClientEvent.ContextAppend(content = "")),
        )
        assertFailsWith<InvalidArgumentError> {
            model.serializeClientEvent(
                RealtimeClientEvent.ContextAppend(
                    content = "context",
                    providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("channel", "invalid") }),
                ),
            )
        }
    }

    @Test
    fun `every session update and every turn-based command is refused before a send`() {
        listOf(
            RealtimeSessionConfig(),
            RealtimeSessionConfig(instructions = "new instructions"),
            openai { putJsonObject("delegation") { put("type", "client") } },
            openai { put("delegation", JsonNull) },
            openai { putJsonObject("delegation") { put("type", "responses") } },
        ).forEach { config ->
            val error = assertFailsWith<UnsupportedFunctionalityError> {
                model.serializeClientEvent(RealtimeClientEvent.SessionUpdate(config))
            }
            assertTrue("startup settings are immutable; use context-append" in error.message.orEmpty())
        }
        listOf(
            RealtimeClientEvent.InputAudioCommit,
            RealtimeClientEvent.InputAudioClear,
            RealtimeClientEvent.ResponseCreate(),
            RealtimeClientEvent.ResponseCancel,
            RealtimeClientEvent.ConversationItemTruncate("item-1", 0, 100),
        ).forEach { event ->
            assertFailsWith<UnsupportedFunctionalityError>("$event") { model.serializeClientEvent(event) }
        }
    }

    @Test
    fun `there is no client secret to mint and no browser socket to configure`() = runTest {
        assertFailsWith<UnsupportedFunctionalityError> { model.doCreateClientSecret(RealtimeClientSecretOptions()) }
        assertFailsWith<UnsupportedFunctionalityError> { model.webSocketConfig("token", "wss://x") }
    }

    private fun JsonObject.audioFormat(): JsonObject = (this["audio"] as JsonObject)["format"] as JsonObject
}
