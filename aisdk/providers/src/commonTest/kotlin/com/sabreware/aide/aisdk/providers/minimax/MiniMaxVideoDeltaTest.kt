package com.sabreware.aide.aisdk.providers.minimax

import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ai@7.0.102 deltas to `minimax-video-model.ts`: the caller's receiver forwarded as `callback_url`
 * (`ef3bac4`), the task id encoded as the path segment it is and status polls that validate a redirect
 * before following it (`46cea63`, `a580ec8`). The `doStart`/`doStatus` pair those land on is the one
 * this port already served.
 */
class MiniMaxVideoDeltaTest {

    private val baseUrl = "https://api.minimax.io"

    private fun TestServer.provider() = MiniMaxProvider(
        client = HttpClient(engine()),
        apiKey = "test-key",
    )

    private fun created() = TestServer(TestServer.json("""{"task_id":"task-9"}"""))

    private val operation = buildJsonObject { put("taskId", "task-9") }

    @Test
    fun `the caller's receiver goes out as callback_url, and its absence leaves the key out`() = runTest {
        val named = created()
        val start = named.provider().videoModel("MiniMax-H3")
            .doStart(VideoCallOptions(prompt = "a fox"), webhookUrl = "https://example.com/callback")!!
        assertEquals("https://example.com/callback", named.request().bodyJson()["callback_url"].string())
        // It is no longer refused: the receiver is the caller's to run, this only names it.
        assertTrue(start.warnings.none { it is Warning.Unsupported && it.feature == "webhookUrl" })

        val unnamed = created()
        unnamed.provider().videoModel("MiniMax-H3").doStart(VideoCallOptions(prompt = "a fox"), webhookUrl = null)
        assertNull(unnamed.request().bodyJson()["callback_url"])
    }

    @Test
    fun `the task id is a path segment, so one carrying reserved characters is encoded`() = runTest {
        val server = TestServer(TestServer.json("""{"task":{"status":"queued"}}"""))
        val taskId = "task/with?query=value#fragment"

        val status = server.provider().videoModel("MiniMax-H3")
            .doStatus(buildJsonObject { put("taskId", taskId) }, headers = null)

        assertIs<VideoStatusResult.Pending>(status)
        assertEquals(
            "$baseUrl/v2/query/video_generation/task%2Fwith%3Fquery%3Dvalue%23fragment",
            server.request().url,
        )
    }

    @Test
    fun `a poll redirected off the API host to a link-local address is refused before it is followed`() = runTest {
        val server = TestServer(
            TestServer.json("", status = HttpStatusCode.Found)
                .withHeaders("Location" to "http://169.254.169.254/latest/meta-data/"),
        )

        assertFailsWith<DownloadError> {
            server.provider().videoModel("MiniMax-H3").doStatus(operation, headers = null)
        }

        // The metadata address was never requested: the guard runs BEFORE the hop, not after it.
        assertEquals(1, server.callCount)
        assertEquals("$baseUrl/v2/query/video_generation/task-9", server.request().url)
    }
}
