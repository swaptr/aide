package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The credential half of OpenAI's realtime model, pinned against `openai-realtime-model.test.ts`.
 *
 * The secret request is asserted whole — URL, headers and body — because the reference's own test only
 * reads `expires_after`, and a `session` object missing its `type` or `model` is a 400 no per-key read
 * of the ttl can see.
 */
class OpenAIRealtimeModelTest {

    private fun model(server: TestServer) = OpenAIRealtimeModel(
        modelId = "gpt-realtime",
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    )

    private val secretResponse = TestServer.json(OpenAIRealtimeFixtures.CLIENT_SECRET_RESPONSE)

    @Test
    fun `omits expires_after when no ttl is requested`() = runTest {
        val server = TestServer(secretResponse)

        model(server).doCreateClientSecret(RealtimeClientSecretOptions())

        val call = server.request()
        call.assertBodyMissing("expires_after")
        // Without a session the endpoint still needs to know which kind it is minting for.
        call.assertBodyEquals("""{"session":{"type":"realtime","model":"gpt-realtime"}}""")
    }

    @Test
    fun `includes the required anchor with expires_after`() = runTest {
        val server = TestServer(secretResponse)

        model(server).doCreateClientSecret(RealtimeClientSecretOptions(expiresAfterSeconds = 60))

        // The client secrets endpoint 400s without `anchor`.
        assertEquals(
            parseJsonObject(OpenAIRealtimeFixtures.EXPIRES_AFTER_60S),
            server.request().bodyJson().obj("expires_after"),
        )
    }

    @Test
    fun `the secret is posted with the key, as JSON, to the client_secrets path`() = runTest {
        val server = TestServer(secretResponse)

        model(server).doCreateClientSecret()

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/realtime/client_secrets", call.path)
        call.assertHeader("Authorization", "Bearer test-key")
        assertTrue(call.header("Content-Type").orEmpty().startsWith("application/json"))
    }

    @Test
    fun `the token, expiry and socket url come back from the response`() = runTest {
        val server = TestServer(secretResponse)

        val secret = model(server).doCreateClientSecret()

        assertEquals("secret", secret.token)
        assertEquals(123L, secret.expiresAt)
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime", secret.url)
    }

    @Test
    fun `a session config handed over at mint time is baked into the token`() = runTest {
        val server = TestServer(secretResponse)

        model(server).doCreateClientSecret(
            RealtimeClientSecretOptions(
                sessionConfig = RealtimeSessionConfig(instructions = "You are a friendly assistant."),
            ),
        )

        val session = server.request().bodyJson().obj("session")!!
        // What is configured here is what the socket opens with — and the type and model ride along.
        assertEquals("You are a friendly assistant.", session["instructions"].string())
        assertEquals("realtime", session["type"].string())
        assertEquals("gpt-realtime", session["model"].string())
    }

    @Test
    fun `the socket url follows the base and encodes the model`() = runTest {
        val server = TestServer(secretResponse)
        val subject = OpenAIRealtimeModel(
            modelId = "gpt realtime/2",
            client = HttpClient(server.engine()),
            apiKey = "test-key",
            baseUrl = "https://gateway.example/openai/v1/",
        )

        val secret = subject.doCreateClientSecret()

        // A gateway that mounts OpenAI under a path prefix keeps it; the reference would drop it.
        assertEquals("wss://gateway.example/openai/v1/realtime?model=gpt%20realtime%2F2", secret.url)
        assertEquals("openai/v1/realtime/client_secrets", server.request().path)
    }

    @Test
    fun `an expiry the vendor does not send is null rather than zero`() = runTest {
        val server = TestServer(TestServer.json("""{"value":"secret"}"""))

        val secret = model(server).doCreateClientSecret()

        assertNull(secret.expiresAt)
    }

    @Test
    fun `a response without a value is no secret at all`() = runTest {
        val server = TestServer(TestServer.json("""{"expires_at":123}"""))

        assertFailsWith<NoContentGeneratedError> { model(server).doCreateClientSecret() }
    }

    @Test
    fun `the token authenticates as a subprotocol list, never as a header`() = runTest {
        val subject = model(TestServer(secretResponse))

        val connection = subject.webSocketConfig("secret", "wss://api.openai.com/v1/realtime?model=gpt-realtime")

        // `realtime` first — it is the entry the server echoes back as the agreed protocol.
        assertEquals(listOf("realtime", "openai-insecure-api-key.secret"), connection.protocols)
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime", connection.url)
    }

    @Test
    fun `the model reports the openai provider id`() = runTest {
        val subject = model(TestServer(secretResponse))

        assertEquals(OPENAI_PROVIDER_ID, subject.provider)
        assertEquals("gpt-realtime", subject.modelId)
    }
}
