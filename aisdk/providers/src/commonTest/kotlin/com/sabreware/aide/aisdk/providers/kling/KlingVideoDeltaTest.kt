package com.sabreware.aide.aisdk.providers.kling

import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.providers.async.KlingFixtures
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ai@7.0.102 deltas to `klingai-video-model.ts`: the caller's receiver forwarded as `callback_url`
 * (`ef3bac4`), and status polls that validate a redirect before following it (`a580ec8`).
 */
class KlingVideoDeltaTest {

    private val baseUrl = "https://api-singapore.klingai.com"
    private val modelId = "kling-v2.6-t2v"

    private fun TestServer.provider() = KlingProvider(
        client = HttpClient(engine()),
        apiKey = "test-jwt-token",
        baseUrl = baseUrl,
    )

    private fun created() = TestServer(TestServer.json(KlingFixtures.TASK_CREATED))

    private suspend fun submittedCallback(webhookUrl: String?, rawUrl: String?): String? {
        val server = created()
        server.provider().videoModel(modelId).doStart(
            VideoCallOptions(
                prompt = "A character performs a graceful dance",
                providerOptions = rawUrl?.let {
                    mapOf(KLING_PROVIDER_ID to buildJsonObject { put("callback_url", it) })
                },
            ),
            webhookUrl = webhookUrl,
        )
        return server.request().bodyJson()["callback_url"].string()
    }

    @Test
    fun `the caller's receiver goes out as callback_url, and wins over one spelled raw in the options`() = runTest {
        // The reference's table: neither, raw only, explicit only, both.
        assertNull(submittedCallback(webhookUrl = null, rawUrl = null))
        assertEquals("https://example.com/raw", submittedCallback(webhookUrl = null, rawUrl = "https://example.com/raw"))
        assertEquals(
            "https://example.com/webhook",
            submittedCallback(webhookUrl = "https://example.com/webhook", rawUrl = null),
        )
        assertEquals(
            "https://example.com/webhook",
            submittedCallback(webhookUrl = "https://example.com/webhook", rawUrl = "https://example.com/raw"),
        )
    }

    @Test
    fun `a poll redirected off the API host to a link-local address is refused before it is followed`() = runTest {
        val server = TestServer(
            TestServer.json(KlingFixtures.TASK_CREATED),
            TestServer.json("", status = HttpStatusCode.Found)
                .withHeaders("Location" to "http://169.254.169.254/latest/meta-data/"),
        )
        val model = server.provider().videoModel(modelId)
        val started = model.doStart(VideoCallOptions(prompt = "A character performs a graceful dance"), webhookUrl = null)!!

        assertFailsWith<DownloadError> { model.doStatus(started.operation, headers = null) }

        // The metadata address was never requested: the guard runs BEFORE the hop, not after it.
        assertEquals(2, server.callCount)
        assertTrue(server.request(1).url.startsWith(baseUrl), server.request(1).url)
    }
}
