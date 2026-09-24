package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.RealtimeDelegationTarget
import com.sabreware.aide.aisdk.RealtimeServerEvent
import com.sabreware.aide.aisdk.RealtimeSpeaker
import com.sabreware.aide.aisdk.RealtimeUsage
import com.sabreware.aide.aisdk.util.parseJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI Live server frames, against `openai-live-event-mapper.test.ts`.
 *
 * Every row keeps the raw frame on the event, a known-but-malformed frame is an `invalid_server_event`
 * error rather than a best-effort event, and an unknown type — the delegated `response.event` envelope
 * above all — is custom, never an error.
 */
class OpenAILiveEventMapperTest {

    private val model = OpenAILiveModel(modelId = "gpt-live-1", apiKey = "test-key")

    private fun parse(json: String): RealtimeServerEvent = model.parseServerEvent(parseJsonElement(json)).single()

    private fun raw(json: String): JsonElement = parseJsonElement(json)

    @Test
    fun `each documented frame normalizes and keeps the original event`() {
        val cases = listOf<Pair<String, (JsonElement) -> RealtimeServerEvent>>(
            """{"type":"session.started","session":{"id":"session-1","model":"gpt-live-1"}}""" to
                { RealtimeServerEvent.SessionStarted(it, "session-1", RealtimeDelegationTarget.Client) },
            """{"type":"session.closed","session":{"id":"session-1"},"usage":{"seconds":12},"reason":"close_requested"}""" to
                { RealtimeServerEvent.SessionClosed(it, RealtimeUsage(12.0), "close_requested", "session-1") },
            """{"type":"session.closed","usage":{"seconds":0},"reason":"connection_lost"}""" to
                { RealtimeServerEvent.SessionClosed(it, RealtimeUsage(0.0), "connection_lost") },
            """{"type":"session.usage.updated","usage":{"seconds":12},"context_window":{"usage_ratio":0.42}}""" to
                { RealtimeServerEvent.SessionUsage(it, RealtimeUsage(12.0), 0.42) },
            """{"type":"session.output_audio.delta","delta":"AAAA"}""" to
                { RealtimeServerEvent.AudioChunk(it, "AAAA") },
            """{"type":"session.input_transcript.delta","delta":" hello","start_ms":100,"end_ms":200}""" to
                { RealtimeServerEvent.TranscriptFragment(it, RealtimeSpeaker.User, " hello", 100, 200) },
            """{"type":"session.output_transcript.delta","delta":"Hi ","start_ms":120,"end_ms":210}""" to
                { RealtimeServerEvent.TranscriptFragment(it, RealtimeSpeaker.Assistant, "Hi ", 120, 210) },
            """{"type":"session.delegation.created","offset_ms":1000,"delegation":{"id":"opaque-delegation","target":"client","type":"delegation"}}""" to
                { RealtimeServerEvent.DelegationCreated(it, "opaque-delegation", RealtimeDelegationTarget.Client, 1000) },
            """{"type":"session.input_audio.muted","client_event_id":"mute-1"}""" to
                { RealtimeServerEvent.CommandAcknowledged(it, "session.input_audio.mute", "mute-1") },
            """{"type":"session.input_audio.unmuted"}""" to
                { RealtimeServerEvent.CommandAcknowledged(it, "session.input_audio.unmute") },
            """{"type":"session.updated","session":{"id":"session-1"},"client_event_id":"update-1"}""" to
                { RealtimeServerEvent.CommandAcknowledged(it, "session.update", "update-1") },
            """{"type":"session.instructions.appended","client_event_id":"append-1","start_ms":10,"end_ms":20}""" to
                { RealtimeServerEvent.CommandAcknowledged(it, "session.instructions.append", "append-1") },
            """{"type":"session.thinking.appended","start_ms":10,"end_ms":20}""" to
                { RealtimeServerEvent.CommandAcknowledged(it, "session.thinking.append") },
            """{"type":"session.commentary.appended","start_ms":10,"end_ms":20}""" to
                { RealtimeServerEvent.CommandAcknowledged(it, "session.commentary.append") },
            """{"type":"error","error":{"message":"Rejected","code":"immutable_field_update","client_event_id":"update-1"}}""" to
                { RealtimeServerEvent.Error(it, "Rejected", "immutable_field_update", "update-1") },
        )
        cases.forEach { (json, expected) ->
            val frame = raw(json)
            val parsed = model.parseServerEvent(frame).single()
            assertEquals(expected(frame), parsed, json)
            assertSame(frame, parsed.raw)
        }
    }

    @Test
    fun `usage snapshots are cumulative, passed through rather than summed`() {
        listOf(1, 2, 2, 3).forEach { seconds ->
            val event = assertIs<RealtimeServerEvent.SessionUsage>(parse("""{"type":"session.usage.updated","usage":{"seconds":$seconds}}"""))
            assertEquals(RealtimeUsage(seconds.toDouble()), event.usage)
            assertNull(event.contextWindowUsageRatio)
        }
    }

    @Test
    fun `an error's optional code is kept as sent, or absent`() {
        listOf("null" to null, "\"rejected\"" to "rejected").forEach { (code, expected) ->
            val event = assertIs<RealtimeServerEvent.Error>(parse("""{"type":"error","error":{"message":"Rejected","code":$code}}"""))
            assertEquals("Rejected", event.message)
            assertEquals(expected, event.code)
            assertNull(event.clientEventId)
        }
        val absent = assertIs<RealtimeServerEvent.Error>(parse("""{"type":"error","error":{"message":"Rejected"}}"""))
        assertNull(absent.code)
    }

    @Test
    fun `a delegated response envelope and every unknown type stay custom, payload intact`() {
        listOf("\"opaque-delegation\"", "null").forEach { delegationId ->
            val frame = raw(
                """{"type":"response.event","delegation_id":$delegationId,"event":{"type":"response.completed",""" +
                    """"response":{"id":"response-1","output":[],"usage":{"input_tokens":10}},"additional":{"future":true}}}""",
            )
            assertEquals(RealtimeServerEvent.Custom(frame, "response.event"), model.parseServerEvent(frame).single())
        }
        listOf("future.event", "response.completed", "response.output_text.delta").forEach { type ->
            val frame = raw("""{"type":"$type","additional":true}""")
            assertEquals(RealtimeServerEvent.Custom(frame, type), model.parseServerEvent(frame).single())
        }
    }

    @Test
    fun `a known frame that is not the documented shape is an invalid-event error, never readiness or finalization`() {
        listOf(
            "null",
            "\"not an event object\"",
            "{}",
            """{"type":"session.started"}""",
            """{"type":"session.started","session":{"id":""}}""",
            """{"type":"session.closed","session":{"id":"session-1"},"reason":"close_requested"}""",
            """{"type":"session.closed","usage":{"seconds":1}}""",
            """{"type":"session.closed","session":{"id":123},"usage":{"seconds":1},"reason":"expired"}""",
            """{"type":"session.usage.updated","usage":{"seconds":-1}}""",
            """{"type":"session.usage.updated","usage":{"seconds":"1"}}""",
            """{"type":"session.usage.updated","usage":{"seconds":1},"context_window":{"usage_ratio":"0.2"}}""",
            """{"type":"session.output_audio.delta","delta":123}""",
            """{"type":"session.input_transcript.delta","delta":"hi"}""",
            """{"type":"session.output_transcript.delta","delta":"hi","start_ms":20,"end_ms":10}""",
            """{"type":"session.delegation.created","delegation":{}}""",
            """{"type":"session.updated"}""",
            """{"type":"session.input_audio.muted","client_event_id":123}""",
            """{"type":"session.instructions.appended","client_event_id":"append-1"}""",
            """{"type":"error","error":{"message":"bad","code":42}}""",
        ).forEach { json ->
            val frame = raw(json)
            val event = assertIs<RealtimeServerEvent.Error>(model.parseServerEvent(frame).single(), json)
            assertEquals("invalid_server_event", event.code, json)
            assertSame(frame, event.raw)
        }
    }

    @Test
    fun `the confirmed delegation mode is client unless the vendor says responses`() {
        listOf(
            "" to RealtimeDelegationTarget.Client,
            ""","delegation":null""" to RealtimeDelegationTarget.Client,
            ""","delegation":{"type":"client"}""" to RealtimeDelegationTarget.Client,
            ""","delegation":{"type":"responses"}""" to RealtimeDelegationTarget.Provider,
        ).forEach { (delegation, mode) ->
            val frame = raw("""{"type":"session.started","session":{"id":"session-1"$delegation}}""")
            val expected = RealtimeServerEvent.SessionStarted(frame, "session-1", mode)
            assertEquals(listOf(expected), model.parseServerEvent(frame))
            assertEquals(listOf(expected), model.createServerEventParser().parse(frame))
        }
    }

    @Test
    fun `delegation metadata is preserved without inventing a response id`() {
        listOf(null, "opaque-response").forEach { responseId ->
            val frame = raw(
                """{"type":"session.delegation.created","delegation":{"id":"opaque-delegation","target":"responses"""" +
                    (responseId?.let { ""","response_id":"$it"""" } ?: "") + "}}",
            )
            assertEquals(
                RealtimeServerEvent.DelegationCreated(frame, "opaque-delegation", RealtimeDelegationTarget.Provider, null, responseId),
                model.parseServerEvent(frame).single(),
            )
        }
    }

    @Test
    fun `parsers are fresh per connection and pure`() {
        val first = model.createServerEventParser()
        val second = model.createServerEventParser()
        assertNotSame(first, second)
        listOf(
            """{"type":"response.event","event":{"type":"response.created","response":{"id":"response-1"}}}""",
            """{"type":"response.event","event":null}""",
            """{"type":"response.event"}""",
        ).forEach { json ->
            val frame = raw(json)
            listOf(first.parse(frame), second.parse(frame), model.parseServerEvent(frame)).forEach { parsed ->
                assertEquals(listOf(RealtimeServerEvent.Custom(frame, "response.event")), parsed)
            }
        }
    }
}
