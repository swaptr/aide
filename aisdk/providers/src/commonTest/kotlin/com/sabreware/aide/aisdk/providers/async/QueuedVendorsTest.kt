package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.assemblyai.AssemblyAiProvider
import com.sabreware.aide.aisdk.providers.kling.KlingProvider
import com.sabreware.aide.aisdk.providers.luma.LumaProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.Jwt
import com.sabreware.aide.aisdk.util.PollPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The remaining queue-based vendors, over the shared polling machine.
 *
 * None of them implements polling. What each has to describe is its own status vocabulary and JSON
 * paths — and each of those vocabularies contains at least one word that a client written against a
 * "standard" set gets wrong, silently.
 */
@OptIn(ExperimentalEncodingApi::class)
class QueuedVendorsTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(routes: List<Pair<String, String>>): HttpClient {
        var index = 0
        return HttpClient(
            MockEngine { request ->
                requests += request
                val (contentType, body) = routes[index.coerceAtMost(routes.lastIndex)]
                index++
                respond(content = body, headers = headersOf(HttpHeaders.ContentType, contentType))
            },
        )
    }

    private val json = "application/json"

    // --- AssemblyAI -----------------------------------------------------------------------------

    private fun TestScope.assemblyAi(server: TestServer) = AssemblyAiProvider(
        client = HttpClient(server.engine()),
        apiKey = "k",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    /** Words, in milliseconds — the shape AssemblyAI actually returns and the one `segments` is built from. */
    private val completed = """
        {"status":"completed","text":"hello there","language_code":"en","audio_duration":2.5,
         "words":[{"start":0,"end":1000,"text":"hello"},{"start":1000,"end":2500,"text":"there"}]}
    """.trimIndent()

    @Test
    fun `AssemblyAI uploads first, then references the returned URL in the job`() = runTest {
        val server = TestServer(
            TestServer.json("""{"upload_url":"https://cdn.assemblyai.com/upload/abc"}"""),
            TestServer.json("""{"id":"t1","status":"queued"}"""),
            TestServer.json("""{"status":"processing"}"""),
            TestServer.json(completed),
        )

        val result = assemblyAi(server).transcriptionModel("best")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes("AUDIO".encodeToByteArray()), "audio/wav"))

        // Three steps, not two: modelling this as a single submit-and-poll gets a 400 about a missing
        // audio_url.
        assertEquals("v2/upload", server.request(0).path)
        assertEquals("v2/transcript", server.request(1).path)
        server.request(1).assertBodyJson {
            assertEquals("https://cdn.assemblyai.com/upload/abc", it["audio_url"].string())
        }
        assertEquals("hello there", result.text)
        assertEquals("en", result.language)
    }

    @Test
    fun `AssemblyAI segment times are converted from milliseconds to seconds`() = runTest {
        val server = TestServer(
            TestServer.json("""{"upload_url":"https://cdn.assemblyai.com/u"}"""),
            TestServer.json("""{"id":"t1"}"""),
            TestServer.json(completed),
        )

        val result = assemblyAi(server).transcriptionModel("best")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        // AssemblyAI reports milliseconds where the contract is seconds. Passing them through yields
        // timings a thousand times too long, which reads as a hung player rather than a unit bug.
        assertEquals(0.0, result.segments.first().startSecond)
        assertEquals(1.0, result.segments.first().endSecond)
        assertEquals(2.5, result.segments.last().endSecond)
    }

    @Test
    fun `AssemblyAI sends a bare key with no auth scheme`() = runTest {
        val server = TestServer(
            TestServer.json("""{"upload_url":"https://cdn.assemblyai.com/u"}"""),
            TestServer.json("""{"id":"t1"}"""),
            TestServer.json(completed),
        )

        assemblyAi(server).transcriptionModel("best")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        // Adding "Bearer" here is a 401 that reads as an invalid key.
        server.request(0).assertHeader("Authorization", "k")
    }

    @Test
    fun `only best goes as speech_model, because every other model is rejected there`() = runTest {
        val server = TestServer(
            TestServer.json("""{"upload_url":"https://cdn.assemblyai.com/u"}"""),
            TestServer.json("""{"id":"t1"}"""),
            TestServer.json(completed),
        )

        val result = assemblyAi(server).transcriptionModel("best")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        val job = server.request(1)
        job.assertBodyKeys("speech_model", "audio_url")
        job.assertBodyJson { assertEquals("best", it["speech_model"].string()) }
        val deprecation = result.warnings.filterIsInstance<Warning.Deprecated>().single()
        assertEquals("model 'best'", deprecation.setting)
        assertTrue(deprecation.message.contains("universal-3-5-pro"), deprecation.message)
    }

    @Test
    fun `every other model goes as the speech_models array`() = runTest {
        val server = TestServer(
            TestServer.json("""{"upload_url":"https://cdn.assemblyai.com/u"}"""),
            TestServer.json("""{"id":"t1"}"""),
            TestServer.json(completed),
        )

        val result = assemblyAi(server).transcriptionModel("universal-3-5-pro")
            .doGenerate(TranscriptionCallOptions(BinaryData.Bytes(ByteArray(1)), "audio/wav"))

        // Sending the singular unconditionally, which is what this used to do, meant AssemblyAI worked
        // for exactly one model and 400'd for every other — and all three tests here passed "best",
        // so nothing could see it.
        val job = server.request(1)
        job.assertBodyKeys("speech_models", "audio_url")
        job.assertBodyJson {
            assertEquals(listOf("universal-3-5-pro"), it.arr("speech_models")!!.map { m -> m.string() })
        }
        // No deprecation and no nudge for the current flagship.
        assertEquals(emptyList(), result.warnings)
    }

    // --- Luma -----------------------------------------------------------------------------------

    @Test
    fun `Luma treats dreaming as in-progress, not as a failure`() = runTest {
        val provider = LumaProvider(
            client = client(
                listOf(
                    json to """{"id":"g1","state":"queued"}""",
                    json to """{"state":"dreaming"}""",
                    json to """{"state":"dreaming"}""",
                    json to """{"state":"completed","assets":{"image":"https://cdn/i.png"}}""",
                    "image/png" to "IMG",
                ),
            ),
            apiKey = "k",
            pollPolicy = PollPolicy.Fast,
            elapsedMillis = { currentTime },
        )

        val result = provider.imageModel("photon-1").doGenerate(ImageCallOptions(prompt = "a cat"))

        // "dreaming" is Luma's "processing". A client matching only a standard vocabulary treats it as
        // terminal and reports a failure for a job that is running perfectly well.
        assertEquals("IMG", (result.images.single() as BinaryData.Bytes).value.decodeToString())
    }

    @Test
    fun `a Luma failure carries the vendor's own reason`() = runTest {
        val provider = LumaProvider(
            client = client(
                listOf(
                    json to """{"id":"g1"}""",
                    json to """{"state":"failed","failure_reason":"prompt rejected"}""",
                ),
            ),
            apiKey = "k",
            pollPolicy = PollPolicy.Fast,
            elapsedMillis = { currentTime },
        )

        val error = assertFailsWith<JobFailedError> {
            provider.imageModel("photon-1").doGenerate(ImageCallOptions(prompt = "x"))
        }
        assertTrue(error.message!!.contains("prompt rejected"), error.message!!)
    }

    // --- Kling ----------------------------------------------------------------------------------

    @Test
    fun `Kling signs a fresh JWT from the access-key pair`() = runTest {
        val provider = KlingProvider(
            client = client(
                listOf(
                    json to """{"data":{"task_id":"k1"}}""",
                ),
            ),
            accessKey = "ak",
            secretKey = "sk",
            nowSeconds = { 1_700_000_000L },
        )

        val started = provider.videoModel("kling-v1-t2v")
            .doStart(VideoCallOptions(prompt = "a cat"))!!

        assertEquals("k1", started.operation.jsonObject["taskId"]!!.jsonPrimitive.content)
        val auth = requests[0].headers[HttpHeaders.Authorization]!!
        assertTrue(auth.startsWith("Bearer eyJ"), auth)
    }

    @Test
    fun `the Kling token is a real HS256 JWT over the access key`() {
        val token = Jwt.hs256(
            secret = "sk",
            claims = buildJsonObject { put("iss", "ak") },
            issuedAtSeconds = 1_700_000_000L,
            expiresAtSeconds = 1_700_001_800L,
        )

        val parts = token.split(".")
        assertEquals(3, parts.size)
        val header = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(parts[0]).decodeToString()
        val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(parts[1]).decodeToString()
        assertTrue(header.contains("\"HS256\""), header)
        assertTrue(payload.contains("\"iss\":\"ak\""), payload)
        // The window is absolute, so it cannot come from the elapsed timer the poller uses.
        assertTrue(payload.contains("1700001800"), payload)
    }

    @Test
    fun `the same claims and window sign to the same token`() {
        // Deterministic, which is what makes the signature testable at all — and confirms nothing random
        // leaks into the signing input.
        val a = Jwt.hs256("sk", buildJsonObject { put("iss", "ak") }, 1_700_000_000L, 1_700_001_800L)
        val b = Jwt.hs256("sk", buildJsonObject { put("iss", "ak") }, 1_700_000_000L, 1_700_001_800L)
        val different = Jwt.hs256("other", buildJsonObject { put("iss", "ak") }, 1_700_000_000L, 1_700_001_800L)

        assertEquals(a, b)
        assertTrue(a != different)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private val TestScope.currentTime: Long get() = testScheduler.currentTime
