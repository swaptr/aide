package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The reference's `provider-utils` cases for the helpers we actually have.
 *
 * Two of them are wire formats in disguise. The JSON instruction is a PROMPT, and a model tuned against
 * those exact words behaves differently against a paraphrase, so "improved" phrasing is an untestable
 * regression — the reference's strings are the specification and these cases are what hold us to them.
 * The extension table is the other: several audio endpoints infer the upload format from the filename,
 * so `audio/mpeg` becoming `audio.mpeg` rather than `audio.mp3` is rejected as an unsupported format
 * for a format the vendor supports.
 *
 * The base64 sniffing cases exist because our prefix decode is ours, not the reference's: we decode a
 * bounded number of whole four-character groups rather than the whole payload, and that arithmetic is
 * invisible until an input is short enough for the trimming to cut into the signature itself.
 */
class ProviderUtilsConformanceTest {

    private val basicSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("age") { put("type", "number") }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("name"))
            add(kotlinx.serialization.json.JsonPrimitive("age"))
        }
    }

    private val basicSchemaJson =
        """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"number"}},""" +
            """"required":["name","age"]}"""

    // -----------------------------------------------------------------------------------------------
    // jsonSchemaInstruction — the reference's injectJsonInstruction
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a prompt and a schema are joined by a blank line, then the schema, then the demand`() {
        assertEquals(
            "Generate a person\n\n" +
                "JSON schema:\n" +
                basicSchemaJson + "\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            jsonSchemaInstruction(basicSchema, "Generate a person"),
        )
    }

    @Test
    fun `a prompt with no schema still demands JSON, without saying there is no schema`() {
        assertEquals(
            "Generate a person\n\nYou MUST answer with JSON.",
            jsonSchemaInstruction(null, "Generate a person"),
        )
    }

    @Test
    fun `a schema with no prompt starts at the schema, with no leading blank line`() {
        assertEquals(
            "JSON schema:\n" +
                basicSchemaJson + "\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            jsonSchemaInstruction(basicSchema),
        )
    }

    @Test
    fun `neither a prompt nor a schema is the bare demand`() {
        assertEquals("You MUST answer with JSON.", jsonSchemaInstruction(null))
    }

    @Test
    fun `an empty prompt is treated as no prompt rather than as a blank first line`() {
        // A leading blank line is a token the model reads as content; it is not free.
        assertEquals(jsonSchemaInstruction(basicSchema), jsonSchemaInstruction(basicSchema, ""))
    }

    @Test
    fun `an empty schema object is still a schema and is still injected`() {
        assertEquals(
            "Generate something\n\n" +
                "JSON schema:\n" +
                "{}\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            jsonSchemaInstruction(buildJsonObject { }, "Generate something"),
        )
    }

    @Test
    fun `property names carrying symbols and emoji reach the model unescaped`() {
        val special = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("special@property") { put("type", "string") }
                putJsonObject("emoji😊") { put("type", "string") }
            }
        }

        assertEquals(
            "JSON schema:\n" +
                """{"type":"object","properties":{"special@property":{"type":"string"},""" +
                """"emoji😊":{"type":"string"}}}""" + "\n" +
                "You MUST answer with a JSON object that matches the JSON schema above.",
            jsonSchemaInstruction(special),
        )
    }

    // -----------------------------------------------------------------------------------------------
    // mediaTypeToExtension — the reference's whole table
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `a media type maps to the extension a vendor's uploader expects`() {
        val table = listOf(
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/webm" to "webm",
            "audio/ogg" to "ogg",
            "audio/opus" to "ogg",
            "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/flac" to "flac",
            "audio/aac" to "aac",
            // Header values arrive in whatever case the server chose to send them.
            "AUDIO/MPEG" to "mp3",
            "AUDIO/MP3" to "mp3",
            // Not a media type at all: the empty string is honest, where a default would name a format.
            "nope" to "",
        )

        assertEquals(table.map { it.second }, table.map { mediaTypeToExtension(it.first) })
    }

    // -----------------------------------------------------------------------------------------------
    // MediaType.detect over base64 — the reference's own literals
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `the reference's recorded base64 prefixes are identified`() {
        assertEquals("image/jpeg", MediaType.detect("/9j/abc123"))
        assertEquals("audio/mpeg", MediaType.detect("//s="))
        assertEquals("image/tiff", MediaType.detect("SUkqAAabc123"))
        assertEquals("image/tiff", MediaType.detect("TU0AKgabc123"))
        assertEquals("image/avif", MediaType.detect("AAAAIGZ0eXBhdmlmabc123"))
        assertEquals("image/heic", MediaType.detect("AAAAIGZ0eXBoZWljabc123"))
    }

    @Test
    fun `a base64 payload only as long as its own signature is still identified`() {
        // Whole four-character groups are what can be decoded, so a six-character input decodes to four
        // bytes only if the trimming rounds the right way — and both of these signatures are four bytes
        // exactly, which is where rounding down loses the last one.
        assertEquals("audio/ogg", MediaType.detect("T2dnUw=="))
        assertEquals("audio/flac", MediaType.detect("ZkxhQw=="))
    }

    @Test
    fun `a base64 payload of a full file header is identified the same as its bytes`() {
        val png = "iVBORw0KGgoAAAANSUhEUg=="
        val gif = "R0lGODlhAQABAAAAACw="

        assertEquals("image/png", MediaType.detect(png))
        assertEquals("image/gif", MediaType.detect(gif))
    }
}
