package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.Warning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A schema as OpenAI will accept it, and what had to change to get it there. */
internal data class NormalizedOpenAISchema(val schema: JsonSchema, val warnings: List<Warning>)

/**
 * JSON Schema as OpenAI's structured outputs accept it.
 *
 * OpenAI rejects the `propertyNames` keyword outright. Property names are strings whatever the schema
 * says about them, so a string-typed `propertyNames` can be dropped and left to client-side validation:
 * the schema still describes the same documents, only less strictly, and a [Warning.Compatibility] says
 * so. A `propertyNames` that is NOT a string schema — a boolean, a `number` type — describes documents
 * no JSON object can be, and rewriting it would change what the schema means; that one is refused.
 *
 * Applied to every schema that reaches the wire — a function tool's parameters, a response format —
 * because each is a 400 with the keyword left in. The walk is the reference's, keyword for keyword;
 * anything it does not name is copied through untouched.
 */
internal fun normalizeOpenAIJsonSchema(schema: JsonSchema): NormalizedOpenAISchema {
    val normalizer = OpenAISchemaNormalizer()
    val normalized = normalizer.normalize(schema)
    val warnings = if (normalizer.removedPropertyNames) {
        listOf(
            Warning.Compatibility(
                feature = "JSON Schema propertyNames",
                details = "OpenAI does not support JSON Schema propertyNames. It was removed before sending " +
                    "the schema, so OpenAI will not enforce property-name constraints.",
            ),
        )
    } else {
        emptyList()
    }
    return NormalizedOpenAISchema(normalized, warnings)
}

private class OpenAISchemaNormalizer {
    var removedPropertyNames = false

    fun normalize(schema: JsonObject): JsonObject {
        val propertyNames = schema["propertyNames"]
        if (propertyNames != null && propertyNames != JsonNull) {
            val isStringSchema = (propertyNames as? JsonObject)
                ?.get("type")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } == "string"
            if (!isStringSchema) {
                throw UnsupportedFunctionalityError("JSON Schema propertyNames that does not use a string schema")
            }
            removedPropertyNames = true
        }
        return buildJsonObject {
            schema.forEach { (keyword, value) ->
                when (keyword) {
                    "propertyNames" -> Unit
                    "properties", "patternProperties", "definitions", "\$defs" -> put(keyword, record(value))
                    "additionalProperties", "additionalItems", "contains", "not", "if", "then", "else" ->
                        put(keyword, definition(value))
                    "items" -> put(
                        keyword,
                        if (value is JsonArray) JsonArray(value.map(::definition)) else definition(value),
                    )
                    "allOf", "anyOf", "oneOf" -> put(keyword, (value as? JsonArray)?.let(::definitions) ?: value)
                    // A dependency is either a list of property names, kept as is, or a schema.
                    "dependencies" -> put(
                        keyword,
                        (value as? JsonObject)
                            ?.let { deps ->
                                JsonObject(deps.mapValues { (_, d) -> if (d is JsonArray) d else definition(d) })
                            }
                            ?: value,
                    )
                    else -> put(keyword, value)
                }
            }
        }
    }

    /** A `JSONSchema7Definition`: a boolean passes through, an object is a schema. */
    private fun definition(value: JsonElement): JsonElement = if (value is JsonObject) normalize(value) else value

    private fun definitions(values: JsonArray): JsonArray = JsonArray(values.map(::definition))

    private fun record(value: JsonElement): JsonElement =
        (value as? JsonObject)?.let { schemas -> JsonObject(schemas.mapValues { (_, s) -> definition(s) }) } ?: value
}
