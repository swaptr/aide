package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Gemini batch transcription, ported from `transcription/google-transcription-model.test.ts`.
 *
 * The whole point of these is the endpoint: transcription is served by the Interactions API
 * (`/interactions`, snake_case config), and every assertion here would fail against the
 * `generateContent`-with-an-audio-part shape a port from memory would have written.
 */
class GoogleTranscriptionModelTest {

    private val completed = TestServer.json(
        """
        {
          "id": "interactions/test",
          "status": "completed",
          "steps": [
            {"type": "model_output", "content": [{"type": "text", "text": "Hello world."}]}
          ],
          "usage": {"total_tokens": 10, "total_input_tokens": 10, "total_output_tokens": 0}
        }
        """,
    )

    private fun model(server: TestServer, modelId: String = "gemini-3.5-transcribe") =
        GoogleTranscriptionModel(modelId = modelId, http = server.http())

    private fun call(providerOptions: Map<String, kotlinx.serialization.json.JsonObject>? = null) =
        TranscriptionCallOptions(
            audio = BinaryData.Bytes(byteArrayOf(1, 2, 3, 4)),
            mediaType = "audio/wav",
            providerOptions = providerOptions,
        )

    @Test
    fun `transcription goes to the Interactions API with a snake_case transcription_config`() = runTest {
        val server = TestServer(completed)

        val result = model(server).doGenerate(
            call(
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonArray("customVocabulary") {
                            add("Gemini")
                            add("Kubernetes")
                        }
                        putJsonArray("languageCodes") { add("es-ES") }
                        put("mode", "SMART")
                    },
                ),
            ),
        )

        assertEquals("Hello world.", result.text)
        val request = server.request()
        assertEquals("v1beta/interactions", request.path)
        request.assertBodyEquals(
            """
            {
              "model": "gemini-3.5-transcribe",
              "input": [{"type": "audio", "data": "AQIDBA==", "mime_type": "audio/wav"}],
              "generation_config": {
                "transcription_config": {
                  "language_codes": ["es-ES"],
                  "custom_vocabulary": ["Gemini", "Kubernetes"],
                  "mode": {"type": "smart"}
                }
              }
            }
            """,
        )
        assertEquals(
            """{"usage":{"total_tokens":10,"total_input_tokens":10,"total_output_tokens":0}}""",
            result.providerMetadata?.get(GOOGLE_PROVIDER_ID).toString(),
        )
    }

    @Test
    fun `diarization and word timestamps live inside the mode object, not beside it`() = runTest {
        val server = TestServer(completed)

        model(server).doGenerate(
            call(
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        put("diarization", true)
                        put("wordTimestamp", true)
                    },
                ),
            ),
        )

        val mode = server.request().bodyJson()
            .obj("generation_config")?.obj("transcription_config")?.obj("mode")
        assertEquals(
            """{"type":"verbatim","diarization_mode":"speaker","timestamp_granularities":["word"]}""",
            mode.toString(),
        )
    }

    @Test
    fun `no options means no generation_config at all`() = runTest {
        val server = TestServer(completed)

        model(server).doGenerate(call())

        server.request().assertBodyKeys("model", "input")
    }

    @Test
    fun `word_info annotations become segments with their offsets parsed from duration strings`() = runTest {
        val server = TestServer(
            TestServer.json(
                """
                {
                  "id": "interactions/test",
                  "status": "completed",
                  "steps": [
                    {
                      "type": "model_output",
                      "content": [
                        {
                          "type": "text",
                          "text": "The quick brown fox.",
                          "annotations": [
                            {"type": "word_info", "text": "The", "speaker": "spk:0",
                             "start_offset": "0.100s", "end_offset": "0.100s"},
                            {"type": "word_info", "text": "quick", "speaker": "spk:0",
                             "start_offset": "0.100s", "end_offset": "0.400s"},
                            {"type": "word_info", "text": "fox.", "speaker": "spk:0",
                             "start_offset": "0.700s", "end_offset": "1s"}
                          ]
                        }
                      ]
                    }
                  ],
                  "usage": {"total_input_tokens": 64}
                }
                """,
            ),
        )

        val result = model(server).doGenerate(call())

        assertEquals("The quick brown fox.", result.text)
        assertEquals(
            listOf(
                TranscriptionResult.Segment("The", 0.1, 0.1),
                TranscriptionResult.Segment("quick", 0.1, 0.4),
                TranscriptionResult.Segment("fox.", 0.7, 1.0),
            ),
            result.segments,
        )
    }

    @Test
    fun `base64 audio passes through without a decode-encode round trip`() = runTest {
        val server = TestServer(completed)

        model(server).doGenerate(
            TranscriptionCallOptions(audio = BinaryData.Base64("AQIDBA=="), mediaType = "audio/mpeg"),
        )

        val body = server.request().bodyText
        assertTrue(body.contains("\"data\":\"AQIDBA==\""))
        assertTrue(body.contains("\"mime_type\":\"audio/mpeg\""))
    }

    @Test
    fun `a live model id rejects the unary call loudly, naming the argument`() = runTest {
        val server = TestServer(completed)

        val failure = assertFailsWith<InvalidArgumentError> {
            model(server, modelId = "gemini-3.5-transcribe-live").doGenerate(call())
        }

        assertEquals("modelId", failure.argument)
        assertTrue(failure.message!!.contains("only supports streaming transcription"))
        assertEquals(0, server.callCount)
    }
}
