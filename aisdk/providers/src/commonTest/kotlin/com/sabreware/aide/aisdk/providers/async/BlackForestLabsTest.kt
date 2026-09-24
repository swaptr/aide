package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.providers.blackforestlabs.BlackForestLabsProvider
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.PollPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** Black Forest Labs (FLUX), whose queue has two details that catch clients out. */
class BlackForestLabsTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun provider(routes: List<Pair<String, String>>, elapsed: () -> Long): BlackForestLabsProvider {
        var index = 0
        return BlackForestLabsProvider(
            client = HttpClient(
                MockEngine { request ->
                    requests += request
                    val (contentType, body) = routes[index.coerceAtMost(routes.lastIndex)]
                    index++
                    respond(content = body, headers = headersOf(HttpHeaders.ContentType, contentType))
                },
            ),
            apiKey = "k",
            pollPolicy = PollPolicy.Fast,
            elapsedMillis = elapsed,
        )
    }

    private val json = "application/json"

    @Test
    fun `the poll URL comes from the response, not from our own base`() = runTest {
        val p = provider(
            listOf(
                json to """{"id":"j1","polling_url":"https://api.us1.bfl.ai/v1/get?id=j1"}""",
                json to """{"status":"Pending"}""",
                json to """{"status":"Ready","result":{"sample":"https://cdn/out.png"}}""",
                "image/png" to "FLUX",
            ),
            elapsed = { currentTime },
        )

        val result = p.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "a cat"))

        // It is on a DIFFERENT host from the submit endpoint; rebuilding it from our base is a 404 for a
        // job that ran perfectly well.
        assertTrue(requests[1].url.toString().startsWith("https://api.us1.bfl.ai/"), requests[1].url.toString())
        assertEquals("FLUX", (result.images.single() as BinaryData.Bytes).value.decodeToString())
    }

    @Test
    fun `auth is the x-key header, not a bearer`() = runTest {
        val p = provider(
            listOf(
                json to """{"polling_url":"https://api.us1.bfl.ai/v1/get"}""",
                json to """{"status":"Ready","result":{"sample":"https://cdn/o.png"}}""",
                "image/png" to "X",
            ),
            elapsed = { currentTime },
        )

        p.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "x"))

        assertEquals("k", requests[0].headers["x-key"])
        assertEquals(null, requests[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `the two moderation outcomes stay distinguishable`() = runTest {
        val prompt = provider(
            listOf(
                json to """{"polling_url":"https://api.us1.bfl.ai/v1/get"}""",
                json to """{"status":"Request Moderated"}""",
            ),
            elapsed = { currentTime },
        )
        val rejected = assertFailsWith<JobFailedError> {
            prompt.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "x"))
        }
        // A rejected PROMPT is a different problem from a rejected RESULT, and flattening both to
        // "failed" loses the only thing that tells a user what to change.
        assertTrue(rejected.message!!.contains("prompt"), rejected.message!!)

        requests.clear()
        val content = provider(
            listOf(
                json to """{"polling_url":"https://api.us1.bfl.ai/v1/get"}""",
                json to """{"status":"Content Moderated"}""",
            ),
            elapsed = { currentTime },
        )
        val filtered = assertFailsWith<JobFailedError> {
            content.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "x"))
        }
        assertTrue(filtered.message!!.contains("generated image"), filtered.message!!)
    }

    @Test
    fun `Ready with no sample is a failure rather than a crash`() = runTest {
        val p = provider(
            listOf(
                json to """{"polling_url":"https://api.us1.bfl.ai/v1/get"}""",
                json to """{"status":"Ready","result":{}}""",
            ),
            elapsed = { currentTime },
        )

        assertFailsWith<JobFailedError> {
            p.imageModel("flux-pro-1.1").doGenerate(ImageCallOptions(prompt = "x"))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private val TestScope.currentTime: Long get() = testScheduler.currentTime
