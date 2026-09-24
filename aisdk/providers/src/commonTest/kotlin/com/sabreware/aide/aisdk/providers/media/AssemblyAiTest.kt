package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.assemblyai.ASSEMBLYAI_PROVIDER_ID
import com.sabreware.aide.aisdk.providers.assemblyai.AssemblyAiProvider
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.bool
import com.sabreware.aide.aisdk.providers.testing.double
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.PollPolicy
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AssemblyAI, ported from the reference's `assemblyai-transcription-model.test.ts`.
 *
 * This provider had NO tests at all before, which is how it came to work for exactly one model: the
 * singular `speech_model` parameter accepts `best` and rejects every other name, and nothing here read
 * the request body to notice. Every case below reads the body AssemblyAI would actually receive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssemblyAiTest {

    /**
     * Upload, submit, poll — the three-call flow the whole provider is shaped around. The last response
     * repeats, so the poll loop can run as many times as its backoff wants.
     */
    private fun server() = TestServer(
        TestServer.json(AssemblyAiFixtures.UPLOAD),
        TestServer.json(AssemblyAiFixtures.QUEUED_JOB),
        TestServer.json(AssemblyAiFixtures.COMPLETED_TRANSCRIPT),
    )

    private fun TestScope.assemblyAi(server: TestServer) = AssemblyAiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        pollPolicy = PollPolicy.Fast,
        elapsedMillis = { currentTime },
    )

    private fun audio() = TranscriptionCallOptions(
        audio = BinaryData.Bytes("AUDIO".encodeToByteArray()),
        mediaType = "audio/wav",
    )

    private fun options(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        mapOf(ASSEMBLYAI_PROVIDER_ID to buildJsonObject(build))

    // --- Which parameter the model travels in ----------------------------------------------------

    @Test
    fun `the legacy model travels in the singular speech_model parameter`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        // The submit call is the SECOND one; the first uploaded the audio. Reading `lastRequest()` here
        // would read a poll, which carries no body at all.
        val submit = server.request(1)
        submit.assertBodyKeys("speech_model", "audio_url")
        assertEquals("best", submit.bodyJson()["speech_model"].string())
        assertEquals(
            "https://storage.assemblyai.com/mock-upload-url",
            submit.bodyJson()["audio_url"].string(),
        )
        val deprecation = result.warnings.filterIsInstance<Warning.Deprecated>().single()
        assertEquals("model 'best'", deprecation.setting)
        assertContains(deprecation.message, "universal-3-5-pro")
        assertContains(deprecation.message, AssemblyAiProvider.MODEL_DOCS_URL)
    }

    @Test
    fun `every other model travels in the plural speech_models array`() = runTest {
        val server = server()

        val result =
            assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(audio())

        // Whole-body: `speech_model` being absent is the entire point, and a per-key read of the key we
        // DO send cannot see it. Sending the singular unconditionally is what made this provider 400 for
        // every model but `best`.
        val submit = server.request(1)
        submit.assertBodyKeys("speech_models", "audio_url")
        assertEquals(
            listOf("universal-3-5-pro"),
            submit.bodyJson().arr("speech_models")?.map { it.string() },
        )
        // The current flagship earns neither a deprecation nor a nudge.
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `universal-3-pro is nudged toward the model that replaces it, by name`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-3-pro").doGenerate(audio())

        val submit = server.request(1)
        submit.assertBodyKeys("speech_models", "audio_url")
        val nudge = result.warnings.filterIsInstance<Warning.Other>().single()
        assertContains(nudge.message, "universal-3-5-pro")
        assertContains(nudge.message, "replace 'universal-3-pro'")
    }

    @Test
    fun `universal-2 is nudged without claiming universal-3-pro replaces it`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-2").doGenerate(audio())

        val nudge = result.warnings.filterIsInstance<Warning.Other>().single()
        assertContains(nudge.message, "universal-3-5-pro")
        assertTrue(
            "replace 'universal-3-pro'" !in nudge.message,
            "universal-2 is not the model universal-3-pro replaced: ${nudge.message}",
        )
    }

    @Test
    fun `the removed nano alias is not special-cased`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("nano").doGenerate(audio())

        // `nano` stopped being a legacy `speech_model` alias, so it falls through to `speech_models`
        // where the live API rejects it. Warning about it here would claim a migration path it lacks.
        val submit = server.request(1)
        submit.assertBodyKeys("speech_models", "audio_url")
        assertEquals(listOf("nano"), submit.bodyJson().arr("speech_models")?.map { it.string() })
        assertEquals(emptyList(), result.warnings.filterIsInstance<Warning.Deprecated>())
    }

    // --- Provider options ------------------------------------------------------------------------

    @Test
    fun `provider options ride alongside the model rather than replacing it`() = runTest {
        val server = server()

        assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put("languageDetection", true)
                    put("punctuate", false)
                },
            ),
        )

        val body = server.request(1).bodyJson()
        assertEquals(listOf("universal-3-5-pro"), body.arr("speech_models")?.map { it.string() })
        assertEquals(true, body["language_detection"].bool())
        // `false` is a value, not an absence: dropping it would silently re-enable punctuation.
        assertEquals(false, body["punctuate"].bool())
    }

    @Test
    fun `the Universal-3 input parameters reach the wire under their own names`() = runTest {
        val server = server()

        assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put("prompt", "This is a conversation about the AI SDK.")
                    put("keytermsPrompt", buildJsonArray { add("Vercel"); add("AI SDK") })
                    put("temperature", 0.2)
                    put("removeAudioTags", "speaker")
                    put("domain", "medical-v1")
                },
            ),
        )

        val body = server.request(1).bodyJson()
        assertEquals("This is a conversation about the AI SDK.", body["prompt"].string())
        // Only some keys are renamed; `prompt`, `temperature` and `domain` are already AssemblyAI's own
        // spelling and a blanket camel-to-snake pass would leave them alone too — which is why the
        // rename is a table rather than a transformation.
        assertEquals(
            listOf("Vercel", "AI SDK"),
            body.arr("keyterms_prompt")?.map { it.string() },
        )
        assertEquals(0.2, body["temperature"].double())
        assertEquals("speaker", body["remove_audio_tags"].string())
        assertEquals("medical-v1", body["domain"].string())
    }

    @Test
    fun `the GA nested configuration objects are renamed one level down`() = runTest {
        val server = server()

        assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put("redactPii", true)
                    put(
                        "speakerOptions",
                        buildJsonObject {
                            put("minSpeakersExpected", 1)
                            put("maxSpeakersExpected", 3)
                        },
                    )
                    put(
                        "languageDetectionOptions",
                        buildJsonObject {
                            put("expectedLanguages", buildJsonArray { add("en"); add("es") })
                            put("fallbackLanguage", "en")
                            put("codeSwitching", true)
                            put("codeSwitchingConfidenceThreshold", 0.5)
                        },
                    )
                    put(
                        "redactPiiAudioOptions",
                        buildJsonObject {
                            put("returnRedactedNoSpeechAudio", true)
                            put("overrideAudioRedactionMethod", "silence")
                        },
                    )
                    put("redactPiiReturnUnredacted", true)
                    put(
                        "redactStaticEntities",
                        buildJsonObject { put("INTERNAL_TOOL", buildJsonArray { add("Bearclaw") }) },
                    )
                },
            ),
        )

        val body = server.request(1).bodyJson()
        // A rename that stops at the top level leaves `minSpeakersExpected` inside an object AssemblyAI
        // rejects wholesale, so the nested keys are the assertion that matters.
        assertEquals(
            buildJsonObject { put("min_speakers_expected", 1); put("max_speakers_expected", 3) },
            body.obj("speaker_options"),
        )
        assertEquals(
            buildJsonObject {
                put("expected_languages", buildJsonArray { add("en"); add("es") })
                put("fallback_language", "en")
                put("code_switching", true)
                put("code_switching_confidence_threshold", 0.5)
            },
            body.obj("language_detection_options"),
        )
        assertEquals(
            buildJsonObject {
                put("return_redacted_no_speech_audio", true)
                put("override_audio_redaction_method", "silence")
            },
            body.obj("redact_pii_audio_options"),
        )
        assertEquals(true, body["redact_pii_return_unredacted"].bool())
        // A caller-invented map, so its keys are cargo and must survive untouched.
        assertEquals(
            buildJsonObject { put("INTERNAL_TOOL", buildJsonArray { add("Bearclaw") }) },
            body.obj("redact_static_entities"),
        )
    }

    // --- Warnings about combinations AssemblyAI refuses ------------------------------------------

    @Test
    fun `wordBoost and boostParam are reported together when both are set`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put("wordBoost", buildJsonArray { add("Vercel") })
                    put("boostParam", "high")
                },
            ),
        )

        val deprecation = result.warnings.filterIsInstance<Warning.Deprecated>().single()
        assertEquals("wordBoost, boostParam", deprecation.setting)
        assertContains(deprecation.message, "keytermsPrompt")
    }

    @Test
    fun `the deprecation names only the option the caller actually set`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(providerOptions = options { put("boostParam", "high") }),
        )

        // A warning that names a setting the caller never wrote sends them looking for it.
        val deprecation = result.warnings.filterIsInstance<Warning.Deprecated>().single()
        assertEquals("boostParam", deprecation.setting)
        assertContains(deprecation.message, "keytermsPrompt")
    }

    @Test
    fun `redaction options that need redactPii are warned about when it is off`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put(
                        "redactStaticEntities",
                        buildJsonObject { put("TOOL", buildJsonArray { add("Vercel") }) },
                    )
                },
            ),
        )

        assertTrue(
            result.warnings.filterIsInstance<Warning.Other>().any { "redactPii" in it.message },
            "expected a warning naming redactPii, got: ${result.warnings}",
        )
    }

    @Test
    fun `redactPiiAudioOptions without redactPiiAudio is warned about, not repaired`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put("redactPii", true)
                    put(
                        "redactPiiAudioOptions",
                        buildJsonObject { put("overrideAudioRedactionMethod", "silence") },
                    )
                },
            ),
        )

        // Enabling `redactPiiAudio` on the caller's behalf would start writing a redacted audio file
        // they never asked for and are billed for.
        assertTrue(
            result.warnings.filterIsInstance<Warning.Other>().any { "redactPiiAudio" in it.message },
            "expected a warning naming redactPiiAudio, got: ${result.warnings}",
        )
    }

    @Test
    fun `an explicit language and language detection together are warned about`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(
            audio().copy(
                providerOptions = options {
                    put("languageCode", "en")
                    put("languageDetection", true)
                },
            ),
        )

        assertTrue(
            result.warnings.filterIsInstance<Warning.Other>()
                .any { "languageDetection" in it.message },
            "expected a warning naming languageDetection, got: ${result.warnings}",
        )
    }

    // --- Reading the transcript ------------------------------------------------------------------

    @Test
    fun `the transcript text is read from the completed job`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        assertEquals("Hello, world!", result.text)
        assertEquals("en_us", result.language)
    }

    @Test
    fun `word timings are converted from AssemblyAI's milliseconds into seconds`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        // The recorded first word is 250ms–650ms. Passing milliseconds straight through gives timings a
        // thousand times too long, which reads as a hung player rather than as a wrong number.
        assertEquals(
            TranscriptionResult.Segment("Hello,", startSecond = 0.25, endSecond = 0.65),
            result.segments.first(),
        )
        assertEquals(
            TranscriptionResult.Segment("world", startSecond = 0.73, endSecond = 1.022),
            result.segments.last(),
        )
        assertEquals(2, result.segments.size)
    }

    @Test
    fun `diarization and audio intelligence are namespaced rather than dropped`() = runTest {
        val server = server()

        val result =
            assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(audio())

        val metadata = assertNotNull(result.providerMetadata?.get(ASSEMBLYAI_PROVIDER_ID))
        // None of this fits the contract's flat `segments`, and it is the reason a caller chose
        // AssemblyAI rather than the cheap option.
        val utterance = (metadata["utterances"] as JsonArray).first() as JsonObject
        assertEquals("A", utterance["speaker"].string())
        assertEquals("Hello, world!", utterance["text"].string())
        val entity = (metadata["entities"] as JsonArray).first() as JsonObject
        assertEquals("location", entity["entity_type"].string())
        assertEquals("Canada", entity["text"].string())
        val sentiment = (metadata["sentimentAnalysisResults"] as JsonArray).first() as JsonObject
        assertEquals("POSITIVE", sentiment["sentiment"].string())
        assertNotNull(metadata["contentSafetyLabels"])
        assertNotNull(metadata["iabCategoriesResult"])
        assertNotNull(metadata["autoHighlightsResult"])
    }

    @Test
    fun `the response body is the raw transcript, not the fields we happened to model`() = runTest {
        val server = server()

        val result =
            assemblyAi(server).transcriptionModel("universal-3-5-pro").doGenerate(audio())

        val body = parseJsonObject(assertNotNull(result.response.body))
        // A per-word speaker label has nowhere to go in `segments`, and chapters and the summary have
        // nowhere to go at all. Re-serializing the parsed shape would lose all three silently.
        assertEquals(
            "speaker",
            ((body["words"] as JsonArray).first() as JsonObject)["speaker"].string(),
        )
        assertNotNull(body["chapters"])
        assertEquals("- Hello, world!", body["summary"].string())
    }

    @Test
    fun `the duration comes from the job rather than being inferred from the last word`() = runTest {
        val server = server()

        val result = assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        // The recording's last word ends at 1.022s but the audio is 281s long; deriving the duration
        // from the words would report a five-minute file as a one-second one.
        assertEquals(281.0, result.durationInSeconds)
    }

    // --- Transport ---------------------------------------------------------------------------------

    @Test
    fun `AssemblyAI authenticates with a bare key and no scheme`() = runTest {
        val server = server()

        assemblyAi(server).transcriptionModel("best").doGenerate(
            audio().copy(headers = mapOf("Custom-Request-Header" to "request-header-value")),
        )

        // `Bearer <key>` is a 401 that reads as an invalid key, which sends the caller to rotate a
        // credential that was fine.
        val upload = server.request(0)
        upload.assertHeader("Authorization", "test-api-key")
        upload.assertHeader("Custom-Request-Header", "request-header-value")
        assertEquals("v2/upload", upload.path)
        // The per-call headers reach every call in the flow, not only the first.
        server.request(1).assertHeader("Custom-Request-Header", "request-header-value")
        server.request(2).assertHeader("Custom-Request-Header", "request-header-value")
    }

    @Test
    fun `the audio is uploaded as raw bytes before any job exists`() = runTest {
        val server = server()

        assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        val upload = server.request(0)
        assertEquals("POST", upload.method)
        assertEquals("AUDIO", upload.bodyText)
        // Not multipart: AssemblyAI's upload endpoint takes the body and nothing else.
        assertTrue(upload.multipart.isEmpty())
        assertEquals("application/octet-stream", upload.header("Content-Type"))
    }

    @Test
    fun `base64 audio is decoded before upload rather than posted as text`() = runTest {
        val server = server()

        assemblyAi(server).transcriptionModel("best").doGenerate(
            TranscriptionCallOptions(BinaryData.Base64("QVVESU8="), "audio/wav"),
        )

        assertEquals("AUDIO", server.request(0).bodyText)
    }

    @Test
    fun `the poll asks the transcript id it was handed, not one rebuilt from the submit body`() =
        runTest {
            val server = server()

            assemblyAi(server).transcriptionModel("best").doGenerate(audio())

            val poll = server.request(2)
            assertEquals("GET", poll.method)
            assertEquals("v2/transcript/9ea68fd3-f953-42c1-9742-976c447fb463", poll.path)
        }

    @Test
    fun `the response carries the model and the poll's own headers`() = runTest {
        val server = TestServer(
            TestServer.json(AssemblyAiFixtures.UPLOAD),
            TestServer.json(AssemblyAiFixtures.QUEUED_JOB),
            TestServer.json(AssemblyAiFixtures.COMPLETED_TRANSCRIPT).withHeaders(
                "x-request-id" to "test-request-id",
                "x-ratelimit-remaining" to "123",
            ),
        )

        val result = assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        assertEquals("best", result.response.modelId)
        assertEquals("test-request-id", result.response.headers?.get("x-request-id"))
        assertEquals("123", result.response.headers?.get("x-ratelimit-remaining"))
        assertEquals("application/json", result.response.headers?.get("content-type"))
        // The reference also pins `response.timestamp` against an injected clock. This port's transport
        // defaults its clock to one that reports nothing, deliberately — `ModalityResponse` models "we
        // did not read that" as null rather than fabricating a value — so there is no timestamp to
        // assert here without a clock parameter this provider does not take.
    }

    @Test
    fun `a job the poll never finishes is a queued status, not a silent empty transcript`() = runTest {
        val server = TestServer(
            TestServer.json(AssemblyAiFixtures.UPLOAD),
            TestServer.json(AssemblyAiFixtures.QUEUED_JOB),
            TestServer.json("""{"status":"processing"}"""),
            TestServer.json("""{"status":"processing"}"""),
            TestServer.json(AssemblyAiFixtures.COMPLETED_TRANSCRIPT),
        )

        val result = assemblyAi(server).transcriptionModel("best").doGenerate(audio())

        // Three polls, and only the last carried a transcript. A client that read the first would
        // report an empty transcription as a success.
        assertEquals("Hello, world!", result.text)
        assertEquals(5, server.callCount)
    }
}
