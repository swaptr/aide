package com.sabreware.aide.aisdk.providers.cartesia

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeClientSecret
import com.sabreware.aide.aisdk.RealtimeClientSecretOptions
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeTranscriptionConfig
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.cartesia.CartesiaRealtimeModelFixtures as Fixtures
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Cartesia Ink 2 on the realtime contract, pinned against `cartesia-realtime-model.test.ts`.
 *
 * Three properties carry the session, and each has a test that would fail if it were done the other
 * way: the token travels in the QUERY STRING (the one field a browser's socket can populate), the
 * session is the URL minted with the token (there is no session frame), and turn detection is an
 * endpoint rather than a flag — which is why what a commit serializes to depends on which URL the
 * socket was opened against.
 */
class CartesiaRealtimeModelTest {

    private fun provider(server: TestServer) = CartesiaProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    /** The reference's `createModel`: the same headers, and the same pinned clock. */
    private fun model(server: TestServer = TestServer(TestServer.json("{}"))) = CartesiaRealtimeModel(
        modelId = "ink-2",
        http = server.http().withErrorStructure(CartesiaErrors),
        baseUrl = CartesiaProvider.DEFAULT_BASE_URL,
        apiVersion = "2026-03-01",
        headers = mapOf("Authorization" to "Bearer test-key", "Cartesia-Version" to "2026-03-01"),
        now = { Fixtures.NOW_MILLIS },
    )

    private fun tokenServer() = TestServer(TestServer.json(Fixtures.TOKEN_RESPONSE))

    private fun parse(json: String) = parseJsonObject(json)

    /** A model whose socket was opened against the turns endpoint — the reference's first `getWebSocketConfig`. */
    private fun turnsModel() = model().also {
        it.webSocketConfig("token", "wss://api.cartesia.ai/stt/turns/websocket?model=ink-2")
    }

    /** A model whose socket was opened against the plain, manually finalized endpoint. */
    private fun manualModel() = model().also {
        it.webSocketConfig("token", "wss://api.cartesia.ai/stt/websocket?model=ink-2")
    }

    // --- Client secret ---------------------------------------------------------------------------

    @Test
    fun `creates an Ink 2 auto-finalize client secret`() = runTest {
        val server = tokenServer()

        val result = model(server).doCreateClientSecret(
            RealtimeClientSecretOptions(
                expiresAfterSeconds = 60,
                sessionConfig = RealtimeSessionConfig(
                    inputAudioFormat = AudioFormat("audio/pcm", rate = 16_000),
                    inputAudioTranscription = RealtimeTranscriptionConfig(language = "en"),
                    turnDetection = RealtimeTurnDetection.ServerVad(),
                ),
            ),
        )

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("https://api.cartesia.ai/access-token", call.url)
        call.assertHeader("Authorization", "Bearer test-key")
        call.assertHeader("Cartesia-Version", "2026-03-01")
        assertTrue(call.header("Content-Type").orEmpty().startsWith("application/json"), call.headers.toString())
        call.assertBodyEquals("""{"grants":{"stt":true},"expires_in":60}""")
        assertEquals(
            RealtimeClientSecret(token = "access-token", url = Fixtures.TURNS_URL, expiresAt = Fixtures.EXPIRES_AT),
            result,
        )
    }

    @Test
    fun `uses the manual-finalize endpoint when turn detection is disabled`() = runTest {
        val server = tokenServer()

        val result = model(server).doCreateClientSecret(
            RealtimeClientSecretOptions(
                sessionConfig = RealtimeSessionConfig(
                    inputAudioFormat = AudioFormat("audio/pcmu", rate = 8_000),
                    inputAudioTranscription = RealtimeTranscriptionConfig(language = "en"),
                    turnDetection = RealtimeTurnDetection.Disabled,
                ),
            ),
        )

        // The language rides the plain endpoint only; the turns one infers it and rejects the parameter.
        assertEquals(Fixtures.MANUAL_URL, result.url)
        // No lifetime asked for: none sent, and none reported — the vendor's default is chosen by not asking.
        assertNull(result.expiresAt)
        server.request().assertBodyEquals("""{"grants":{"stt":true}}""")
    }

    @Test
    fun `an unconfigured session takes the turns endpoint at 24 kHz PCM`() = runTest {
        val result = model(tokenServer()).doCreateClientSecret()

        assertEquals(
            "wss://api.cartesia.ai/stt/turns/websocket?model=ink-2&encoding=pcm_s16le&sample_rate=24000" +
                "&cartesia_version=2026-03-01",
            result.url,
        )
    }

    @Test
    fun `rejects unsupported languages and token lifetimes`() = runTest {
        val server = tokenServer()
        val subject = model(server)

        val language = assertFailsWith<InvalidArgumentError> {
            subject.doCreateClientSecret(
                RealtimeClientSecretOptions(
                    sessionConfig = RealtimeSessionConfig(
                        inputAudioTranscription = RealtimeTranscriptionConfig(language = "es"),
                    ),
                ),
            )
        }
        assertContains(language.message.orEmpty(), "currently supports English only")

        val lifetime = assertFailsWith<InvalidArgumentError> {
            subject.doCreateClientSecret(RealtimeClientSecretOptions(expiresAfterSeconds = 3601))
        }
        assertContains(lifetime.message.orEmpty(), "between 1 and 3600 seconds")
        assertFailsWith<InvalidArgumentError> {
            subject.doCreateClientSecret(RealtimeClientSecretOptions(expiresAfterSeconds = 0))
        }

        // Refused before anything was minted: a credential for a session that never opens is a leak.
        assertEquals(0, server.callCount)
    }

    @Test
    fun `an unsupported audio format is refused before a token is minted`() = runTest {
        val server = tokenServer()

        val error = assertFailsWith<InvalidArgumentError> {
            model(server).doCreateClientSecret(
                RealtimeClientSecretOptions(
                    sessionConfig = RealtimeSessionConfig(inputAudioFormat = AudioFormat("audio/mpeg")),
                ),
            )
        }

        assertContains(error.message.orEmpty(), "audio/mpeg")
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a reply without a token is no content, not a blank credential`() = runTest {
        assertFailsWith<NoContentGeneratedError> {
            model(TestServer(TestServer.json("""{"token":""}"""))).doCreateClientSecret()
        }
        assertFailsWith<NoContentGeneratedError> {
            model(TestServer(TestServer.json("{}"))).doCreateClientSecret()
        }
    }

    @Test
    fun `a rejected mint reads Cartesia's own error shape`() = runTest {
        val server = TestServer(
            TestServer.error(
                401,
                """{"error_code":"unauthorized","title":"Unauthorized","message":"Invalid API key","request_id":"r-1"}""",
            ),
        )

        val error = assertFailsWith<APICallError> { model(server).doCreateClientSecret() }

        // Title and message, not message alone — the default reader drops the half that names the fault.
        assertContains(error.message.orEmpty(), "Unauthorized: Invalid API key")
    }

    @Test
    fun `the provider mints with its own headers and reports its id`() = runTest {
        val server = tokenServer()

        val subject = provider(server).realtimeModel("ink-2")
        val secret = subject.doCreateClientSecret()

        assertEquals(CARTESIA_PROVIDER_ID, subject.provider)
        assertEquals("ink-2", subject.modelId)
        assertEquals("access-token", secret.token)
        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Cartesia-Version", CartesiaProvider.DEFAULT_API_VERSION)
        assertContains(secret.url, "cartesia_version=${CartesiaProvider.DEFAULT_API_VERSION}")
    }

    // --- Opening the socket ----------------------------------------------------------------------

    @Test
    fun `adds the access token to the WebSocket URL`() {
        val connection = model().webSocketConfig(
            "access-token",
            "wss://api.cartesia.ai/stt/turns/websocket?model=ink-2",
        )

        assertEquals(
            "wss://api.cartesia.ai/stt/turns/websocket?model=ink-2&access_token=access-token",
            connection.url,
        )
        // Query string, not a header and not a subprotocol: the one field a browser can populate.
        assertEquals(emptyList(), connection.protocols)
    }

    @Test
    fun `a token already on the URL is replaced, and the new one is encoded`() {
        assertEquals(
            "wss://api.cartesia.ai/stt/websocket?model=ink-2&access_token=fresh",
            model().webSocketConfig("fresh", "wss://api.cartesia.ai/stt/websocket?model=ink-2&access_token=stale").url,
        )
        assertEquals(
            "wss://api.cartesia.ai/stt/websocket?access_token=t%2B1",
            model().webSocketConfig("t+1", "wss://api.cartesia.ai/stt/websocket").url,
        )
    }

    // --- Client events ---------------------------------------------------------------------------

    @Test
    fun `sends audio as binary and lets auto-finalize detect turns`() {
        val subject = turnsModel()

        val audio = assertIs<CartesiaClientFrame.Audio>(
            subject.clientFrame(RealtimeClientEvent.InputAudioAppend("AQID")),
        )
        assertContentEquals(byteArrayOf(1, 2, 3), audio.bytes)
        assertEquals(CartesiaClientFrame.None, subject.clientFrame(RealtimeClientEvent.InputAudioCommit))
        assertEquals(
            CartesiaClientFrame.None,
            subject.clientFrame(RealtimeClientEvent.SessionUpdate(RealtimeSessionConfig())),
        )
        assertEquals(CartesiaClientFrame.None, subject.clientFrame(RealtimeClientEvent.InputAudioClear))
        assertEquals(CartesiaClientFrame.None, subject.clientFrame(RealtimeClientEvent.ResponseCreate()))
    }

    @Test
    fun `sends finalize for manual turn detection`() {
        val subject = manualModel()

        assertEquals(CartesiaClientFrame.Text("finalize"), subject.clientFrame(RealtimeClientEvent.InputAudioCommit))
        assertEquals(JsonPrimitive("finalize"), subject.serializeClientEvent(RealtimeClientEvent.InputAudioCommit))
    }

    @Test
    fun `a fresh model is on the turns endpoint until the socket says otherwise`() {
        assertEquals(CartesiaClientFrame.None, model().clientFrame(RealtimeClientEvent.InputAudioCommit))
    }

    @Test
    fun `the neutral rendering carries audio as base64 and nothing as null`() {
        val subject = turnsModel()

        // A JsonElement cannot carry bytes: the base64 is the carrier, and the frame on the wire is binary.
        assertEquals(JsonPrimitive("AQID"), subject.serializeClientEvent(RealtimeClientEvent.InputAudioAppend("AQID")))
        assertEquals(JsonNull, subject.serializeClientEvent(RealtimeClientEvent.InputAudioCommit))
        assertEquals(JsonNull, subject.serializeClientEvent(RealtimeClientEvent.SessionUpdate(RealtimeSessionConfig())))
    }

    @Test
    fun `conversation events are refused, not silently dropped`() {
        val subject = turnsModel()

        val error = assertFailsWith<UnsupportedFunctionalityError> {
            subject.serializeClientEvent(
                RealtimeClientEvent.ConversationItemCreate(RealtimeConversationItem.TextMessage("hi")),
            )
        }
        assertContains(error.message.orEmpty(), """does not support realtime client event "conversation-item-create"""")
        assertFailsWith<UnsupportedFunctionalityError> {
            subject.serializeClientEvent(RealtimeClientEvent.ConversationItemTruncate("i1", 0, 100))
        }
        assertFailsWith<UnsupportedFunctionalityError> {
            subject.serializeClientEvent(RealtimeClientEvent.ResponseCancel)
        }
    }

    @Test
    fun `there is no session payload, the URL is the configuration`() {
        assertEquals(JsonNull, model().buildSessionConfig(RealtimeSessionConfig(instructions = "ignored")))
        // Neither endpoint pings.
        assertNull(model().healthCheckResponse(parse(Fixtures.CONNECTED)))
    }

    // --- Server events ---------------------------------------------------------------------------

    @Test
    fun `maps Ink 2 turn events to normalized realtime events`() {
        val subject = model()
        val connected = parse(Fixtures.CONNECTED)
        val turnStart = parse(Fixtures.TURN_START)
        val turnEnd = parse(Fixtures.TURN_END)

        assertEquals(
            listOf(RealtimeServerEvent.SessionCreated(raw = connected, sessionId = "request-1")),
            subject.parseServerEvent(connected),
        )
        assertEquals(
            listOf(RealtimeServerEvent.SpeechStarted(raw = turnStart, itemId = "request-1")),
            subject.parseServerEvent(turnStart),
        )
        assertEquals(
            listOf(
                RealtimeServerEvent.SpeechStopped(raw = turnEnd, itemId = "request-1"),
                RealtimeServerEvent.InputTranscriptionCompleted(
                    raw = turnEnd,
                    itemId = "request-1",
                    transcript = "Hello world",
                ),
            ),
            subject.parseServerEvent(turnEnd),
        )
    }

    @Test
    fun `assembles manual transcript chunks when a flush completes`() {
        val subject = model()
        val first = parse(Fixtures.TRANSCRIPT_FIRST)
        val second = parse(Fixtures.TRANSCRIPT_SECOND)
        val flush = parse(Fixtures.FLUSH_DONE)

        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = first, sessionId = "request-1"),
                RealtimeServerEvent.Custom(raw = first, rawType = "transcript"),
            ),
            subject.parseServerEvent(first),
        )
        subject.parseServerEvent(second)
        assertEquals(
            listOf(
                RealtimeServerEvent.AudioCommitted(raw = flush, itemId = "request-1"),
                RealtimeServerEvent.InputTranscriptionCompleted(
                    raw = parse(
                        """{"event":${Fixtures.FLUSH_DONE},""" +
                            """"transcriptEvents":[${Fixtures.TRANSCRIPT_FIRST},${Fixtures.TRANSCRIPT_SECOND}],""" +
                            """"duration":0.9}""",
                    ),
                    itemId = "request-1",
                    transcript = "Hello world",
                ),
            ),
            subject.parseServerEvent(flush),
        )
    }

    @Test
    fun `a flush settles the transcript once and the next starts empty`() {
        val subject = model()
        subject.parseServerEvent(parse(Fixtures.TRANSCRIPT_FIRST))
        val flush = parse(Fixtures.FLUSH_DONE)

        assertEquals(2, subject.parseServerEvent(flush).size)
        // Nothing accumulated since: one custom event, and no phantom second transcript.
        assertEquals(listOf(RealtimeServerEvent.Custom(raw = flush, rawType = "flush_done")), subject.parseServerEvent(flush))
    }

    @Test
    fun `a partial transcript is forwarded but never accumulated`() {
        val subject = model()
        val partial = parse("""{"type":"transcript","request_id":"request-1","text":"Hel","is_final":false}""")

        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = partial, sessionId = "request-1"),
                RealtimeServerEvent.Custom(raw = partial, rawType = "transcript"),
            ),
            subject.parseServerEvent(partial),
        )
        val flush = parse(Fixtures.FLUSH_DONE)
        assertEquals(listOf(RealtimeServerEvent.Custom(raw = flush, rawType = "flush_done")), subject.parseServerEvent(flush))
    }

    @Test
    fun `emits one custom event when a manual stream closes without text`() {
        val done = parse(Fixtures.DONE)

        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = done, sessionId = "request-1"),
                RealtimeServerEvent.Custom(raw = done, rawType = "done"),
            ),
            model().parseServerEvent(done),
        )
    }

    @Test
    fun `a close with text still pending settles it first`() {
        val subject = model()
        subject.parseServerEvent(parse(Fixtures.TRANSCRIPT_FIRST))
        val done = parse(Fixtures.DONE)

        val events = subject.parseServerEvent(done)

        assertEquals(3, events.size)
        assertIs<RealtimeServerEvent.AudioCommitted>(events[0])
        assertEquals("Hello ", assertIs<RealtimeServerEvent.InputTranscriptionCompleted>(events[1]).transcript)
        assertEquals("done", assertIs<RealtimeServerEvent.Custom>(events[2]).rawType)
    }

    @Test
    fun `maps structured Cartesia errors`() {
        val error = parse(Fixtures.ERROR)

        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = error, sessionId = "request-1"),
                RealtimeServerEvent.Error(raw = error, message = "Invalid model", code = "model_not_found"),
            ),
            model().parseServerEvent(error),
        )
    }

    @Test
    fun `an error without a message still names the vendor`() {
        val error = parse("""{"type":"error","request_id":"request-1"}""")

        assertEquals(
            RealtimeServerEvent.Error(raw = error, message = "Unknown Cartesia realtime error"),
            model().parseServerEvent(error).last(),
        )
    }

    @Test
    fun `a resume is speech again, plus the frame that says so`() {
        val subject = model()
        subject.parseServerEvent(parse(Fixtures.CONNECTED))
        val resume = parse("""{"type":"turn.resume","request_id":"request-1"}""")

        assertEquals(
            listOf(
                RealtimeServerEvent.SpeechStarted(raw = resume, itemId = "request-1"),
                RealtimeServerEvent.Custom(raw = resume, rawType = "turn.resume"),
            ),
            subject.parseServerEvent(resume),
        )
    }

    @Test
    fun `a frame without a request id is correlated under a fixed one`() {
        val start = parse("""{"type":"turn.start"}""")

        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = start, sessionId = "cartesia-realtime"),
                RealtimeServerEvent.SpeechStarted(raw = start, itemId = "cartesia-realtime"),
            ),
            model().parseServerEvent(start),
        )
    }

    @Test
    fun `a session is announced once, by whichever frame comes first`() {
        val subject = model()
        val update = parse("""{"type":"turn.update","request_id":"request-1","transcript":"Hel"}""")

        // Only the turns endpoint says `connected`; on the plain one the first frame of any kind is what
        // stands in for "the session exists" — and a revision in progress has no neutral event, so it
        // is forwarded whole for a caller to read the text off.
        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = update, sessionId = "request-1"),
                RealtimeServerEvent.Custom(raw = update, rawType = "turn.update"),
            ),
            subject.parseServerEvent(update),
        )
        assertEquals(listOf(RealtimeServerEvent.Custom(raw = update, rawType = "turn.update")), subject.parseServerEvent(update))

        // A `connected` after that is reported as the vendor sent it, never suppressed.
        val connected = parse(Fixtures.CONNECTED)
        assertEquals(
            listOf(RealtimeServerEvent.SessionCreated(raw = connected, sessionId = "request-1")),
            subject.parseServerEvent(connected),
        )
    }

    @Test
    fun `a frame that is not an object is forwarded whole`() {
        val frame = JsonPrimitive("ping")

        assertEquals(
            listOf(
                RealtimeServerEvent.SessionCreated(raw = frame, sessionId = "cartesia-realtime"),
                RealtimeServerEvent.Custom(raw = frame, rawType = ""),
            ),
            model().parseServerEvent(frame),
        )
    }
}
