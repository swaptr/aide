package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.CallOptions
import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.ResponseFormat
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.assertNoWarnings
import com.sabreware.aide.aisdk.providers.testing.assertUnsupported
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonElement
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the Interactions endpoint actually receives for each option.
 *
 * Asserted against the serialized body: `ProviderJson` sets `encodeDefaults = false`, so a field left
 * at a Kotlin default never reaches the wire and an assertion on the request object cannot see its
 * absence. The response is the `basic` fixture throughout; the body is the subject.
 */
@OptIn(ExperimentalEncodingApi::class)
class GoogleInteractionsRequestTest {

    private class Sent(val body: JsonObject, val warnings: List<Warning>)

    private suspend fun send(
        options: CallOptions,
        target: GoogleInteractionsTarget = GoogleInteractionsTarget.Model(TEST_MODEL),
    ): Sent {
        val server = TestServer(TestServer.json(GoogleInteractionsFixtures.BASIC_JSON))
        val result = interactionsModel(server, target).doGenerate(options)
        return Sent(server.request().bodyJson(), result.warnings)
    }

    private val agent = GoogleInteractionsTarget.Agent(TEST_AGENT)

    // --- video output --------------------------------------------------------------------------

    @Test
    fun `video response format controls are re-spelled onto the wire and inline video comes back`() = runTest {
        // "serializes video response format controls and returns inline video data".
        val server = TestServer(
            TestServer.json(
                """{"id":"v1_video","status":"completed","model":"gemini-omni-flash-preview","steps":[""" +
                    """{"type":"model_output","content":[{"type":"video","mime_type":"video/mp4","data":"AAAAIGZ0eXBpc29t"}]}]}""",
            ),
        )

        val result = interactionsModel(server).doGenerate(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonArray("responseModalities") { add("video") }
                    putJsonArray("responseFormat") {
                        add(
                            buildJsonObject {
                                put("type", "video")
                                put("aspectRatio", "16:9")
                                put("resolution", "360p")
                                put("duration", "4s")
                                put("delivery", "uri")
                                put("gcsUri", "gs://video-output/clip.mp4")
                            },
                        )
                    }
                },
            ),
        )

        val body = server.request().bodyJson()
        assertEquals(parseJsonElement("""["video"]"""), body["response_modalities"])
        assertEquals(
            parseJsonElement(
                """[{"type":"video","aspect_ratio":"16:9","resolution":"360p","duration":"4s","delivery":"uri",""" +
                    """"gcs_uri":"gs://video-output/clip.mp4"}]""",
            ),
            body["response_format"],
        )
        val file = result.content.filterIsInstance<Content.File>().single()
        assertEquals("video/mp4", file.mediaType)
        assertEquals(FileData.Bytes(Base64.decode("AAAAIGZ0eXBpc29t")), file.data)
    }

    // --- stateful options ------------------------------------------------------------------------

    @Test
    fun `a previous interaction id goes out and store stays absent by default`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("previousInteractionId", "v1_prev-abc") }))
        assertEquals("v1_prev-abc", sent.body["previous_interaction_id"].string())
        assertNull(sent.body["store"])
    }

    @Test
    fun `store false goes out without a previous id for stateless mode`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("store", false) }))
        assertEquals("false", sent.body["store"].string())
        assertNull(sent.body["previous_interaction_id"])
    }

    @Test
    fun `a previous id with store false sends both and warns about the contradiction`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    put("previousInteractionId", "v1_prev")
                    put("store", false)
                },
            ),
        )
        assertEquals("v1_prev", sent.body["previous_interaction_id"].string())
        assertEquals("false", sent.body["store"].string())
        assertTrue(sent.warnings.filterIsInstance<Warning.Other>().any { it.message.contains("store: false") })
    }

    @Test
    fun `an assistant turn stamped with the previous id is compacted out of the input`() = runTest {
        val previous = "v1_prev-compaction"
        val sent = send(
            CallOptions(
                prompt = listOf(
                    ModelMessage.User(listOf(UserPart.Text("What is the largest city in Spain?"))),
                    ModelMessage.Assistant(
                        listOf(AssistantPart.Text("Madrid is the largest.", providerOptions = googleMeta("interactionId" to previous))),
                    ),
                    ModelMessage.User(listOf(UserPart.Text("And the second largest?"))),
                ),
                providerOptions = googleOptions { put("previousInteractionId", previous) },
            ),
        )
        assertEquals(previous, sent.body["previous_interaction_id"].string())
        assertEquals(
            parseJsonElement(
                """[{"content":[{"text":"What is the largest city in Spain?","type":"text"}],"type":"user_input"},""" +
                    """{"content":[{"text":"And the second largest?","type":"text"}],"type":"user_input"}]""",
            ),
            sent.body["input"],
        )
    }

    @Test
    fun `a replayed reasoning part is a thought step carrying its signature`() = runTest {
        val sent = send(
            CallOptions(
                listOf(
                    ModelMessage.User(listOf(UserPart.Text("q"))),
                    ModelMessage.Assistant(
                        listOf(
                            AssistantPart.Reasoning("", providerOptions = googleMeta("signature" to "sig-roundtrip")),
                            AssistantPart.Text("old answer"),
                        ),
                    ),
                    ModelMessage.User(listOf(UserPart.Text("q2"))),
                ),
            ),
        )
        val thought = sent.body.arr("input")!!.map { it.jsonObject }.single { it["type"].string() == "thought" }
        assertEquals("sig-roundtrip", thought["signature"].string())
    }

    // --- generation config -----------------------------------------------------------------------

    @Test
    fun `thinking level and summaries ride generation_config`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    put("thinkingLevel", "high")
                    put("thinkingSummaries", "auto")
                },
            ),
        )
        val config = sent.body.obj("generation_config")!!
        assertEquals("high", config["thinking_level"].string())
        assertEquals("auto", config["thinking_summaries"].string())
    }

    @Test
    fun `the neutral reasoning effort becomes a thinking level with summaries switched on`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, reasoning = ReasoningEffort.Low))
        assertEquals(
            buildJsonObject {
                put("thinking_level", "low")
                put("thinking_summaries", "auto")
            },
            sent.body["generation_config"],
        )
        sent.warnings.assertNoWarnings()
    }

    @Test
    fun `an explicit thinking level wins over the neutral effort and adds no summaries`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                reasoning = ReasoningEffort.High,
                providerOptions = googleOptions { put("thinkingLevel", "minimal") },
            ),
        )
        assertEquals(buildJsonObject { put("thinking_level", "minimal") }, sent.body["generation_config"])
    }

    @Test
    fun `reasoning None is refused with a warning because minimal is the floor`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, reasoning = ReasoningEffort.None))
        assertNull(sent.body["generation_config"])
        sent.warnings.assertUnsupported("reasoning effort None")
    }

    @Test
    fun `topK is forwarded and the two penalties are warned about, not sent`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, topK = 10, frequencyPenalty = 0.5, presencePenalty = 0.5))
        assertEquals(buildJsonObject { put("top_k", 10) }, sent.body["generation_config"])
        assertEquals(
            listOf(Warning.Unsupported("frequencyPenalty"), Warning.Unsupported("presencePenalty")),
            sent.warnings,
        )
    }

    @Test
    fun `every sampler knob has its snake_case seat and nothing set means no config at all`() = runTest {
        val full = send(
            CallOptions(
                TEST_PROMPT,
                temperature = 0.5,
                topP = 0.9,
                maxOutputTokens = 100,
                seed = 7,
                stopSequences = listOf("END"),
            ),
        )
        assertEquals(
            parseJsonElement("""{"temperature":0.5,"top_p":0.9,"seed":7,"stop_sequences":["END"],"max_output_tokens":100}"""),
            full.body["generation_config"],
        )
        val empty = send(CallOptions(TEST_PROMPT, stopSequences = emptyList()))
        assertNull(empty.body["generation_config"])
    }

    // --- the rest of the model-call options ------------------------------------------------------

    @Test
    fun `service tier and response modalities pass through`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    put("serviceTier", "priority")
                    putJsonArray("responseModalities") { add("image") }
                },
            ),
        )
        assertEquals("priority", sent.body["service_tier"].string())
        assertEquals(buildJsonArray { add("image") }, sent.body["response_modalities"])
        val plain = send(CallOptions(TEST_PROMPT))
        assertNull(plain.body["service_tier"])
    }

    @Test
    fun `the systemInstruction option fills in for a missing system message and loses to a present one`() = runTest {
        val alone = send(CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("systemInstruction", "Be terse.") }))
        assertEquals("Be terse.", alone.body["system_instruction"].string())
        alone.warnings.assertNoWarnings()

        val both = send(
            CallOptions(
                listOf(ModelMessage.System("From the prompt.")) + TEST_PROMPT,
                providerOptions = googleOptions { put("systemInstruction", "From the option.") },
            ),
        )
        assertEquals("From the prompt.", both.body["system_instruction"].string())
        assertTrue(both.warnings.filterIsInstance<Warning.Other>().any { it.message.contains("systemInstruction") })
    }

    @Test
    fun `responseFormat entries are appended after the JSON entry, re-spelled to snake_case`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                responseFormat = ResponseFormat.Json(),
                providerOptions = googleOptions {
                    putJsonArray("responseFormat") {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                put("mimeType", "image/png")
                                put("aspectRatio", "16:9")
                                put("imageSize", "2K")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "audio")
                                put("mimeType", "audio/wav")
                            },
                        )
                    }
                },
            ),
        )
        assertEquals(
            parseJsonElement(
                """[{"type":"text","mime_type":"application/json"},""" +
                    """{"type":"image","mime_type":"image/png","aspect_ratio":"16:9","image_size":"2K"},""" +
                    """{"type":"audio","mime_type":"audio/wav"}]""",
            ),
            sent.body["response_format"],
        )
    }

    @Test
    fun `the deprecated imageConfig becomes an image entry with a warning, unless one was given properly`() = runTest {
        val fallback = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions { putJsonObject("imageConfig") { put("aspectRatio", "1:1") } },
            ),
        )
        assertEquals(
            parseJsonElement("""[{"type":"image","mime_type":"image/png","aspect_ratio":"1:1"}]"""),
            fallback.body["response_format"],
        )
        assertEquals("providerOptions.google.imageConfig", fallback.warnings.filterIsInstance<Warning.Deprecated>().single().setting)

        val ignored = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonObject("imageConfig") { put("aspectRatio", "1:1") }
                    putJsonArray("responseFormat") { add(buildJsonObject { put("type", "image") }) }
                },
            ),
        )
        assertEquals(parseJsonElement("""[{"type":"image"}]"""), ignored.body["response_format"])
        assertTrue(ignored.warnings.filterIsInstance<Warning.Deprecated>().single().message.contains("ignored"))
    }

    @Test
    fun `the request carries only what was set`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT))
        assertEquals(setOf("input", "model"), sent.body.keys)
    }

    // --- the agent branch ------------------------------------------------------------------------

    @Test
    fun `an agent call says agent, a model call says model, never both`() = runTest {
        val agentSent = send(CallOptions(TEST_PROMPT), agent)
        assertEquals(TEST_AGENT, agentSent.body["agent"].string())
        assertNull(agentSent.body["model"])
        assertNull(agentSent.body["generation_config"])
        assertNull(agentSent.body["background"])

        val modelSent = send(CallOptions(TEST_PROMPT))
        assertEquals(TEST_MODEL, modelSent.body["model"].string())
        assertNull(modelSent.body["agent"])
        assertNull(modelSent.body["background"])
    }

    @Test
    fun `a managed agent is addressed the same way`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT), GoogleInteractionsTarget.ManagedAgent("my-agent"))
        assertEquals("my-agent", sent.body["agent"].string())
        assertNull(sent.body["model"])
    }

    @Test
    fun `agent config is re-spelled for deep research and sent bare for dynamic`() = runTest {
        val research = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonObject("agentConfig") {
                        put("type", "deep-research")
                        put("thinkingSummaries", "auto")
                        put("visualization", "auto")
                        put("collaborativePlanning", true)
                    }
                },
            ),
            agent,
        )
        assertEquals(
            parseJsonElement(
                """{"type":"deep-research","thinking_summaries":"auto","visualization":"auto","collaborative_planning":true}""",
            ),
            research.body["agent_config"],
        )
        val dynamic = send(
            CallOptions(TEST_PROMPT, providerOptions = googleOptions { putJsonObject("agentConfig") { put("type", "dynamic") } }),
            agent,
        )
        assertEquals(parseJsonElement("""{"type":"dynamic"}"""), dynamic.body["agent_config"])
    }

    @Test
    fun `an agent takes file_search tools and background without complaint`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                tools = listOf(
                    Tool.ProviderDefined(
                        name = "file_search",
                        id = "google.file_search",
                        args = buildJsonObject { putJsonArray("fileSearchStoreNames") { add("fileSearchStores/x") } },
                    ),
                ),
                providerOptions = googleOptions { put("background", true) },
            ),
            agent,
        )
        assertEquals(
            parseJsonElement("""[{"type":"file_search","file_search_store_names":["fileSearchStores/x"]}]"""),
            sent.body["tools"],
        )
        assertEquals("true", sent.body["background"].string())
        assertTrue(sent.warnings.filterIsInstance<Warning.Other>().none { it.message.contains("tools") })
    }

    @Test
    fun `an agent warns once, naming every generation field it dropped`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                temperature = 0.5,
                topP = 0.9,
                topK = 10,
                frequencyPenalty = 0.5,
                presencePenalty = 0.5,
                providerOptions = googleOptions { put("thinkingLevel", "high") },
            ),
            agent,
        )
        assertNull(sent.body["generation_config"])
        val warning = assertNotNull(sent.warnings.filterIsInstance<Warning.Other>().singleOrNull())
        listOf("temperature", "topP", "topK", "frequencyPenalty", "presencePenalty", "thinkingLevel").forEach {
            assertTrue(warning.message.contains(it), warning.message)
        }
        assertTrue(warning.message.contains("use providerOptions.google.agentConfig instead"))

        val quiet = send(CallOptions(TEST_PROMPT), agent)
        quiet.warnings.assertNoWarnings()
    }

    @Test
    fun `an agent refuses structured output with a warning`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, responseFormat = ResponseFormat.Json(schema = buildJsonObject { put("type", "object") })), agent)
        assertNull(sent.body["response_format"])
        assertTrue(sent.warnings.filterIsInstance<Warning.Other>().any { it.message.contains("structured output") })
    }

    @Test
    fun `an agent keeps its previous interaction id and background flag`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    put("previousInteractionId", "v1_prior-agent-turn")
                    put("background", true)
                },
            ),
            agent,
        )
        assertEquals("v1_prior-agent-turn", sent.body["previous_interaction_id"].string())
        assertEquals(TEST_AGENT, sent.body["agent"].string())
        assertEquals("true", sent.body["background"].string())
    }

    // --- environment -----------------------------------------------------------------------------

    @Test
    fun `an environment string passes through verbatim`() = runTest {
        val remote = send(CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("environment", "remote") }), agent)
        assertEquals("remote", remote.body["environment"].string())
        val existing = send(CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("environment", "env_abc123") }), agent)
        assertEquals("env_abc123", existing.body["environment"].string())
    }

    @Test
    fun `the environment object form serializes its three source kinds and omits a null target`() = runTest {
        val sent = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonObject("environment") {
                        put("type", "remote")
                        putJsonArray("sources") {
                            add(
                                buildJsonObject {
                                    put("type", "inline")
                                    put("content", "note contents")
                                    put("target", "/data/note.txt")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "gcs")
                                    put("source", "gs://example/path")
                                    put("target", "/data/")
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "repository")
                                    put("source", "github://octocat/Hello-World")
                                },
                            )
                        }
                    }
                },
            ),
            agent,
        )
        assertEquals(
            parseJsonElement(
                """{"sources":[{"content":"note contents","target":"/data/note.txt","type":"inline"},""" +
                    """{"source":"gs://example/path","target":"/data/","type":"gcs"},""" +
                    """{"source":"github://octocat/Hello-World","type":"repository"}],"type":"remote"}""",
            ),
            sent.body["environment"],
        )
    }

    @Test
    fun `the environment network is an allow-list with transforms, or disabled`() = runTest {
        val allowlist = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonObject("environment") {
                        put("type", "remote")
                        putJsonObject("network") {
                            putJsonArray("allowlist") {
                                add(
                                    buildJsonObject {
                                        put("domain", "generativelanguage.googleapis.com")
                                        putJsonArray("transform") { add(buildJsonObject { put("x-goog-api-key", "AIza-redacted") }) }
                                    },
                                )
                                add(buildJsonObject { put("domain", "*") })
                            }
                        }
                    }
                },
            ),
            agent,
        )
        assertEquals(
            parseJsonElement(
                """{"network":{"allowlist":[{"domain":"generativelanguage.googleapis.com",""" +
                    """"transform":[{"x-goog-api-key":"AIza-redacted"}]},{"domain":"*"}]},"type":"remote"}""",
            ),
            allowlist.body["environment"],
        )
        val disabled = send(
            CallOptions(
                TEST_PROMPT,
                providerOptions = googleOptions {
                    putJsonObject("environment") {
                        put("type", "remote")
                        put("network", "disabled")
                    }
                },
            ),
            agent,
        )
        assertEquals(parseJsonElement("""{"type":"remote","network":"disabled"}"""), disabled.body["environment"])
    }

    @Test
    fun `a model call drops the environment with a warning`() = runTest {
        val sent = send(CallOptions(TEST_PROMPT, providerOptions = googleOptions { put("environment", "remote") }))
        assertNull(sent.body["environment"])
        assertTrue(sent.warnings.filterIsInstance<Warning.Other>().any { it.message.contains("environment") })
    }
}
