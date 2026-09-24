package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

/**
 * `normalizeOpenAIJsonSchema`, against `normalize-openai-json-schema.test.ts`.
 *
 * The two cases are the reference's own: the keyword is stripped at every depth the walk reaches
 * (a property, a nested `additionalProperties`, `definitions`, `$defs`, an `if`), exactly one warning
 * says so, and a `propertyNames` that is not a string schema is refused rather than rewritten.
 */
class OpenAIJsonSchemaTest {

    @Test
    fun `removes string propertyNames recursively and warns`() {
        val schema = parseJsonObject(
            """{"type":"object","properties":{"variables":{"type":"object",""" +
                """"propertyNames":{"type":"string","format":"uuid"},""" +
                """"additionalProperties":{"type":"object","propertyNames":{"type":"string","pattern":"^[A-Z_]+$"}}}},""" +
                """"definitions":{"variable":{"type":"object","propertyNames":{"type":"string"}}},""" +
                """"${'$'}defs":{"conditional":{"if":{"type":"object","propertyNames":{"type":"string"}}}}}""",
        )

        val result = normalizeOpenAIJsonSchema(schema)

        assertEquals(
            parseJsonObject(
                """{"type":"object","properties":{"variables":{"type":"object","additionalProperties":{"type":"object"}}},""" +
                    """"definitions":{"variable":{"type":"object"}},""" +
                    """"${'$'}defs":{"conditional":{"if":{"type":"object"}}}}""",
            ),
            result.schema,
        )
        assertEquals(
            listOf(
                Warning.Compatibility(
                    feature = "JSON Schema propertyNames",
                    details = "OpenAI does not support JSON Schema propertyNames. It was removed before sending " +
                        "the schema, so OpenAI will not enforce property-name constraints.",
                ),
            ),
            result.warnings,
        )
        // The caller's schema is untouched; only the copy that goes on the wire lost the keyword.
        assertTrue("propertyNames" in schema["properties"]!!.jsonObject["variables"]!!.jsonObject)
    }

    @Test
    fun `rejects non-string propertyNames schemas`() {
        assertFailsWith<UnsupportedFunctionalityError> {
            normalizeOpenAIJsonSchema(parseJsonObject("""{"type":"object","propertyNames":{"type":"number"}}"""))
        }
    }

    @Test
    fun `rejects a boolean propertyNames, which no string schema could stand in for`() {
        assertFailsWith<UnsupportedFunctionalityError> {
            normalizeOpenAIJsonSchema(parseJsonObject("""{"type":"object","propertyNames":false}"""))
        }
    }

    @Test
    fun `a schema without the keyword passes through untouched, with no warning`() {
        val schema = parseJsonObject(
            """{"type":"object","properties":{"a":{"type":"string"}},"required":["a"],"additionalProperties":false}""",
        )

        val result = normalizeOpenAIJsonSchema(schema)

        assertEquals(schema, result.schema)
        assertEquals(emptyList(), result.warnings)
    }
}
