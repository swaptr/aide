package com.sabreware.aide.aisdk.providers.bytedance

import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ai@7.0.102 deltas to `bytedance-video-model.ts`: the caller's receiver forwarded as `callback_url`
 * and `expired` as a terminal answer (`ef3bac4`), and status polls that validate a redirect before
 * following it (`a580ec8`).
 */
class BytedanceVideoDeltaTest {

    private val baseUrl = "https://ark.ap-southeast.bytepluses.com/api/v3"
    private val modelId = "seedance-1-5-pro-251215"

    private fun TestServer.provider() = BytedanceProvider(
        client = HttpClient(engine()),
        apiKey = "test-key",
    )

    private val operation = buildJsonObject { put("taskId", "test-task-id-123") }

    private suspend fun submittedCallback(webhookUrl: String?, rawUrl: String?): String? {
        val server = TestServer(TestServer.json("""{"id":"task-1"}"""))
        server.provider().videoModel(modelId).doStart(
            VideoCallOptions(
                prompt = "a fox in the snow",
                providerOptions = rawUrl?.let {
                    mapOf(BYTEDANCE_PROVIDER_ID to buildJsonObject { put("callback_url", it) })
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
    fun `an expired task is an answer, with the service's own diagnostic kept`() = runTest {
        // The reference's two fixtures: an error object, and none — then the raw body is the diagnostic.
        val diagnosed = TestServer(
            TestServer.json(
                """{"id":"test-task-id-123","status":"expired",""" +
                    """"error":{"code":"TaskExpired","message":"The task has expired."}}""",
            ),
        )
        val withError = assertIs<VideoStatusResult.Failed>(
            diagnosed.provider().videoModel(modelId).doStatus(operation, headers = null),
        )
        assertEquals("Video generation expired. Task ID: test-task-id-123. The task has expired.", withError.error)

        val bare = TestServer(TestServer.json("""{"id":"test-task-id-123","status":"expired"}"""))
        val withoutError = assertIs<VideoStatusResult.Failed>(
            bare.provider().videoModel(modelId).doStatus(operation, headers = null),
        )
        assertEquals(
            """Video generation expired. Task ID: test-task-id-123. {"id":"test-task-id-123","status":"expired"}""",
            withoutError.error,
        )
    }

    @Test
    fun `a poll redirected off the API host to a link-local address is refused before it is followed`() = runTest {
        val server = TestServer(
            TestServer.json("", status = HttpStatusCode.Found)
                .withHeaders("Location" to "http://169.254.169.254/latest/meta-data/"),
        )

        assertFailsWith<DownloadError> { server.provider().videoModel(modelId).doStatus(operation, headers = null) }

        // The metadata address was never requested: the guard runs BEFORE the hop, not after it.
        assertEquals(1, server.callCount)
        assertEquals("$baseUrl/contents/generations/tasks/test-task-id-123", server.request().url)
    }
}
