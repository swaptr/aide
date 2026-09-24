package com.sabreware.aide.aisdk.providers.moonshotai

import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MFJS normalizer, tested as the pure function it is.
 *
 * These cases are written against Moonshot's published MFJS specification rather than the vendored
 * `ai@7.0.85` reference, because the two disagree: the reference emits `prefixItems` for a tuple, and
 * the specification lists `prefixItems` among the keywords MFJS does not support. Where they agree
 * (the `type`-beside-`anyOf` move, the object root) the reference's own test expectations are used.
 */
class MoonshotJsonSchemaTest {

    private fun normalize(json: String): JsonObject =
        normalizeJsonSchemaForMfjs(parse(json)) as JsonObject

    private fun parse(json: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a tuple becomes a union, because MFJS supports neither tuple form`() {
        // The spec: "Tuple simulation using prefixItems or unevaluatedItems is not supported." So the
        // reference's items-to-prefixItems rewrite produces a schema Moonshot itself rejects. Positional
        // typing cannot survive; a union over the element types is the strongest thing left to say.
        val result = normalize(
            """{"type":"object","properties":{"pair":{"type":"array",
               "items":[{"type":"string"},{"type":"number"}]}}}""",
        )

        val pair = result.jsonObject["properties"]!!.jsonObject["pair"]!!.jsonObject
        assertNull(pair["prefixItems"], "prefixItems is prohibited by the MFJS spec")
        val branches = pair["items"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(
            listOf("string", "number"),
            branches.map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `a single-element tuple collapses to that element rather than a one-branch union`() {
        val result = normalize(
            """{"type":"object","properties":{"one":{"type":"array","items":[{"type":"string"}]}}}""",
        )

        val one = result["properties"]!!.jsonObject["one"]!!.jsonObject
        assertEquals("string", one["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(one["items"]!!.jsonObject["anyOf"])
    }

    @Test
    fun `a caller's own prefixItems is folded into the union and the keyword removed`() {
        val result = normalize(
            """{"type":"object","properties":{"p":{"type":"array",
               "prefixItems":[{"type":"boolean"}],"items":[{"type":"string"}]}}}""",
        )

        val p = result["properties"]!!.jsonObject["p"]!!.jsonObject
        assertNull(p["prefixItems"])
        // Positional entries lead, so the order the caller wrote survives even though the positions do not.
        assertEquals(
            listOf("boolean", "string"),
            p["items"]!!.jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `a homogeneous items schema is recursed into, not collapsed`() {
        val result = normalize(
            """{"type":"object","properties":{"xs":{"type":"array",
               "items":{"type":"array","items":[{"type":"string"}]}}}}""",
        )

        val inner = result["properties"]!!.jsonObject["xs"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("array", inner["type"]!!.jsonPrimitive.content)
        assertEquals("string", inner["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `type beside anyOf moves into the branches that lack one`() {
        val result = normalize(
            """{"type":"object","properties":{"u":{"type":"string",
               "anyOf":[{"maxLength":3},{"type":"number"}]}}}""",
        )

        val u = result["properties"]!!.jsonObject["u"]!!.jsonObject
        assertNull(u["type"], "the parent type has nowhere to apply once the union is there")
        val branches = u["anyOf"]!!.jsonArray
        assertEquals("string", branches[0].jsonObject["type"]!!.jsonPrimitive.content)
        // A branch that named its own type keeps it.
        assertEquals("number", branches[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keywords the MFJS spec prohibits are stripped wherever they appear`() {
        val result = normalize(
            """{"type":"object","title":"Root","properties":{
               "n":{"type":"number","format":"float","exclusiveMinimum":0,"exclusiveMaximum":10},
               "a":{"type":"array","unevaluatedItems":false,"minContains":1,"maxContains":2}}}""",
        )

        assertNull(result["title"])
        val n = result["properties"]!!.jsonObject["n"]!!.jsonObject
        listOf("format", "exclusiveMinimum", "exclusiveMaximum").forEach { assertNull(n[it], it) }
        val a = result["properties"]!!.jsonObject["a"]!!.jsonObject
        listOf("unevaluatedItems", "minContains", "maxContains").forEach { assertNull(a[it], it) }
        // What the spec does support is untouched.
        assertEquals("number", n["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `supported keywords survive intact`() {
        val source = """{"type":"object","description":"d","required":["a"],
            "properties":{"a":{"type":"string","enum":["x","y"],"default":"x"}},
            "additionalProperties":false,"${'$'}defs":{"D":{"type":"integer"}}}"""

        val result = normalize(source)

        assertEquals("d", result["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("a"), result["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        val a = result["properties"]!!.jsonObject["a"]!!.jsonObject
        assertEquals(listOf("x", "y"), a["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("x", a["default"]!!.jsonPrimitive.content)
        assertEquals("integer", result["\$defs"]!!.jsonObject["D"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested definitions are rewritten too, however deep`() {
        val result = normalize(
            """{"type":"object","${'$'}defs":{"Inner":{"type":"array","items":[{"type":"string"}],"title":"t"}},
               "properties":{"o":{"type":"object","properties":{
                 "deep":{"type":"array","items":[{"type":"number"},{"type":"boolean"}]}}}}}""",
        )

        val inner = result["\$defs"]!!.jsonObject["Inner"]!!.jsonObject
        assertNull(inner["title"])
        assertEquals("string", inner["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val deep = result["properties"]!!.jsonObject["o"]!!.jsonObject["properties"]!!
            .jsonObject["deep"]!!.jsonObject
        assertTrue(deep["items"]!!.jsonObject["anyOf"]!!.jsonArray.size == 2)
    }

    @Test
    fun `a root that is not an object is refused, naming the requirement`() {
        // Agrees with the reference. A function's parameters is an argument bag on every vendor, so a
        // non-object root is a caller mistake worth naming here rather than as a vendor 400 later.
        val error = assertFailsWith<UnsupportedFunctionalityError> {
            normalize("""{"type":"array","items":{"type":"string"}}""")
        }
        assertTrue(error.message.orEmpty().contains("type \"object\""))

        assertFailsWith<UnsupportedFunctionalityError> {
            normalizeJsonSchemaForMfjs(kotlinx.serialization.json.JsonPrimitive(true))
        }
    }
}
