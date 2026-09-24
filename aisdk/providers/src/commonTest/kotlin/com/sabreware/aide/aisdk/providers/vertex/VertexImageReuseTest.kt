package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini image generation on Vertex is the google package's model on another host — what this pins is
 * the one thing the vertex wrapper adds: options filed under `google-vertex` reach the delegate.
 */
class VertexImageReuseTest {

    @Test
    fun `image options filed under google-vertex are re-filed into the google namespace`() = runTest {
        // The delegate is the language model, and the language model always STREAMS — doGenerate
        // assembles the SSE stream — so the mock answers as the wire really does.
        val server = TestServer(
            TestServer.sse(
                "data: " +
                    """{"candidates":[{"content":{"role":"model","parts":[{"inlineData":""" +
                    """{"mimeType":"image/png","data":"aW1n"}}]},"finishReason":"STOP"}]}""" +
                    "\n\n",
            ),
        )
        val provider = VertexProvider(
            client = HttpClient(server.engine()),
            projectId = "test-project",
            location = "global",
            accessToken = { "test-token" },
        )

        val result = provider.imageModel("gemini-2.5-flash-image").doGenerate(
            ImageCallOptions(
                prompt = "a lighthouse",
                aspectRatio = "16:9",
                providerOptions = mapOf(
                    VERTEX_PROVIDER_ID to buildJsonObject {
                        putJsonObject("imageConfig") { put("imageSize", "2K") }
                    },
                ),
            ),
        )

        val call = server.request()
        // `global` exercises the bare-host rule — no region prefix, no `.rep.` subdomain.
        assertTrue(
            call.url.startsWith(
                "https://aiplatform.googleapis.com/v1/projects/test-project" +
                    "/locations/global/publishers/google/models/gemini-2.5-flash-image:",
            ),
            "expected the global publisher base, got ${call.url}",
        )
        val imageConfig = call.bodyJson().obj("generationConfig", "imageConfig")
        // The vendor knob rode in under OUR id; the neutral aspectRatio still wins its own field.
        assertEquals("2K", imageConfig?.get("imageSize").string())
        assertEquals("16:9", imageConfig?.get("aspectRatio").string())
        assertEquals(1, result.images.size)
        assertEquals(null, result.isRetryable)
    }

    @Test
    fun `a prompt block is classified as terminal`() = runTest {
        // "should classify prompt blocks as terminal": one call, no images, and not worth another try.
        val server = TestServer(
            TestServer.sse(
                "data: " +
                    """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},""" +
                    """"usageMetadata":{"promptTokenCount":9,"totalTokenCount":9}}""" + "\n\n",
            ),
        )
        val provider = VertexProvider(
            client = HttpClient(server.engine()),
            projectId = "test-project",
            location = "global",
            accessToken = { "test-token" },
        )

        val result = provider.imageModel("gemini-2.5-flash-image").doGenerate(ImageCallOptions(prompt = "A blocked image prompt"))

        assertEquals(emptyList(), result.images)
        assertEquals(false, result.isRetryable)
        assertEquals(1, server.callCount)
    }
}
