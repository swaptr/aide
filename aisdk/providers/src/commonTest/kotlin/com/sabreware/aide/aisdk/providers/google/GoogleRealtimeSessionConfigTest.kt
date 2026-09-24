package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeModality
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeToolDefinition
import com.sabreware.aide.aisdk.RealtimeTranscriptionConfig
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonElement
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The client half of `realtime/google-realtime-event-mapper.test.ts` — what a session sends — and the
 * `buildGoogleSessionConfig` snapshots: the setup frame that is also baked into an ephemeral token.
 */
class GoogleRealtimeSessionConfigTest {

    private val modelId = "gemini-2.0-flash-live-001"

    private fun mapper() = GoogleRealtimeEventMapper()

    private fun serialize(event: RealtimeClientEvent, model: String = modelId) =
        mapper().serializeClientEvent(event, model)

    private fun weatherTool(vararg required: String) = RealtimeToolDefinition(
        name = "getWeather",
        description = "Get weather",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
            if (required.isNotEmpty()) put("required", JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        },
    )

    // --- serializeClientEvent ------------------------------------------------------------------------

    @Test
    fun `a session update is the setup frame, audio by default`() {
        val frame = serialize(RealtimeClientEvent.SessionUpdate(RealtimeSessionConfig()))

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.BARE_SETUP), frame)
    }

    @Test
    fun `a full session config becomes Google's setup, input format and all ignored where it has no field`() {
        val frame = serialize(
            RealtimeClientEvent.SessionUpdate(
                RealtimeSessionConfig(
                    instructions = "Be helpful",
                    voice = "Puck",
                    outputModalities = listOf(RealtimeModality.Audio, RealtimeModality.Text),
                    inputAudioFormat = AudioFormat("audio/pcm", rate = 24_000),
                    tools = listOf(weatherTool()),
                ),
            ),
        )

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.FULL_SETUP), frame)
    }

    @Test
    fun `live translation rides generationConfig, beside both transcription switches`() {
        val frame = serialize(
            RealtimeClientEvent.SessionUpdate(
                RealtimeSessionConfig(
                    inputAudioTranscription = RealtimeTranscriptionConfig(),
                    outputAudioTranscription = RealtimeTranscriptionConfig(),
                    providerOptions = mapOf(
                        GOOGLE_PROVIDER_ID to buildJsonObject {
                            putJsonObject("translationConfig") {
                                put("targetLanguageCode", "pl")
                                put("echoTargetLanguage", true)
                            }
                        },
                    ),
                ),
            ),
            model = "gemini-3.5-live-translate-preview",
        )

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.TRANSLATION_SETUP), frame)
    }

    @Test
    fun `appended audio is a realtimeInput blob labelled with its rate`() {
        val frame = serialize(RealtimeClientEvent.InputAudioAppend("base64data"), model = "model")

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.audioAppend(16_000)), frame)
    }

    @Test
    fun `a commit is audioStreamEnd`() {
        assertEquals(
            parseJsonObject(GoogleRealtimeFixtures.AUDIO_STREAM_END),
            serialize(RealtimeClientEvent.InputAudioCommit, model = "model"),
        )
    }

    @Test
    fun `the events Gemini Live has no frame for serialize as null, not a guess`() {
        // Null lets a session layer tell "nothing to send" from "the vendor rejected it".
        assertEquals(JsonNull, serialize(RealtimeClientEvent.InputAudioClear, model = "model"))
        assertEquals(JsonNull, serialize(RealtimeClientEvent.ResponseCreate(), model = "model"))
        assertEquals(JsonNull, serialize(RealtimeClientEvent.ResponseCancel, model = "model"))
        assertEquals(
            JsonNull,
            serialize(
                RealtimeClientEvent.ConversationItemTruncate(itemId = "item_1", contentIndex = 0, audioEndMs = 1000),
                model = "model",
            ),
        )
        assertEquals(
            JsonNull,
            serialize(
                RealtimeClientEvent.ConversationItemCreate(RealtimeConversationItem.AudioMessage("AQID")),
                model = "model",
            ),
        )
    }

    @Test
    fun `a typed message is realtimeInput text`() {
        val frame = serialize(
            RealtimeClientEvent.ConversationItemCreate(RealtimeConversationItem.TextMessage("hello")),
            model = "model",
        )

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.TEXT_INPUT), frame)
    }

    @Test
    fun `a tool result is a toolResponse with the parsed output`() {
        val frame = serialize(
            RealtimeClientEvent.ConversationItemCreate(
                RealtimeConversationItem.FunctionCallOutput(callId = "call_1", output = """{"temp":72}""", name = "getWeather"),
            ),
            model = "model",
        )

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.toolResponse("""{"temp":72}""")), frame)
    }

    @Test
    fun `a tool result that is not an object is wrapped so the response stays one`() {
        // `functionResponse.response` is a protobuf Struct: a bare string, number, array, null or
        // boolean on the wire closed the socket with 1007. The field's docstring names the `output` key.
        for ((output, expected) in listOf(
            "\"SEARCH_UNAVAILABLE\"" to """{"output":"SEARCH_UNAVAILABLE"}""",
            "42" to """{"output":42}""",
            """["a","b"]""" to """{"output":["a","b"]}""",
            "null" to """{"output":null}""",
            "false" to """{"output":false}""",
        )) {
            val frame = serialize(
                RealtimeClientEvent.ConversationItemCreate(
                    RealtimeConversationItem.FunctionCallOutput(callId = "call_1", output = output, name = "getWeather"),
                ),
                model = "model",
            )

            assertEquals(parseJsonObject(GoogleRealtimeFixtures.toolResponse(expected)), frame, output)
        }
    }

    @Test
    fun `an unparseable tool output is kept as text instead of becoming an empty object`() {
        // `{}` told the model the tool had returned an empty object, with nothing thrown and the socket
        // still up — a wrong answer with nothing to notice.
        val frame = serialize(
            RealtimeClientEvent.ConversationItemCreate(
                RealtimeConversationItem.FunctionCallOutput(callId = "call_1", output = "{", name = "getWeather"),
            ),
            model = "model",
        )

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.toolResponse("""{"output":"{"}""")), frame)
    }

    // --- buildGoogleSessionConfig --------------------------------------------------------------------

    @Test
    fun `no config is the model path and audio output`() {
        assertEquals(
            parseJsonObject(GoogleRealtimeFixtures.MODEL_PATH_ONLY),
            buildGoogleSessionConfig(null, "gemini-2.0-flash"),
        )
    }

    @Test
    fun `instructions and a voice`() {
        assertEquals(
            parseJsonObject(GoogleRealtimeFixtures.INSTRUCTIONS_AND_VOICE),
            buildGoogleSessionConfig(RealtimeSessionConfig(instructions = "Be helpful", voice = "Puck"), "gemini-2.0-flash"),
        )
    }

    @Test
    fun `tools become function declarations`() {
        val setup = buildGoogleSessionConfig(RealtimeSessionConfig(tools = listOf(weatherTool("city"))), "gemini-2.0-flash")

        assertEquals(parseJsonElement(GoogleRealtimeFixtures.TOOLS_SNAPSHOT), setup["tools"])
    }

    @Test
    fun `local schema references are preserved, as on the chat request`() {
        val tool = RealtimeToolDefinition(
            name = "formatDate",
            description = "Format a date",
            parameters = parseJsonObject(
                """{"type":"object","properties":{"locale":{"${'$'}ref":"#/${'$'}defs/Locale"}},""" +
                    """"required":["locale"],"${'$'}defs":{"Locale":{"type":"string","enum":["de","en"]}}}""",
            ),
        )

        val setup = buildGoogleSessionConfig(RealtimeSessionConfig(tools = listOf(tool)), "gemini-2.0-flash")

        assertEquals(parseJsonElement(GoogleRealtimeFixtures.PRESERVED_REFERENCE_TOOLS), setup["tools"])
    }

    @Test
    fun `output modalities are upper-cased`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(outputModalities = listOf(RealtimeModality.Audio, RealtimeModality.Text)),
            "model",
        )

        assertEquals(listOf("AUDIO", "TEXT"), setup.obj("generationConfig")!!.arr("responseModalities")!!.map { it.string() })
    }

    @Test
    fun `either transcription switch is an empty object`() {
        assertEquals(
            JsonObject(emptyMap()),
            buildGoogleSessionConfig(RealtimeSessionConfig(inputAudioTranscription = RealtimeTranscriptionConfig()), "model")
                .obj("inputAudioTranscription"),
        )
        assertEquals(
            JsonObject(emptyMap()),
            buildGoogleSessionConfig(RealtimeSessionConfig(outputAudioTranscription = RealtimeTranscriptionConfig()), "model")
                .obj("outputAudioTranscription"),
        )
    }

    @Test
    fun `the google namespace's translationConfig is merged into generationConfig`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(
                providerOptions = mapOf(
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonObject("translationConfig") {
                            put("targetLanguageCode", "es")
                            put("echoTargetLanguage", true)
                        }
                    },
                ),
            ),
            "gemini-3.5-live-translate-preview",
        )

        assertEquals(parseJsonObject(GoogleRealtimeFixtures.TRANSLATION_ONLY), setup)
    }

    @Test
    fun `a raw generationConfig in the provider options is kept and the translation merged into it`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(
                providerOptions = mapOf(
                    "generationConfig" to parseJsonObject("""{"responseModalities":["AUDIO"],"temperature":0.2}"""),
                    GOOGLE_PROVIDER_ID to buildJsonObject {
                        putJsonObject("translationConfig") { put("targetLanguageCode", "fr") }
                    },
                ),
            ),
            "gemini-3.5-live-translate-preview",
        )

        assertEquals(
            parseJsonObject(
                """{"responseModalities":["AUDIO"],"temperature":0.2,"translationConfig":{"targetLanguageCode":"fr"}}""",
            ),
            setup.obj("generationConfig"),
        )
    }

    // --- Gemini 3.8 Live: thinking and tool behaviour --------------------------------------------

    private val thinkingModel = "gemini-3.8-live-extended-thinking"

    private fun googleOptions(json: String) = RealtimeSessionConfig(
        providerOptions = mapOf(GOOGLE_PROVIDER_ID to parseJsonObject(json)),
    )

    @Test
    fun `the google namespace's thinkingConfig is merged into generationConfig`() {
        val setup = buildGoogleSessionConfig(
            googleOptions("""{"thinkingConfig":{"thinkingLevel":"high","includeThoughts":true}}"""),
            thinkingModel,
        )

        assertEquals(
            parseJsonObject(
                """{"model":"models/gemini-3.8-live-extended-thinking","generationConfig":{"responseModalities":["AUDIO"],""" +
                    """"thinkingConfig":{"thinkingLevel":"high","includeThoughts":true}}}""",
            ),
            setup,
        )
    }

    @Test
    fun `a background-reasoning Live model defaults to thinkingLevel low`() {
        // Google requires exactly one of thinkingLevel / thinkingBudget in the setup of such a model,
        // so a session opened with no thinking config would be refused at the handshake.
        for (modelId in listOf(thinkingModel, "models/$thinkingModel")) {
            assertEquals(
                parseJsonObject(
                    """{"model":"models/gemini-3.8-live-extended-thinking","generationConfig":""" +
                        """{"responseModalities":["AUDIO"],"thinkingConfig":{"thinkingLevel":"low"}}}""",
                ),
                buildGoogleSessionConfig(null, modelId),
            )
        }
        assertEquals(
            parseJsonObject("""{"responseModalities":["AUDIO"],"thinkingConfig":{"thinkingLevel":"low"}}"""),
            buildGoogleSessionConfig(
                RealtimeSessionConfig(outputModalities = listOf(RealtimeModality.Audio), instructions = "Be brief."),
                thinkingModel,
            ).obj("generationConfig"),
        )
    }

    @Test
    fun `no thinkingConfig is sent to a Live model without background reasoning`() {
        // Every other Live model REJECTS thinkingConfig, so the default is scoped to the ones that need it.
        for (modelId in listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-3.5-live-translate-preview")) {
            assertEquals(
                parseJsonObject("""{"responseModalities":["AUDIO"]}"""),
                buildGoogleSessionConfig(RealtimeSessionConfig(outputModalities = listOf(RealtimeModality.Audio)), modelId)
                    .obj("generationConfig"),
                modelId,
            )
        }
    }

    @Test
    fun `a thinkingBudget suppresses the default level - the two are mutually exclusive`() {
        val setup = buildGoogleSessionConfig(googleOptions("""{"thinkingConfig":{"thinkingBudget":256}}"""), thinkingModel)

        assertEquals(
            parseJsonObject("""{"responseModalities":["AUDIO"],"thinkingConfig":{"thinkingBudget":256}}"""),
            setup.obj("generationConfig"),
        )
    }

    @Test
    fun `the default thinkingLevel survives a raw generationConfig provider option`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(providerOptions = mapOf("generationConfig" to parseJsonObject("""{"temperature":0.2}"""))),
            thinkingModel,
        )

        assertEquals(
            parseJsonObject("""{"temperature":0.2,"thinkingConfig":{"thinkingLevel":"low"}}"""),
            setup.obj("generationConfig"),
        )
    }

    @Test
    fun `thinking config is merged into a raw generationConfig provider option`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(
                providerOptions = mapOf(
                    "generationConfig" to parseJsonObject("""{"responseModalities":["AUDIO"],"temperature":0.2}"""),
                    GOOGLE_PROVIDER_ID to parseJsonObject("""{"thinkingConfig":{"thinkingBudget":512}}"""),
                ),
            ),
            thinkingModel,
        )

        assertEquals(
            parseJsonObject("""{"responseModalities":["AUDIO"],"temperature":0.2,"thinkingConfig":{"thinkingBudget":512}}"""),
            setup.obj("generationConfig"),
        )
    }

    @Test
    fun `translation and thinking config merge together into generationConfig`() {
        val setup = buildGoogleSessionConfig(
            googleOptions("""{"translationConfig":{"targetLanguageCode":"fr"},"thinkingConfig":{"thinkingBudget":1024}}"""),
            thinkingModel,
        )

        assertEquals(
            parseJsonObject(
                """{"responseModalities":["AUDIO"],"translationConfig":{"targetLanguageCode":"fr"},""" +
                    """"thinkingConfig":{"thinkingBudget":1024}}""",
            ),
            setup.obj("generationConfig"),
        )
    }

    @Test
    fun `defaultToolBehavior is stamped onto every function declaration`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(
                tools = listOf(
                    weatherTool("city"),
                    RealtimeToolDefinition(name = "getTime", parameters = parseJsonObject("""{"type":"object","properties":{}}""")),
                ),
                providerOptions = mapOf(GOOGLE_PROVIDER_ID to parseJsonObject("""{"defaultToolBehavior":"BLOCKING"}""")),
            ),
            "gemini-3.8-live",
        )

        val declarations = setup.arr("tools")!!.single().jsonObject.arr("functionDeclarations")!!
        assertEquals(listOf("BLOCKING", "BLOCKING"), declarations.map { it.jsonObject["behavior"].string() })
    }

    @Test
    fun `behavior is omitted from function declarations when defaultToolBehavior is unset`() {
        val setup = buildGoogleSessionConfig(
            RealtimeSessionConfig(
                tools = listOf(
                    RealtimeToolDefinition(name = "getWeather", parameters = parseJsonObject("""{"type":"object","properties":{}}""")),
                ),
            ),
            "gemini-3.8-live",
        )

        assertNull(setup.arr("tools")!!.single().jsonObject.arr("functionDeclarations")!!.single().jsonObject["behavior"])
    }

    @Test
    fun `a model already spelled as a resource path is left alone`() {
        assertEquals("models/gemini-2.0-flash", buildGoogleSessionConfig(null, "models/gemini-2.0-flash")["model"].string())
    }
}
