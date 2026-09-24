package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.AudioFormat
import com.sabreware.aide.aisdk.RealtimeClientEvent
import com.sabreware.aide.aisdk.RealtimeConversationItem
import com.sabreware.aide.aisdk.RealtimeModality
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSessionConfig
import com.sabreware.aide.aisdk.RealtimeToolDefinition
import com.sabreware.aide.aisdk.RealtimeTranscriptionConfig
import com.sabreware.aide.aisdk.RealtimeTurnDetection
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI's realtime frames in both directions, pinned against `openai-realtime-event-mapper.ts` and
 * its test.
 *
 * Exercised through the public model rather than the mapper functions, because the model is the
 * surface a session layer holds, and what these tests guard is that it renders the GA wire — `type`
 * and `model` on every session object, `audio.input` / `audio.output`, `response.output_text.*` — and
 * not the beta it replaced.
 */
class OpenAIRealtimeEventMapperTest {

    private val subject = OpenAIRealtimeModel(
        modelId = "gpt-realtime",
        client = HttpClient(TestServer(TestServer.json("{}")).engine()),
        apiKey = "test-key",
    )

    private fun session(config: RealtimeSessionConfig): JsonObject = subject.buildSessionConfig(config) as JsonObject

    private fun parse(json: String): RealtimeServerEvent = subject.parseServerEvent(parseJsonObject(json)).single()

    private fun serialized(event: RealtimeClientEvent): String = subject.serializeClientEvent(event).toString()

    // --- session config: the reference's own tests -------------------------------------------------

    @Test
    fun `enables input audio transcription with a default model`() {
        val result = session(RealtimeSessionConfig(inputAudioTranscription = RealtimeTranscriptionConfig()))

        assertEquals(parseJsonObject(OpenAIRealtimeFixtures.AUDIO_TRANSCRIPTION_DEFAULT_MODEL), result["audio"])
    }

    @Test
    fun `maps input audio transcription options`() {
        val result = session(
            RealtimeSessionConfig(
                inputAudioTranscription = RealtimeTranscriptionConfig(
                    model = "gpt-4o-mini-transcribe",
                    language = "en",
                    prompt = "Transcribe short voice chat messages.",
                ),
            ),
        )

        assertEquals(parseJsonObject(OpenAIRealtimeFixtures.AUDIO_TRANSCRIPTION_OPTIONS), result["audio"])
    }

    // --- session config: the rest of the GA shape ----------------------------------------------------

    @Test
    fun `every session object names its type and model`() {
        // A `session.update` without the model applies to whatever the socket URL named — the one
        // place the caller's choice can silently go missing.
        assertEquals(
            parseJsonObject("""{"type":"realtime","model":"gpt-realtime"}"""),
            session(RealtimeSessionConfig()),
        )
    }

    @Test
    fun `instructions and output modalities sit at the top level, lower-cased`() {
        val result = session(
            RealtimeSessionConfig(
                instructions = "Speak clearly and briefly.",
                outputModalities = listOf(RealtimeModality.Text),
            ),
        )

        assertEquals("Speak clearly and briefly.", result["instructions"].string())
        assertEquals(listOf("text"), result.arr("output_modalities")!!.map { it.string() })
    }

    @Test
    fun `audio is emitted only when a side of it was configured`() {
        // An empty `audio.input` overrides the server's defaults with nothing, which is audible.
        assertTrue("audio" !in session(RealtimeSessionConfig(instructions = "be brief")))

        val inputOnly = session(RealtimeSessionConfig(inputAudioFormat = AudioFormat("audio/pcm", rate = 24_000)))
        assertEquals(
            parseJsonObject("""{"input":{"format":{"type":"audio/pcm","rate":24000}}}"""),
            inputOnly["audio"],
        )

        val outputOnly = session(
            RealtimeSessionConfig(voice = "marin", outputAudioFormat = AudioFormat("audio/pcmu")),
        )
        assertEquals(
            parseJsonObject("""{"output":{"format":{"type":"audio/pcmu"},"voice":"marin"}}"""),
            outputOnly["audio"],
        )
    }

    @Test
    fun `push-to-talk is an explicit null inside audio input, not an omitted key`() {
        val input = session(RealtimeSessionConfig(turnDetection = RealtimeTurnDetection.Disabled))
            .obj("audio", "input")!!

        // Omitting the key keeps server VAD, which is the opposite of what was asked.
        assertTrue("turn_detection" in input)
        assertEquals(JsonNull, input["turn_detection"])
    }

    @Test
    fun `server and semantic vad map to their own types with the same knobs`() {
        val server = session(
            RealtimeSessionConfig(
                turnDetection = RealtimeTurnDetection.ServerVad(
                    threshold = 0.5,
                    silenceDurationMs = 500,
                    prefixPaddingMs = 300,
                ),
            ),
        ).obj("audio", "input", "turn_detection")!!
        assertEquals(
            parseJsonObject(
                """{"type":"server_vad","threshold":0.5,"silence_duration_ms":500,"prefix_padding_ms":300}""",
            ),
            server,
        )

        val semantic = session(RealtimeSessionConfig(turnDetection = RealtimeTurnDetection.SemanticVad()))
            .obj("audio", "input", "turn_detection")!!
        // Semantic VAD is OpenAI's own detector, so it goes out under its own type rather than folded.
        assertEquals(parseJsonObject("""{"type":"semantic_vad"}"""), semantic)
    }

    @Test
    fun `tools go out as functions with tool_choice auto, and only when there are any`() {
        val declared = session(
            RealtimeSessionConfig(
                tools = listOf(
                    RealtimeToolDefinition(
                        name = "generate_horoscope",
                        description = "Give today's horoscope for an astrological sign.",
                        parameters = parseJsonObject("""{"type":"object","properties":{"sign":{"type":"string"}}}"""),
                    ),
                    RealtimeToolDefinition(name = "bare", parameters = buildJsonObject { }),
                ),
            ),
        )
        assertEquals(
            parseJsonObject(
                """{"type":"function","name":"generate_horoscope",""" +
                    """"description":"Give today's horoscope for an astrological sign.",""" +
                    """"parameters":{"type":"object","properties":{"sign":{"type":"string"}}}}""",
            ),
            declared.arr("tools")!![0],
        )
        // A description the caller did not write is absent, not `null`.
        assertEquals(
            parseJsonObject("""{"type":"function","name":"bare","parameters":{}}"""),
            declared.arr("tools")!![1],
        )
        assertEquals("auto", declared["tool_choice"].string())

        val none = session(RealtimeSessionConfig(tools = emptyList()))
        assertTrue("tools" !in none)
        assertTrue("tool_choice" !in none)
    }

    @Test
    fun `provider options under openai merge onto the session last`() {
        val result = session(
            RealtimeSessionConfig(
                instructions = "from the config",
                providerOptions = mapOf(
                    OPENAI_PROVIDER_ID to buildJsonObject {
                        put("max_output_tokens", 512)
                        put("instructions", "from the options")
                    },
                    "someone-else" to buildJsonObject { put("ignored", true) },
                ),
            ),
        )

        // The namespace reaches fields this port has not modelled, and wins over ones it has.
        assertEquals("512", result["max_output_tokens"].toString())
        assertEquals("from the options", result["instructions"].string())
        assertTrue("ignored" !in result)
    }

    @Test
    fun `output transcription is not a switch OpenAI has, so it sends nothing`() {
        val result = session(
            RealtimeSessionConfig(outputAudioTranscription = RealtimeTranscriptionConfig(model = "whisper-1")),
        )

        // The model's own speech is transcribed unconditionally; setting it merely states the default.
        assertTrue("audio" !in result)
    }

    // --- client events -------------------------------------------------------------------------------

    @Test
    fun `session update wraps the session object`() {
        val frame = subject.serializeClientEvent(
            RealtimeClientEvent.SessionUpdate(RealtimeSessionConfig(voice = "marin")),
        )

        // Whole-frame: `voice` lives under `audio.output` on the GA wire, and a per-key read of a
        // top-level `voice` would pass against the beta shape this must not produce.
        assertEquals(
            parseJsonObject(
                """{"type":"session.update","session":{"type":"realtime","model":"gpt-realtime",""" +
                    """"audio":{"output":{"voice":"marin"}}}}""",
            ),
            frame,
        )
    }

    @Test
    fun `audio buffer events serialize into OpenAI's frame names`() {
        assertEquals(
            """{"type":"input_audio_buffer.append","audio":"AQID"}""",
            serialized(RealtimeClientEvent.InputAudioAppend("AQID")),
        )
        assertEquals("""{"type":"input_audio_buffer.commit"}""", serialized(RealtimeClientEvent.InputAudioCommit))
        assertEquals("""{"type":"input_audio_buffer.clear"}""", serialized(RealtimeClientEvent.InputAudioClear))
        assertEquals("""{"type":"response.cancel"}""", serialized(RealtimeClientEvent.ResponseCancel))
    }

    @Test
    fun `conversation items are created in OpenAI's item shapes`() {
        assertEquals(
            """{"type":"conversation.item.create","item":{"type":"message","role":"user",""" +
                """"content":[{"type":"input_text","text":"hi"}]}}""",
            serialized(RealtimeClientEvent.ConversationItemCreate(RealtimeConversationItem.TextMessage("hi"))),
        )
        assertEquals(
            """{"type":"conversation.item.create","item":{"type":"message","role":"user",""" +
                """"content":[{"type":"input_audio","audio":"AQID"}]}}""",
            serialized(RealtimeClientEvent.ConversationItemCreate(RealtimeConversationItem.AudioMessage("AQID"))),
        )
        // Routed by `call_id` alone: OpenAI has no field for the name, so it is not sent.
        assertEquals(
            """{"type":"conversation.item.create",""" +
                """"item":{"type":"function_call_output","call_id":"c1","output":"{}"}}""",
            serialized(
                RealtimeClientEvent.ConversationItemCreate(
                    RealtimeConversationItem.FunctionCallOutput(callId = "c1", output = "{}", name = "lookup"),
                ),
            ),
        )
    }

    @Test
    fun `truncate carries the item, the part and the heard duration`() {
        assertEquals(
            """{"type":"conversation.item.truncate","item_id":"item_1234","content_index":0,"audio_end_ms":1500}""",
            serialized(
                RealtimeClientEvent.ConversationItemTruncate(itemId = "item_1234", contentIndex = 0, audioEndMs = 1500),
            ),
        )
    }

    @Test
    fun `response create carries a response object only when there is something to say in it`() {
        assertEquals("""{"type":"response.create"}""", serialized(RealtimeClientEvent.ResponseCreate()))

        assertEquals(
            parseJsonObject(
                """{"type":"response.create","response":{"output_modalities":["text"],""" +
                    """"instructions":"Analyze the conversation so far.","metadata":{"topic":"classification"}}}""",
            ),
            subject.serializeClientEvent(
                RealtimeClientEvent.ResponseCreate(
                    modalities = listOf(RealtimeModality.Text),
                    instructions = "Analyze the conversation so far.",
                    metadata = buildJsonObject { put("topic", "classification") },
                ),
            ),
        )
    }

    // --- server events -------------------------------------------------------------------------------

    @Test
    fun `session and input buffer frames map onto the neutral events`() {
        val created = assertIs<RealtimeServerEvent.SessionCreated>(
            parse("""{"type":"session.created","session":{"id":"sess_1"}}"""),
        )
        assertEquals("sess_1", created.sessionId)
        assertIs<RealtimeServerEvent.SessionUpdated>(parse("""{"type":"session.updated","session":{}}"""))

        assertEquals(
            "item_1",
            assertIs<RealtimeServerEvent.SpeechStarted>(
                parse("""{"type":"input_audio_buffer.speech_started","item_id":"item_1","audio_start_ms":0}"""),
            ).itemId,
        )
        assertEquals(
            "item_1",
            assertIs<RealtimeServerEvent.SpeechStopped>(
                parse("""{"type":"input_audio_buffer.speech_stopped","item_id":"item_1","audio_end_ms":900}"""),
            ).itemId,
        )
        val committed = assertIs<RealtimeServerEvent.AudioCommitted>(
            parse("""{"type":"input_audio_buffer.committed","item_id":"item_2","previous_item_id":"item_1"}"""),
        )
        assertEquals("item_2", committed.itemId)
        assertEquals("item_1", committed.previousItemId)
    }

    @Test
    fun `a conversation item is named by its own id before the flat one`() {
        val added = assertIs<RealtimeServerEvent.ConversationItemAdded>(
            parse("""{"type":"conversation.item.added","item":{"id":"item_9","type":"message","role":"user"}}"""),
        )
        assertEquals("item_9", added.itemId)
        assertEquals("message", (added.item as JsonObject)["type"].string())

        val flat = assertIs<RealtimeServerEvent.ConversationItemAdded>(
            parse("""{"type":"conversation.item.added","item_id":"item_8","item":{"type":"message"}}"""),
        )
        assertEquals("item_8", flat.itemId)
    }

    @Test
    fun `an input transcription completes with its text, or with an empty one`() {
        val heard = assertIs<RealtimeServerEvent.InputTranscriptionCompleted>(
            parse(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1",""" +
                    """"content_index":0,"transcript":"Hello there."}""",
            ),
        )
        assertEquals("Hello there.", heard.transcript)

        // The completion still closes the user's bubble when nothing was heard, as upstream.
        val silent = assertIs<RealtimeServerEvent.InputTranscriptionCompleted>(
            parse("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1"}"""),
        )
        assertEquals("", silent.transcript)
    }

    @Test
    fun `a response is identified by the nested id first and finishes with a status`() {
        assertEquals(
            "resp_1",
            assertIs<RealtimeServerEvent.ResponseCreated>(
                parse("""{"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}"""),
            ).responseId,
        )
        val done = assertIs<RealtimeServerEvent.ResponseDone>(
            parse("""{"type":"response.done","response":{"id":"resp_1","status":"cancelled"}}"""),
        )
        assertEquals("resp_1", done.responseId)
        assertEquals("cancelled", done.status)

        // A `response.done` is the end of an answer whatever else it says.
        val bare = assertIs<RealtimeServerEvent.ResponseDone>(
            parse("""{"type":"response.done","response_id":"resp_2"}"""),
        )
        assertEquals("resp_2", bare.responseId)
        assertEquals("completed", bare.status)
    }

    @Test
    fun `output items and content parts carry both correlation ids`() {
        val added = assertIs<RealtimeServerEvent.OutputItemAdded>(
            parse(
                """{"type":"response.output_item.added","response_id":"resp_1","output_index":0,""" +
                    """"item":{"id":"item_3"}}""",
            ),
        )
        assertEquals("resp_1" to "item_3", added.responseId to added.itemId)
        val itemDone = assertIs<RealtimeServerEvent.OutputItemDone>(
            parse("""{"type":"response.output_item.done","response_id":"resp_1","item_id":"item_3"}"""),
        )
        assertEquals("item_3", itemDone.itemId)

        val part = assertIs<RealtimeServerEvent.ContentPartAdded>(
            parse(
                """{"type":"response.content_part.added","response_id":"resp_1","item_id":"item_3",""" +
                    """"content_index":0}""",
            ),
        )
        assertEquals("resp_1", part.responseId)
        assertIs<RealtimeServerEvent.ContentPartDone>(
            parse("""{"type":"response.content_part.done","response_id":"resp_1","item_id":"item_3"}"""),
        )
    }

    @Test
    fun `audio, transcript and text output stream under the GA names`() {
        val audio = assertIs<RealtimeServerEvent.AudioDelta>(
            parse(
                """{"type":"response.output_audio.delta","response_id":"resp_1","item_id":"item_3","delta":"AQID"}""",
            ),
        )
        assertEquals("AQID", audio.delta)
        assertIs<RealtimeServerEvent.AudioDone>(
            parse("""{"type":"response.output_audio.done","response_id":"resp_1","item_id":"item_3"}"""),
        )

        val transcript = assertIs<RealtimeServerEvent.AudioTranscriptDelta>(
            parse(
                """{"type":"response.output_audio_transcript.delta","response_id":"resp_1","item_id":"item_3",""" +
                    """"delta":"Hel"}""",
            ),
        )
        assertEquals("Hel", transcript.delta)
        assertEquals(
            "Hello.",
            assertIs<RealtimeServerEvent.AudioTranscriptDone>(
                parse(
                    """{"type":"response.output_audio_transcript.done","response_id":"resp_1","item_id":"item_3",""" +
                        """"transcript":"Hello."}""",
                ),
            ).transcript,
        )

        val text = assertIs<RealtimeServerEvent.TextDelta>(
            parse("""{"type":"response.output_text.delta","response_id":"resp_1","item_id":"item_3","delta":"Hi"}"""),
        )
        assertEquals("Hi", text.delta)
        assertEquals(
            "Hi there.",
            assertIs<RealtimeServerEvent.TextDone>(
                parse(
                    """{"type":"response.output_text.done","response_id":"resp_1","item_id":"item_3",""" +
                        """"text":"Hi there."}""",
                ),
            ).text,
        )
        // The beta spelling is not aliased: a client that connected to the wrong endpoint should see it.
        assertIs<RealtimeServerEvent.Custom>(
            parse("""{"type":"response.text.delta","response_id":"resp_1","item_id":"item_3","delta":"Hi"}"""),
        )
    }

    @Test
    fun `function call arguments stream by call id and settle with the name`() {
        val delta = assertIs<RealtimeServerEvent.FunctionCallArgumentsDelta>(
            parse(
                """{"type":"response.function_call_arguments.delta","response_id":"resp_1","item_id":"item_4",""" +
                    """"call_id":"call_1","delta":"{\"sign\""}""",
            ),
        )
        assertEquals("call_1", delta.callId)
        assertEquals("{\"sign\"", delta.delta)

        val done = assertIs<RealtimeServerEvent.FunctionCallArgumentsDone>(
            parse(
                """{"type":"response.function_call_arguments.done","response_id":"resp_1","item_id":"item_4",""" +
                    """"call_id":"call_1","name":"generate_horoscope","arguments":"{\"sign\":\"Aquarius\"}"}""",
            ),
        )
        assertEquals("generate_horoscope", done.name)
        // A JSON string, never a parsed object: the caller decides when to parse it.
        assertEquals("""{"sign":"Aquarius"}""", done.arguments)
    }

    @Test
    fun `an error reads from the nested shape first, then the flat one, then says so`() {
        val nested = assertIs<RealtimeServerEvent.Error>(
            parse(
                """{"type":"error","event_id":"event_1","error":{"type":"invalid_request_error",""" +
                    """"code":"invalid_event","message":"The 'type' field is missing.","param":null}}""",
            ),
        )
        assertEquals("The 'type' field is missing.", nested.message)
        assertEquals("invalid_event", nested.code)

        val flat = assertIs<RealtimeServerEvent.Error>(parse("""{"type":"error","message":"boom","code":"500"}"""))
        assertEquals("boom", flat.message)
        assertEquals("500", flat.code)

        val bare = assertIs<RealtimeServerEvent.Error>(parse("""{"type":"error"}"""))
        assertEquals("Unknown error", bare.message)
        assertNull(bare.code)
    }

    @Test
    fun `an unmodelled frame keeps its payload rather than becoming a synthetic event`() {
        val rateLimits = assertIs<RealtimeServerEvent.Custom>(
            parse("""{"type":"rate_limits.updated","rate_limits":[{"name":"requests","limit":1000}]}"""),
        )
        assertEquals("rate_limits.updated", rateLimits.rawType)
        assertTrue("rate_limits" in rateLimits.raw as JsonObject)

        // A frame this contract models but which lacks the id it requires is malformed for its type;
        // reporting it whole beats inventing an empty id a session layer would correlate on.
        val malformed = assertIs<RealtimeServerEvent.Custom>(
            parse("""{"type":"response.output_audio.delta","item_id":"item_3","delta":"AQID"}"""),
        )
        assertEquals("response.output_audio.delta", malformed.rawType)

        // Not even an object: still reported, with the raw payload, rather than thrown.
        val scalar = subject.parseServerEvent(JsonPrimitive("ping")).single()
        assertEquals("", assertIs<RealtimeServerEvent.Custom>(scalar).rawType)
    }

    // --- 39535af: client event ids, correlated errors, the Live commands refused here ----------------

    @Test
    fun `a session update or audio append carries the caller's event id, empty string included`() {
        listOf("client-event", "", null).forEach { eventId ->
            val suffix = eventId?.let { ""","event_id":"$it"""" } ?: ""
            assertEquals(
                parseJsonObject(
                    """{"type":"session.update","session":{"type":"realtime","model":"gpt-realtime","instructions":"Be concise."}$suffix}""",
                ),
                subject.serializeClientEvent(
                    RealtimeClientEvent.SessionUpdate(RealtimeSessionConfig(instructions = "Be concise."), eventId = eventId),
                ),
            )
            assertEquals(
                parseJsonObject("""{"type":"input_audio_buffer.append","audio":"AA=="$suffix}"""),
                subject.serializeClientEvent(RealtimeClientEvent.InputAudioAppend("AA==", eventId = eventId)),
            )
        }
    }

    @Test
    fun `a nested error event_id names the client frame that was rejected`() {
        listOf("client-event" to "client-event", "" to "", null to null).forEach { (eventId, expected) ->
            val nested = eventId?.let { ""","event_id":"$it"""" } ?: ""
            val error = assertIs<RealtimeServerEvent.Error>(
                parse(
                    """{"type":"error","event_id":"server-event","error":{"message":"Invalid audio","code":"invalid_audio"$nested,""" +
                        """"client_event_id":"live-client-event"}}""",
                ),
            )
            assertEquals("Invalid audio", error.message)
            assertEquals("invalid_audio", error.code)
            // The frame's own top-level `event_id` is the server's and is never read as the client's.
            assertEquals(expected, error.clientEventId)
        }
        val nulled = assertIs<RealtimeServerEvent.Error>(
            parse("""{"type":"error","event_id":"server-event","error":{"message":"Invalid audio","code":"invalid_audio","event_id":null}}"""),
        )
        assertNull(nulled.clientEventId)
    }

    @Test
    fun `missing or null error fields fall back, top-level ones included`() {
        listOf(
            """{"type":"error","event_id":"server-event"}""",
            """{"type":"error","event_id":"server-event","error":null}""",
            """{"type":"error","event_id":"server-event","error":{}}""",
            """{"type":"error","event_id":"server-event","error":{"message":null,"code":null,"event_id":null}}""",
        ).forEach { json ->
            val error = assertIs<RealtimeServerEvent.Error>(parse(json), json)
            assertEquals("Unknown error", error.message)
            assertNull(error.code)
            assertNull(error.clientEventId)
        }
        val flat = assertIs<RealtimeServerEvent.Error>(
            parse("""{"type":"error","message":"Invalid request","code":"invalid_request","error":{"message":null,"code":null}}"""),
        )
        assertEquals("Invalid request", flat.message)
        assertEquals("invalid_request", flat.code)
        assertNull(flat.clientEventId)
    }

    @Test
    fun `the continuous-session commands belong to the Live API and are refused here`() {
        listOf(
            RealtimeClientEvent.SessionStart(RealtimeSessionConfig()),
            RealtimeClientEvent.SessionClose(),
            RealtimeClientEvent.InputAudioMute(),
            RealtimeClientEvent.InputAudioUnmute(),
            RealtimeClientEvent.ContextAppend("context"),
        ).forEach { event ->
            assertFailsWith<UnsupportedFunctionalityError>("$event") { subject.serializeClientEvent(event) }
        }
    }
}
