package com.sabreware.aide.aisdk.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The reference's `fix-json` suite, ported case for case.
 *
 * Kept whole rather than sampled because the cases are the specification: each one is a position in the
 * JSON grammar a stream can stop at, and the interesting ones — a number cut at its exponent, a unicode
 * escape cut at two digits, a key with no value — are exactly the ones a hand-written repair pass gets
 * wrong by closing the document over a value it should have dropped.
 */
class FixJsonTest {

    private fun fixed(input: String, expected: String) = assertEquals(expected, fixJson(input))

    @Test
    fun `empty input`() = fixed("", "")

    @Test
    fun `incomplete literals are completed`() {
        fixed("nul", "null")
        fixed("t", "true")
        fixed("fals", "false")
    }

    @Test
    fun `a number is truncated back to its last digit`() {
        fixed("12.", "12")
        fixed("12.2", "12.2")
        fixed("-12", "-12")
        fixed("-", "")
    }

    @Test
    fun `e-notation is dropped until it has an exponent`() {
        fixed("2.5e", "2.5")
        fixed("2.5e-", "2.5")
        fixed("2.5e3", "2.5e3")
        fixed("-2.5e3", "-2.5e3")
        fixed("2.5E", "2.5")
        fixed("2.5E-", "2.5")
        fixed("2.5E3", "2.5E3")
        fixed("-2.5E3", "-2.5E3")
        fixed("12.e", "12")
        fixed("12.34e", "12.34")
        fixed("5e", "5")
    }

    @Test
    fun `an unterminated string is closed`() {
        fixed("\"abc", "\"abc\"")
        val escapes = "\"value with \\\"quoted\\\" text and \\\\ escape"
        fixed(escapes, escapes + "\"")
    }

    @Test
    fun `a string cut inside an escape drops the escape`() {
        fixed("\"value with \\", "\"value with \"")
    }

    @Test
    fun `a string cut inside a unicode escape drops the whole escape`() {
        fixed("\"\\u", "\"\"")
        fixed("\"\\u12", "\"\"")
        fixed("\"text \\u00", "\"text \"")
        fixed("{\"a\":\"\\u12", "{\"a\":\"\"}")
    }

    @Test
    fun `every partial unicode escape still parses`() {
        for (input in listOf("\"\\u", "\"\\u12", "\"text \\u00", "{\"a\":\"\\u12")) {
            Json.parseToJsonElement(fixJson(input))
        }
    }

    @Test
    fun `a complete unicode escape is kept`() {
        val fixed = fixJson("\"value with unicode <\"")
        assertEquals(JsonPrimitive("value with unicode <"), Json.parseToJsonElement(fixed))
    }

    @Test
    fun `arrays are closed at every depth`() {
        fixed("[", "[]")
        fixed("[[1], [2", "[[1], [2]]")
        fixed("[[\"1\"], [\"2", "[[\"1\"], [\"2\"]]")
        fixed("[[false], [nu", "[[false], [null]]")
        fixed("[[[]], [[]", "[[[]], [[]]]")
        fixed("[[{}], [{", "[[{}], [{}]]")
        fixed("[1, ", "[1]")
        fixed("[[], 123", "[[], 123]")
    }

    @Test
    fun `a key with no value is dropped`() {
        fixed("""{"key":""", "{}")
        fixed("""{"ke""", "{}")
        fixed("""{"k1": 1, "k2""", """{"k1": 1}""")
        fixed("""{"k1": 1, "k2":""", """{"k1": 1}""")
        fixed("""{"key": {"subKey":""", """{"key": {}}""")
        fixed("""{"key": 123, "key2": {"subKey":""", """{"key": 123, "key2": {}}""")
        fixed("""{"key": null, "key2": {"subKey":""", """{"key": null, "key2": {}}""")
    }

    @Test
    fun `nested objects are closed at every depth`() {
        fixed("""{"a": {"b": 1}, "c": {"d": 2""", """{"a": {"b": 1}, "c": {"d": 2}}""")
        fixed("""{"a": {"b": "1"}, "c": {"d": 2""", """{"a": {"b": "1"}, "c": {"d": 2}}""")
        fixed("""{"a": {"b": false}, "c": {"d": 2""", """{"a": {"b": false}, "c": {"d": 2}}""")
        fixed("""{"a": {"b": []}, "c": {"d": 2""", """{"a": {"b": []}, "c": {"d": 2}}""")
        fixed("""{"a": {"b": {}}, "c": {"d": 2""", """{"a": {"b": {}}, "c": {"d": 2}}""")
        fixed("""{"a": {"b": {}""", """{"a": {"b": {}}}""")
    }

    @Test
    fun `trailing whitespace does not defeat the close`() {
        fixed("{\"key\": \"value\"  ", "{\"key\": \"value\"}")
    }

    @Test
    fun `mixed nesting closes in the right order`() {
        fixed("[1, [2, 3, [", "[1, [2, 3, []]]")
        fixed("[false, [true, [", "[false, [true, []]]")
        fixed("""{"key": [1, 2, {""", """{"key": [1, 2, {}]}""")
        fixed("[1, 2, {\"key\": \"value\",", "[1, 2, {\"key\": \"value\"}]")
        fixed("{\"a\": {\"b\": [\"c\", {\"d\": \"e\",", "{\"a\": {\"b\": [\"c\", {\"d\": \"e\"}]}}")
        fixed("""{"a": {"b": {"c": {"d":""", """{"a": {"b": {"c": {}}}}""")
        fixed("""{"a": 1, "b": [""", """{"a": 1, "b": []}""")
        fixed("""{"a": 1, "b": {""", """{"a": 1, "b": {}}""")
        fixed("{\"a\": 1, \"b\": \"", "{\"a\": 1, \"b\": \"\"}")
        fixed("""{"type":"div","children":[{"type":"Card","props":{}""", """{"type":"div","children":[{"type":"Card","props":{}}]}""")
    }

    @Test
    fun `a multi-line document is closed where it stopped`() {
        val input = listOf(
            "{",
            """  "a": [""",
            "    {",
            """      "a1": "v1",""",
            """      "a2": "v2",""",
            "      \"a3\": \"v3\"",
            "    }",
            "  ],",
            """  "b": [""",
            "    {",
            "      \"b1\": \"n",
        ).joinToString("\n")
        val expected = input + "\"}]}"
        assertEquals(expected, fixJson(input))
    }
}

class ParsePartialJsonTest {

    @Test
    fun `a complete document parses without repair`() {
        val result = parsePartialJson("""{"a":1}""")
        assertEquals(PartialJsonState.Parsed, result.state)
        assertEquals(JsonPrimitive(1), result.value?.jsonObject?.get("a"))
    }

    @Test
    fun `a truncated document is repaired and reported as repaired`() {
        val result = parsePartialJson("""{"a":1,"b":"partia""")
        assertEquals(PartialJsonState.Repaired, result.state)
        assertEquals(JsonPrimitive("partia"), result.value?.jsonObject?.get("b"))
    }

    @Test
    fun `text that is not JSON fails`() {
        assertEquals(PartialJsonState.Failed, parsePartialJson("not json at all").state)
        assertEquals(PartialJsonState.Failed, parsePartialJson(null).state)
    }
}
