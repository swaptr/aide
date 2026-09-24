package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.providers.testing.arr
import com.sabreware.aide.aisdk.providers.testing.obj
import com.sabreware.aide.aisdk.providers.testing.string
import com.sabreware.aide.aisdk.util.parseJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject

/** The reference's `sanitize-response-json-schema.test.ts`, byte for byte. */
class GoogleResponseJsonSchemaTest {

    @Test
    fun `replaces const with enum while preserving JSON Schema`() {
        val schema = parseJsonObject(
            """{"type":"object","properties":{"response":{"oneOf":[{"type":"object",""" +
                """"properties":{"type":{"type":"string","const":"fruit"}},"required":["type"],""" +
                """"additionalProperties":false}]}},"required":["response"],"additionalProperties":false,""" +
                """"${'$'}defs":{"label":{"type":"string","const":"produce"}}}""",
        )

        assertEquals(
            parseJsonObject(
                """{"type":"object","properties":{"response":{"oneOf":[{"type":"object",""" +
                    """"properties":{"type":{"type":"string","enum":["fruit"]}},"required":["type"],""" +
                    """"additionalProperties":false}]}},"required":["response"],"additionalProperties":false,""" +
                    """"${'$'}defs":{"label":{"type":"string","enum":["produce"]}}}""",
            ),
            sanitizeResponseJsonSchema(schema),
        )
        // The input is not mutated: the `const` is still where the caller wrote it.
        assertEquals(
            "fruit",
            schema.obj("properties", "response")!!.arr("oneOf")!!.single().jsonObject
                .obj("properties", "type")!!["const"].string(),
        )
    }
}
