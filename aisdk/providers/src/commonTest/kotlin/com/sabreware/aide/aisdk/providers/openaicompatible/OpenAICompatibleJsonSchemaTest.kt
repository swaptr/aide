package com.sabreware.aide.aisdk.providers.openaicompatible

import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.providers.openai.normalizeOpenAIJsonSchema
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The reference's `normalize-openai-json-schema.test.ts`, with its fixture. */
class OpenAICompatibleJsonSchemaTest {

    @Test
    fun `string propertyNames are removed at every depth, once warned`() {
        val schema = parseJsonObject(
            """
            {"type":"object",
             "properties":{"variables":{"type":"object","propertyNames":{"type":"string","format":"uuid"},
               "additionalProperties":{"type":"object","propertyNames":{"type":"string","pattern":"^[A-Z_]+$"}}}},
             "definitions":{"variable":{"type":"object","propertyNames":{"type":"string"}}},
             "${'$'}defs":{"conditional":{"if":{"type":"object","propertyNames":{"type":"string"}}}}}
            """.trimIndent(),
        )

        val normalized = normalizeOpenAIJsonSchema(schema)

        assertEquals(
            parseJsonObject(
                """
                {"type":"object",
                 "properties":{"variables":{"type":"object","additionalProperties":{"type":"object"}}},
                 "definitions":{"variable":{"type":"object"}},
                 "${'$'}defs":{"conditional":{"if":{"type":"object"}}}}
                """.trimIndent(),
            ),
            normalized.schema,
        )
        assertEquals(
            listOf(
                Warning.Compatibility(
                    feature = "JSON Schema propertyNames",
                    details = "OpenAI does not support JSON Schema propertyNames. It was removed before sending " +
                        "the schema, so OpenAI will not enforce property-name constraints.",
                ),
            ),
            normalized.warnings,
        )
        // The input is not rewritten in place.
        assertTrue("propertyNames" in schema.getValue("properties").let { parseJsonObject(it.toString()) }.getValue("variables").toString())
    }

    @Test
    fun `a propertyNames that is not a string schema cannot be dropped honestly, and is refused`() {
        assertFailsWith<UnsupportedFunctionalityError> {
            normalizeOpenAIJsonSchema(parseJsonObject("""{"type":"object","propertyNames":{"type":"number"}}"""))
        }
        assertFailsWith<UnsupportedFunctionalityError> {
            normalizeOpenAIJsonSchema(parseJsonObject("""{"type":"object","propertyNames":true}"""))
        }
    }

    @Test
    fun `a schema without the keyword comes back as it went in, with nothing to say`() {
        val schema = parseJsonObject(
            """{"type":"object","properties":{"a":{"type":"array","items":[{"type":"string"},true]}},"required":["a"]}""",
        )

        val normalized = normalizeOpenAIJsonSchema(schema)

        assertEquals(schema, normalized.schema)
        assertTrue(normalized.warnings.isEmpty())
    }
}
