package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.NoContentGeneratedError
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini TTS, ported from `google-speech-model.test.ts`.
 *
 * The wire is `generateContent` with `responseModalities: ["AUDIO"]` — not a speech endpoint — and the
 * response is headerless raw PCM, so the WAV wrapping is asserted at the byte level: a header field
 * written wrong is audio that silently fails to play everywhere.
 */
class GoogleSpeechModelTest {

    // 8 bytes of raw PCM ([1..8]) base64-encoded, as in the reference's fixture.
    private val pcmBase64 = "AQIDBAUGBwg="
    private val pcmBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private fun audioResponse(mimeType: String = "audio/L16;rate=24000", data: String = pcmBase64) =
        TestServer.json(
            buildJsonObject {
                putJsonArray("candidates") {
                    add(
                        buildJsonObject {
                            putJsonObject("content") {
                                putJsonArray("parts") {
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", mimeType)
                                                put("data", data)
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }.toString(),
        )

    private fun model(server: TestServer) =
        GoogleSpeechModel(modelId = "gemini-2.5-flash-preview-tts", http = server.http())

    @Test
    fun `the request is generateContent asking for AUDIO, with the default voice`() = runTest {
        val server = TestServer(audioResponse())

        model(server).doGenerate(SpeechCallOptions(text = "Hello from the AI SDK!"))

        val call = server.request()
        assertEquals("v1beta/models/gemini-2.5-flash-preview-tts:generateContent", call.path)
        call.assertBodyEquals(
            """
            {
              "contents": [{"role": "user", "parts": [{"text": "Hello from the AI SDK!"}]}],
              "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": {
                  "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}
                }
              }
            }
            """,
        )
    }

    @Test
    fun `a named voice replaces the default`() = runTest {
        val server = TestServer(audioResponse())

        model(server).doGenerate(SpeechCallOptions(text = "hi", voice = "Puck"))

        val config = server.request().bodyJson()["generationConfig"]!!
        assertTrue(config.toString().contains("\"voiceName\":\"Puck\""))
    }

    @Test
    fun `raw PCM is wrapped in a 44-byte WAV container whose header states the rate`() = runTest {
        val server = TestServer(audioResponse())

        val result = model(server).doGenerate(SpeechCallOptions(text = "hi"))

        val audio = (result.audio as BinaryData.Bytes).value
        assertEquals(44 + pcmBytes.size, audio.size)
        assertEquals("RIFF", audio.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", audio.copyOfRange(8, 12).decodeToString())
        // Sample rate, little-endian at offset 24, parsed from the response's `rate=24000`.
        val rate = (audio[24].toInt() and 0xFF) or
            ((audio[25].toInt() and 0xFF) shl 8) or
            ((audio[26].toInt() and 0xFF) shl 16) or
            ((audio[27].toInt() and 0xFF) shl 24)
        assertEquals(24_000, rate)
        // The payload rides after the header, untouched.
        assertTrue(audio.copyOfRange(44, audio.size).contentEquals(pcmBytes))
        assertEquals(
            24_000,
            result.providerMetadata?.get(GOOGLE_PROVIDER_ID)?.get("sampleRate").int(),
        )
    }

    @Test
    fun `a response mime type with another rate reaches both the header and the metadata`() = runTest {
        val server = TestServer(audioResponse(mimeType = "audio/L16;rate=16000"))

        val result = model(server).doGenerate(SpeechCallOptions(text = "hi"))

        val audio = (result.audio as BinaryData.Bytes).value
        val rate = (audio[24].toInt() and 0xFF) or ((audio[25].toInt() and 0xFF) shl 8)
        assertEquals(16_000, rate)
        assertEquals(
            16_000,
            result.providerMetadata?.get(GOOGLE_PROVIDER_ID)?.get("sampleRate").int(),
        )
    }

    @Test
    fun `outputFormat pcm returns the naked bytes, with a warning that says they are naked`() = runTest {
        val server = TestServer(audioResponse())

        val result = model(server).doGenerate(SpeechCallOptions(text = "hi", outputFormat = "pcm"))

        assertTrue((result.audio as BinaryData.Bytes).value.contentEquals(pcmBytes))
        result.assertUnsupportedFeature("outputFormat")
    }

    @Test
    fun `an unknown outputFormat warns and falls back to wav`() = runTest {
        val server = TestServer(audioResponse())

        val result = model(server).doGenerate(SpeechCallOptions(text = "hi", outputFormat = "mp3"))

        assertEquals(44 + pcmBytes.size, (result.audio as BinaryData.Bytes).value.size)
        result.assertUnsupportedFeature("outputFormat")
    }

    @Test
    fun `instructions become prompt text, because that is where Gemini reads style direction`() = runTest {
        val server = TestServer(audioResponse())

        model(server).doGenerate(SpeechCallOptions(text = "Hello!", instructions = "Speak slowly"))

        val parts = server.request().bodyText
        assertTrue(parts.contains("Speak slowly: Hello!"))
    }

    @Test
    fun `multi-speaker config wins over the voice and rejects instructions with a warning`() = runTest {
        val server = TestServer(audioResponse())

        val result = model(server).doGenerate(
            SpeechCallOptions(
                text = "Joe: hi\nJane: hello",
                voice = "Puck",
                instructions = "cheerfully",
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonObject("multiSpeakerVoiceConfig") {
                            putJsonArray("speakerVoiceConfigs") {
                                add(
                                    buildJsonObject {
                                        put("speaker", "Joe")
                                        putJsonObject("voiceConfig") {
                                            putJsonObject("prebuiltVoiceConfig") { put("voiceName", "Kore") }
                                        }
                                    },
                                )
                            }
                        }
                    },
                ),
            ),
        )

        val body = server.request().bodyText
        assertTrue(body.contains("multiSpeakerVoiceConfig"))
        assertTrue(!body.contains("prebuiltVoiceConfig\":{\"voiceName\":\"Puck\""))
        // The transcript must not be corrupted by a prepended instruction sentence.
        assertTrue(server.request().bodyJson()["contents"].toString().contains("Joe: hi"))
        assertTrue(!server.request().bodyJson()["contents"].toString().contains("cheerfully:"))
        result.assertUnsupportedFeature("instructions")
    }

    @Test
    fun `speed and language have nowhere to go, and each warning says so`() = runTest {
        val server = TestServer(audioResponse())

        val result = model(server).doGenerate(
            SpeechCallOptions(text = "hi", speed = 1.5, language = "en"),
        )

        result.assertUnsupportedFeature("speed")
        result.assertUnsupportedFeature("language")
    }

    @Test
    fun `a response with no audio part fails loudly rather than returning zero bytes`() = runTest {
        val server = TestServer(TestServer.json("""{"candidates":[{"content":{"parts":[{}]}}]}"""))

        assertFailsWith<NoContentGeneratedError> {
            model(server).doGenerate(SpeechCallOptions(text = "hi"))
        }
    }

    private fun com.sabreware.aide.aisdk.SpeechResult.assertUnsupportedFeature(feature: String) {
        assertTrue(
            warnings.filterIsInstance<Warning.Unsupported>().any { it.feature == feature },
            "expected an Unsupported warning for '$feature', got $warnings",
        )
    }
}
