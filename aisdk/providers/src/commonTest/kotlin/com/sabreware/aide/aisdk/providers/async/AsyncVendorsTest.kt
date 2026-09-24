package com.sabreware.aide.aisdk.providers.async

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.providers.fal.FalProvider
import com.sabreware.aide.aisdk.providers.replicate.ReplicateProvider
import com.sabreware.aide.aisdk.util.JobFailedError
import com.sabreware.aide.aisdk.util.JobTimeoutError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The queue-based vendors, over the shared polling machine.
 *
 * The value here is that neither vendor implements polling: they describe their own status vocabulary
 * and JSON paths, and `pollUntilDone` does the waiting. What is worth testing per vendor is the
 * vocabulary and the shapes that catch clients out.
 */
class AsyncVendorsTest {

    private val requested = mutableListOf<String>()
    private val bodies = mutableListOf<JsonObject>()

    /** Answers each URL from a script, recording what was asked for. */
    private fun client(routes: List<Pair<String, String>>): HttpClient {
        var index = 0
        return HttpClient(
            MockEngine { request ->
                requested += request.url.toString()
                (request.body as? io.ktor.http.content.TextContent)?.text
                    ?.let { runCatching { com.sabreware.aide.aisdk.util.parseJsonObject(it) }.getOrNull() }
                    ?.let { bodies += it }
                val (contentType, body) = routes[index.coerceAtMost(routes.lastIndex)]
                index++
                respond(content = body, headers = headersOf(HttpHeaders.ContentType, contentType))
            },
        )
    }

    private val json = "application/json"

    // --- fal ------------------------------------------------------------------------------------

    @Test
    fun `fal images are a single synchronous call, not a queue`() = runTest {
        val provider = FalProvider(
            client = client(
                listOf(
                    json to """{"images":[{"url":"https://cdn/img.png","width":1024,"height":1024}]}""",
                    "image/png" to "PNGBYTES",
                ),
            ),
            apiKey = "k",
        )

        val result = provider.imageModel("fal-ai/flux").doGenerate(ImageCallOptions(prompt = "a cat"))

        // Two calls: generate, then fetch. Running the queue flow here — submit, poll, poll, collect,
        // fetch — is what the reference reserves for fal VIDEO, and against the image endpoint it waits
        // on a status URL that synchronous responses never carry.
        assertEquals(2, requested.size)
        assertTrue(requested.first().endsWith("/fal-ai/flux"), "generated at ${requested.first()}")
        // The URL is fetched rather than returned: fal's links expire, so a stored one is a broken image
        // later instead of an error now.
        assertEquals("PNGBYTES", (result.images.single() as BinaryData.Bytes).value.decodeToString())
        assertEquals("https://cdn/img.png", requested.last())
    }

    @Test
    fun `fal size becomes an enum name or an object, never the WxH string`() = runTest {
        val provider = FalProvider(
            client = client(
                listOf(
                    json to """{"images":[{"url":"https://cdn/i.png"}]}""",
                    "image/png" to "X",
                ),
            ),
            apiKey = "k",
        )

        provider.imageModel("fal-ai/flux").doGenerate(
            ImageCallOptions(prompt = "x", size = "1024x1024"),
        )

        // `image_size` takes a preset name or `{width,height}`. A "1024x1024" string is a 422 on every
        // call that sets a size, and it was previously sent on every one of them.
        val body = bodies.first()
        assertTrue(
            body["image_size"] !is JsonPrimitive || body["image_size"]?.jsonPrimitive?.content != "1024x1024",
            "image_size went out as the raw WxH string: ${body["image_size"]}",
        )
    }

    @Test
    fun `fal surfaces the per-image NSFW flag rather than dropping it`() = runTest {
        val provider = FalProvider(
            client = client(
                listOf(
                    json to """{"images":[{"url":"https://cdn/i.png"}],"has_nsfw_concepts":[true],"seed":7}""",
                    "image/png" to "X",
                ),
            ),
            apiKey = "k",
        )

        val result = provider.imageModel("fal-ai/flux").doGenerate(ImageCallOptions(prompt = "x"))

        // A safety-relevant per-image flag: without it a caller cannot tell a filtered image from a
        // clean one, and image is the one modality whose contract has a place to put it.
        val fal = result.providerMetadata?.get("fal")
        assertNotNull(fal, "fal emitted no providerMetadata")
        assertEquals("true", fal["images"]?.jsonArray?.first()?.jsonObject?.get("nsfw")?.jsonPrimitive?.content)
        assertEquals("7", fal["seed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `replicate waits in the request rather than polling`() = runTest {
        val provider = ReplicateProvider(
            client = client(
                listOf(
                    json to """{"id":"p1","status":"succeeded","output":"https://cdn/one.png"}""",
                    "image/png" to "ONE",
                ),
            ),
            apiToken = "t",
        )

        val result = provider.imageModel("stability-ai/sdxl").doGenerate(ImageCallOptions(prompt = "x"))

        // `Prefer: wait` holds the request open until the prediction finishes, so there is no poll loop
        // to get wrong — two calls, the prediction and the image.
        assertEquals(2, requested.size)
        // The union that catches clients out: assuming an array drops every image from the models that
        // return one URL, and does so silently.
        assertEquals("ONE", (result.images.single() as BinaryData.Bytes).value.decodeToString())
    }

    @Test
    fun `replicate handles a multi-output model returning an array`() = runTest {
        val provider = ReplicateProvider(
            client = client(
                listOf(
                    json to """{"id":"p1","output":["https://cdn/a.png","https://cdn/b.png"]}""",
                    "image/png" to "A",
                    "image/png" to "B",
                ),
            ),
            apiToken = "t",
        )

        val result = provider.imageModel("stability-ai/sdxl").doGenerate(ImageCallOptions(prompt = "x", n = 2))

        assertEquals(2, result.images.size)
    }

    @Test
    fun `a versioned replicate id posts to predictions with the version in the body`() = runTest {
        val provider = ReplicateProvider(
            client = client(
                listOf(
                    json to """{"id":"p1","output":"https://cdn/a.png"}""",
                    "image/png" to "A",
                ),
            ),
            apiToken = "t",
        )

        provider.imageModel("owner/name:abc123sha").doGenerate(ImageCallOptions(prompt = "x"))

        // An `owner/name:sha` id is NOT a model path: posting it to `/models/{id}/predictions` 404s,
        // because the version has to travel in the body instead.
        assertTrue(requested.first().endsWith("/predictions"), "posted to ${requested.first()}")
        assertEquals("abc123sha", bodies.first()["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a replicate wait that lapses is a timeout, not an empty result`() = runTest {
        val provider = ReplicateProvider(
            client = client(listOf(json to """{"id":"p1","status":"processing"}""")),
            apiToken = "t",
        )

        // The image model has no poll loop by design, so a prediction still running when the wait window
        // closes is a budget the caller can raise — not the "no output" a missing-field read reports.
        assertFailsWith<JobTimeoutError> {
            provider.imageModel("stability-ai/sdxl").doGenerate(ImageCallOptions(prompt = "x"))
        }
    }

    @Test
    fun `a replicate failure surfaces the vendor's own error text`() = runTest {
        val provider = ReplicateProvider(
            client = client(
                listOf(json to """{"id":"p1","status":"failed","error":"NSFW content detected"}"""),
            ),
            apiToken = "t",
        )

        // `Prefer: wait` reports a refused prediction as a 200 with `status: failed`, so the only place
        // the refusal exists is the body. `JobFailedError` and not a transport error: the request itself
        // succeeded, and retrying this prompt verbatim will be refused again.
        val error = assertFailsWith<JobFailedError> {
            provider.imageModel("stability-ai/sdxl").doGenerate(ImageCallOptions(prompt = "x"))
        }
        // "failed" alone would leave the user guessing; the vendor already said why.
        assertEquals("Replicate generation failed: NSFW content detected", error.message)
    }
}
