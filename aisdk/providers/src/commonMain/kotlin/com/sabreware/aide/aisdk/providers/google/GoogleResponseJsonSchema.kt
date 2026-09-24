package com.sabreware.aide.aisdk.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A response schema as Gemini's `responseJsonSchema` takes it: JSON Schema, with `const` respelled.
 *
 * Gemini reads JSON Schema on both `parametersJsonSchema` and `responseJsonSchema` — `$ref`, `$defs`,
 * `additionalProperties`, `minItems` and the rest reach the model as written — so this port no longer
 * rewrites a schema into the OpenAPI dialect its older `parameters` / `responseSchema` fields wanted.
 * That rewrite inlined every reference, which is what lost a recursive schema outright and dropped the
 * array bounds and `additionalProperties` a generated schema carries. Tool parameters now go out
 * verbatim; a response schema goes out verbatim but for one keyword.
 *
 * `responseJsonSchema` refuses `const`, and a one-value `enum` says the same thing, so `const` is
 * replaced in every location Google reads a subschema from — `properties`, `items`,
 * `additionalProperties`, `anyOf`, `oneOf` and `$defs` — and nothing else is touched. The reference's
 * `sanitizeResponseJsonSchema`, keyword for keyword.
 */
internal fun sanitizeResponseJsonSchema(schema: JsonObject): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    schema.forEach { (key, value) ->
        when (key) {
            "const" -> Unit
            "properties", "\$defs" -> result[key] = sanitizeDefinitions(value)
            "items" -> result[key] = if (value is JsonArray) JsonArray(value.map(::sanitizeDefinition)) else sanitizeDefinition(value)
            "additionalProperties" -> result[key] = if (value is JsonPrimitive) value else sanitizeDefinition(value)
            "anyOf", "oneOf" -> result[key] = (value as? JsonArray)?.let { JsonArray(it.map(::sanitizeDefinition)) } ?: value
            else -> result[key] = value
        }
    }
    // Last, so a `const` beside an `enum` wins the way the reference's spread order makes it win.
    schema["const"]?.let { result["enum"] = JsonArray(listOf(it)) }
    return JsonObject(result)
}

private fun sanitizeDefinitions(definitions: JsonElement): JsonElement =
    (definitions as? JsonObject)?.let { JsonObject(it.mapValues { (_, value) -> sanitizeDefinition(value) }) }
        ?: definitions

/** A subschema, or the bare `true`/`false` JSON Schema allows in its place, which has nothing to respell. */
private fun sanitizeDefinition(definition: JsonElement): JsonElement =
    if (definition is JsonObject) sanitizeResponseJsonSchema(definition) else definition
