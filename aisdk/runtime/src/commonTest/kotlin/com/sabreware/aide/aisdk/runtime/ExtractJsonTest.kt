package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.TypeValidationError
import com.sabreware.aide.aisdk.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Every way a model hands back JSON that is not, strictly, JSON.
 *
 * This extraction is deliberately a superset of the reference, which fails where a model fences or
 * narrates around its answer — losing a correct answer to a formatting habit is waste, and the habit is
 * near-universal on models that were trained on chat. The ladder has three rungs and only the first was
 * covered; the ones below it are exactly the ones that fire when a vendor changes its default style.
 */
class ExtractJsonTest {

    @Test
    fun `plain JSON is taken as it stands`() {
        val value = assertIs<JsonObject>(extractJson("""{"a":1}"""))
        assertEquals("1", value["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a bare fence is unwrapped, not only a json-tagged one`() {
        // Several models fence with no language tag at all, and a matcher that requires ```json reads
        // the backticks as prose and falls through to the brace scan — which finds the same object by
        // accident until the model puts a second brace in its commentary.
        val value = assertIs<JsonObject>(extractJson("```\n{\"a\":1}\n```"))
        assertEquals("1", value["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a JSON-tagged fence is unwrapped whatever its case`() {
        assertIs<JsonObject>(extractJson("```JSON\n{\"a\":1}\n```"))
    }

    @Test
    fun `a top-level array is a JSON value, not a failure`() {
        // An array output strategy asks for one, so rejecting it here would make the strategy unusable
        // on any model that does not wrap its answer in an object.
        val value = assertIs<JsonArray>(extractJson("""[1,2,3]"""))
        assertEquals(3, value.size)
    }

    @Test
    fun `an array inside prose is found by the brace scan`() {
        val value = assertIs<JsonArray>(extractJson("""Here you go: [1,2] — hope that helps."""))
        assertEquals(2, value.size)
    }

    @Test
    fun `a fence that does not contain JSON falls through to the scan rather than failing`() {
        // The fence matched, its contents did not parse, and the answer was outside it. Returning null
        // at the first rung that matched would throw away a perfectly good object.
        val value = assertIs<JsonObject>(
            extractJson("```\nnot json at all\n```\nThe answer is {\"a\":1}"),
        )
        assertEquals("1", value["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a scalar answer is still a JSON value`() {
        assertEquals("42", assertIs<JsonPrimitive>(extractJson("42")).content)
    }

    @Test
    fun `prose with no JSON in it extracts nothing`() {
        assertNull(extractJson("I'd rather not."))
    }

    @Test
    fun `a lone brace is not an object`() {
        // `start in 0..<end` is what excludes this: one brace is a start with no end, and a scan that
        // accepted it would hand a parse failure to the caller as a shape mismatch.
        assertNull(extractJson("The set is { incomplete"))
    }

    @Test
    fun `asObject returns the object it was given`() {
        val result = ObjectResult<JsonElement>(
            value = buildJsonObject { put("a", 1) },
            raw = buildJsonObject { put("a", 1) },
            usage = Usage(),
            steps = emptyList(),
        )
        assertEquals("1", result.asObject()["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `asObject names what it got instead of failing with a cast`() {
        val result = ObjectResult<JsonElement>(
            value = JsonArray(listOf(JsonPrimitive(1))),
            raw = JsonArray(listOf(JsonPrimitive(1))),
            usage = Usage(),
            steps = emptyList(),
        )
        // A ClassCastException here would name the JVM's type and not the model's mistake, which is the
        // difference between a caller fixing their schema and a caller filing a bug against the runtime.
        val failure = assertFailsWith<TypeValidationError> { result.asObject() }
        assertEquals(true, failure.message?.contains("Expected a JSON object"))
    }

}
