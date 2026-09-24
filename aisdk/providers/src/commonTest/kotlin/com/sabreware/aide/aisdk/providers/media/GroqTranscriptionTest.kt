package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.providers.groq.GROQ_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.groq.GroqProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Groq's Whisper endpoint, read from the vendored reference. */
class GroqTranscriptionTest {

    private fun groq(server: TestServer) =
        GroqProvider(server.http(), "k", GroqProvider.DEFAULT_BASE_URL, emptyMap())

    private fun options(vendor: Map<String, JsonPrimitive> = emptyMap()) = TranscriptionCallOptions(
        audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
        mediaType = "audio/wav",
        providerOptions = if (vendor.isEmpty()) {
            null
        } else {
            mapOf(GROQ_PROVIDER_ID to buildJsonObject { vendor.forEach { (k, v) -> put(k, v) } })
        },
    )

    @Test
    fun `Groq names the model in a form field and nothing else a caller did not ask for`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"Hello world","x_groq":{"id":"r1"}}"""))

        val result = groq(server).transcriptionModel("whisper-large-v3").doGenerate(options())

        val call = server.request()
        assertEquals("openai/v1/audio/transcriptions", call.path)
        assertEquals(mapOf("model" to "whisper-large-v3"), call.multipart)
        call.assertHeader("Authorization", "Bearer k")
        assertEquals("Hello world", result.text)
        result.warnings.assertNoWarnings()
    }

    @Test
    fun `a granularity list is repeated fields with Groq's own bracket suffix`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"hi","x_groq":{"id":"r1"}}"""))

        groq(server).transcriptionModel("whisper-large-v3").doGenerate(
            TranscriptionCallOptions(
                audio = BinaryData.Bytes(ByteArray(1)),
                mediaType = "audio/wav",
                providerOptions = mapOf(
                    GROQ_PROVIDER_ID to buildJsonObject {
                        put("responseFormat", JsonPrimitive("verbose_json"))
                        put("language", JsonPrimitive("en"))
                        put("prompt", JsonPrimitive("a hint"))
                        put("temperature", JsonPrimitive(0.2))
                        put(
                            "timestampGranularities",
                            buildJsonArray {
                                add(JsonPrimitive("segment"))
                                add(JsonPrimitive("word"))
                            },
                        )
                    },
                ),
            ),
        )

        // The bare name holding a JSON array is accepted and ignored, so the timings never arrive —
        // a request that succeeds and answers without the thing it was sent for.
        val call = server.request()
        call.assertMultipartField("timestamp_granularities[]", "word")
        assertEquals(
            2,
            Regex("""name="timestamp_granularities\[]"""").findAll(call.bodyText).count(),
        )
        call.assertMultipartField("model", "whisper-large-v3")
        call.assertMultipartField("response_format", "verbose_json")
        call.assertMultipartField("language", "en")
        call.assertMultipartField("prompt", "a hint")
        call.assertMultipartField("temperature", "0.2")
    }

    @Test
    fun `verbose_json segments are read as segments`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {"text":"Hello world","language":"English","duration":2.0,"x_groq":{"id":"r1"},
                 "segments":[{"id":0,"start":0.0,"end":2.0,"text":"Hello world"}]}
                """.trimIndent(),
            ),
        )

        val result = groq(server).transcriptionModel("whisper-large-v3").doGenerate(options())

        assertEquals(1, result.segments.size)
        assertEquals("Hello world", result.segments.single().text)
        assertEquals(2.0, result.segments.single().endSecond)
        assertEquals("English", result.language)
        assertEquals(2.0, result.durationInSeconds)
    }

    @Test
    fun `a word granularity answers with words instead, and they are segments too`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {"text":"Hello world","language":"English","duration":2.0,"x_groq":{"id":"r1"},
                 "words":[{"word":"Hello","start":0.0,"end":1.0},
                          {"word":"world","start":1.0,"end":2.0}]}
                """.trimIndent(),
            ),
        )

        val result = groq(server).transcriptionModel("whisper-large-v3").doGenerate(options())

        // Groq spells the text `word` here rather than `text`, so a reader that knows only `segments`
        // returns an empty list for a transcript that is full of timings.
        assertEquals(2, result.segments.size)
        assertEquals("Hello", result.segments[0].text)
        assertEquals(1.0, result.segments[0].endSecond)
    }

    @Test
    fun `the text response format is dropped, because it answers with no JSON to read`() = runTest {
        val server = TestServer(TestServer.json("""{"text":"Hello world","x_groq":{"id":"r1"}}"""))

        val result = groq(server).transcriptionModel("whisper-large-v3")
            .doGenerate(options(mapOf("responseFormat" to JsonPrimitive("text"))))

        // Forwarded, it turns every such call into a parse failure; omitted, Groq's default returns the
        // same transcript in an envelope. The field carries nothing the contract needs either way.
        server.request().assertMultipartField("model", "whisper-large-v3")
        assertEquals(mapOf("model" to "whisper-large-v3"), server.request().multipart)
        result.warnings.assertUnsupported(
            feature = "providerOptions.groq.responseFormat",
            details = "Groq's 'text' response format returns a bare transcript rather than JSON. " +
                "The request was sent without it; the transcript is unchanged.",
        )
        assertEquals("Hello world", result.text)
    }
}
