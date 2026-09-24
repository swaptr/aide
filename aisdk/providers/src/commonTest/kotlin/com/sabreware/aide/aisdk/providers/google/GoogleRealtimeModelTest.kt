package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeTranscriptionConfig
import com.sabreware.aide.aisdk.providers.google.GoogleRealtimeFixtures.AUTH_TOKENS_URL
import com.sabreware.aide.aisdk.providers.google.GoogleRealtimeFixtures.SOCKET_PATH
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.int
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini Live as a realtime model, against `realtime/google-realtime-model.test.ts`.
 *
 * Two properties carry the session: the ephemeral token is minted with the session's SETUP baked in,
 * because the constrained bidi endpoint opens only the session the token was minted for; and the token
 * then travels in the socket URL's `access_token` query parameter, which is the only place a browser
 * client can put it.
 */
class GoogleRealtimeModelTest {

    private fun model(
        server: TestServer,
        modelId: String = "gemini-2.0-flash-live-001",
        baseUrl: String = GOOGLE_DEFAULT_BASE_URL,
        apiKey: String = "test-key",
    ) = GoogleRealtimeModel(
        modelId = modelId,
        http = server.http(),
        baseUrl = baseUrl,
        apiKey = apiKey,
        now = { TestServer.FIXED_NOW },
    )

    private fun tokenServer(reply: String = GoogleRealtimeFixtures.TOKEN_REPLY) = TestServer(TestServer.json(reply))

    private fun setupOf(server: TestServer): JsonObject = server.request().bodyJson().obj("bidiGenerateContentSetup")!!

    @Test
    fun `a client secret is minted from auth_tokens with the session baked in`() = runTest {
        val server = tokenServer()

        val secret = model(server).doCreateClientSecret(
            RealtimeClientSecretOptions(
                sessionConfig = RealtimeSessionConfig(instructions = "Be helpful", voice = "Puck"),
            ),
        )

        assertEquals(1, server.callCount)
        val call = server.request()
        assertEquals(AUTH_TOKENS_URL, call.url)
        assertEquals("POST", call.method)
        val body = call.bodyJson()
        // Zero lifts the per-token session limit; the default of one breaks a reconnect mid-session.
        assertEquals(0, body["uses"].int())
        assertTrue(body["expireTime"].string() != null)
        val setup = body.obj("bidiGenerateContentSetup")!!
        assertEquals("models/gemini-2.0-flash-live-001", setup["model"].string())
        assertEquals(parseJsonObject("""{"parts":[{"text":"Be helpful"}]}"""), setup.obj("systemInstruction"))
        assertEquals(
            "Puck",
            setup.obj("generationConfig", "speechConfig", "voiceConfig", "prebuiltVoiceConfig")!!["voiceName"].string(),
        )

        assertEquals("projects/123/locations/us/accessTokens/abc", secret.token)
        assertTrue("generativelanguage.googleapis.com" in secret.url)
        assertEquals(Instant.parse("2026-01-01T00:05:00.000Z").epochSeconds, secret.expiresAt)
    }

    @Test
    fun `expiresAfterSeconds is the window to OPEN a session, and the token outlives it`() = runTest {
        val server = tokenServer(GoogleRealtimeFixtures.BARE_TOKEN_REPLY)

        val secret = model(server).doCreateClientSecret(RealtimeClientSecretOptions(expiresAfterSeconds = 120))

        val body = server.request().bodyJson()
        val newSessionExpires = Instant.parse(body["newSessionExpireTime"].string()!!)
        assertEquals(Instant.fromEpochMilliseconds(TestServer.FIXED_NOW + 120_000L), newSessionExpires)
        // The overall lifetime must outlast the open window so the opened session has room to run.
        assertTrue(Instant.parse(body["expireTime"].string()!!) > newSessionExpires)
        assertNull(secret.expiresAt)
    }

    @Test
    fun `the configured base URL serves both realtime endpoints`() = runTest {
        val server = tokenServer(GoogleRealtimeFixtures.BARE_TOKEN_REPLY)

        val secret = model(server, baseUrl = "https://proxy.example.com/google/v1beta").doCreateClientSecret()

        assertEquals("https://proxy.example.com/google/v1alpha/auth_tokens?key=test-key", server.request().url)
        assertEquals("wss://proxy.example.com/google$SOCKET_PATH", secret.url)
    }

    @Test
    fun `an http base yields a ws socket`() = runTest {
        val server = tokenServer(GoogleRealtimeFixtures.BARE_TOKEN_REPLY)

        val secret = model(server, baseUrl = "http://localhost:8787/v1beta").doCreateClientSecret()

        assertEquals("ws://localhost:8787$SOCKET_PATH", secret.url)
    }

    @Test
    fun `output audio transcription is enabled by an empty object`() = runTest {
        val server = tokenServer(GoogleRealtimeFixtures.BARE_TOKEN_REPLY)

        model(server).doCreateClientSecret(
            RealtimeClientSecretOptions(
                sessionConfig = RealtimeSessionConfig(outputAudioTranscription = RealtimeTranscriptionConfig()),
            ),
        )

        assertEquals(JsonObject(emptyMap()), setupOf(server).obj("outputAudioTranscription"))
    }

    @Test
    fun `Live Translation config lands in generationConfig, and the google namespace does not leak`() = runTest {
        val server = tokenServer(GoogleRealtimeFixtures.BARE_TOKEN_REPLY)

        model(server, modelId = "gemini-3.5-live-translate-preview").doCreateClientSecret(
            RealtimeClientSecretOptions(
                sessionConfig = RealtimeSessionConfig(
                    providerOptions = mapOf(
                        GOOGLE_PROVIDER_ID to buildJsonObject {
                            putJsonObject("translationConfig") {
                                put("targetLanguageCode", "pl")
                                put("echoTargetLanguage", true)
                            }
                        },
                    ),
                ),
            ),
        )

        val setup = setupOf(server)
        assertEquals(
            parseJsonObject("""{"targetLanguageCode":"pl","echoTargetLanguage":true}"""),
            setup.obj("generationConfig", "translationConfig"),
        )
        assertNull(setup["google"])
    }

    @Test
    fun `no key, no token request`() = runTest {
        val server = tokenServer()

        val error = assertFailsWith<InvalidArgumentError> { model(server, apiKey = "").doCreateClientSecret() }

        assertEquals("apiKey", error.argument)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a rejected token request surfaces its status and body`() = runTest {
        val server = TestServer(TestServer.error(403, "Forbidden"))

        val error = assertFailsWith<APICallError> { model(server).doCreateClientSecret() }

        assertEquals(403, error.statusCode)
        assertTrue("403" in error.message.orEmpty() && "Forbidden" in error.message.orEmpty())
    }

    @Test
    fun `the token authenticates in the query string, with no subprotocol`() = runTest {
        val connection = model(tokenServer()).webSocketConfig("my-token", "wss://example.com/ws")

        assertEquals("wss://example.com/ws?access_token=my-token", connection.url)
        assertTrue(connection.protocols.isEmpty())
    }

    @Test
    fun `server frames go through the mapper`() = runTest {
        val raw = parseJsonObject(GoogleRealtimeFixtures.SETUP_COMPLETE)

        assertEquals(listOf(RealtimeServerEvent.SessionCreated(raw)), model(tokenServer()).parseServerEvent(raw))
    }

    @Test
    fun `client events go through the mapper`() = runTest {
        val frame = model(tokenServer()).serializeClientEvent(RealtimeClientEvent.InputAudioAppend("base64"))

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.audioAppend(16_000, "base64")), frame)
    }

    @Test
    fun `input audio is labelled with the capture rate the session declared`() = runTest {
        val subject = model(tokenServer())
        // A mislabelled rate corrupts custom-rate audio rather than rejecting it.
        subject.serializeClientEvent(
            RealtimeClientEvent.SessionUpdate(
                RealtimeSessionConfig(inputAudioFormat = AudioFormat("audio/pcm", rate = 24_000)),
            ),
        )

        val frame = subject.serializeClientEvent(RealtimeClientEvent.InputAudioAppend("base64"))

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.audioAppend(24_000, "base64")), frame)
    }

    @Test
    fun `the provider wires the realtime model with its key`() = runTest {
        val server = tokenServer()

        val secret = GoogleProvider(client = HttpClient(server.engine()), apiKey = "test-api-key")
            .realtimeModel("gemini-2.0-flash-live-001")
            .doCreateClientSecret()

        assertEquals("https://generativelanguage.googleapis.com/v1alpha/auth_tokens?key=test-api-key", server.request().url)
        assertEquals("projects/123/locations/us/accessTokens/abc", secret.token)
        assertEquals("wss://generativelanguage.googleapis.com$SOCKET_PATH", secret.url)
    }
}
