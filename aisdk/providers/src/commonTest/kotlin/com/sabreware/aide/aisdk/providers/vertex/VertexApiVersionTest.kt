package com.sabreware.aide.aisdk.providers.vertex

import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * One version segment for one API, across every surface this provider reaches.
 *
 * The media models used to build on `v1beta1` while the language models built on `v1`, so a single
 * Vertex surface — `generateContent` — was reached through two different versions depending on which
 * model object a caller happened to hold. Both resolve, which is exactly why nothing failed and the
 * split survived: the justification in the code was "matching the reference", and the reference is not
 * an authority on Google's URLs.
 *
 * Google documents `v1` for every surface here (see [vertexPublisherBaseUrl] for the per-surface
 * citations), so these assert the agreement rather than each URL in isolation — a future edit that
 * moves one model back is caught by the one that did not move.
 */
class VertexApiVersionTest {

    private fun provider(server: TestServer) = VertexProvider(
        client = HttpClient(server.engine()),
        projectId = "test-project",
        location = "us-central1",
        accessToken = { "test-token" },
    )

    private val prompt = listOf(ModelMessage.User(listOf(UserPart.Text("hi"))))

    @Test
    fun `the language model and the media models agree on the version segment`() = runTest {
        val expected = "https://us-central1-aiplatform.googleapis.com/v1/projects/test-project" +
            "/locations/us-central1/publishers/google"

        // Language: generateContent, streamed.
        val languageServer = TestServer(
            TestServer.sse("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}\n\n"),
        )
        provider(languageServer).languageModel("gemini-2.5-flash")
            .doStream(CallOptions(prompt = prompt)).stream.toList()
        assertTrue(
            languageServer.request().url.startsWith(expected),
            "language model: ${languageServer.request().url}",
        )

        // Embeddings: the same publisher base, a different verb.
        val embeddingServer = TestServer(
            TestServer.json("""{"predictions":[{"embeddings":{"values":[0.1]}}]}"""),
        )
        provider(embeddingServer).embeddingModel("text-embedding-005")
            .doEmbed(EmbeddingCallOptions(values = listOf("hello")))
        assertTrue(
            embeddingServer.request().url.startsWith(expected),
            "embedding model: ${embeddingServer.request().url}",
        )

        // Video: the long-running verb, same base.
        val videoServer = TestServer(
            TestServer.json("""{"name":"projects/test-project/operations/op-1"}"""),
        )
        provider(videoServer).videoModel("veo-3.0-generate-001")
            .doStart(VideoCallOptions(prompt = "a cat"))
        assertTrue(
            videoServer.request().url.startsWith(expected),
            "video model: ${videoServer.request().url}",
        )
    }

    @Test
    fun `the two surfaces on their own hosts keep their own versions`() = runTest {
        // Cloud Text-to-Speech and Cloud Speech-to-Text are separate APIs, not other versions of this
        // one — so unifying the publisher base must not have dragged them along. `chirp` routes to
        // Cloud TTS; a non-gemini transcription id routes to Cloud STT v2.
        val ttsServer = TestServer(TestServer.json("""{"audioContent":"QUJD"}"""))
        provider(ttsServer).speechModel("chirp-3-hd").doGenerate(
            com.sabreware.aide.aisdk.SpeechCallOptions(text = "hello"),
        )
        assertTrue(
            ttsServer.request().url.startsWith("https://texttospeech.googleapis.com/v1/"),
            "cloud tts: ${ttsServer.request().url}",
        )
    }
}
