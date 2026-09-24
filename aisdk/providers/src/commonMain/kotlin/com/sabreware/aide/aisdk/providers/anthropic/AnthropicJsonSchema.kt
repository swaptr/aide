package com.sabreware.aide.aisdk.providers.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Trims a JSON Schema down to what Anthropic's constrained decoder accepts.
 *
 * This module's standing rule is that a schema is cargo — passed through, never parsed. `output_config`
 * is the one place that cannot hold: the decoder rejects keywords any realistic generated schema carries
 * (`minimum`, `pattern`, `uniqueItems`, an unrecognised `format`), and a rejection here is a 400 on a
 * request the caller has no way to see was malformed.
 *
 * Dropped constraints are not simply lost. They are appended to the schema's `description`, which is what
 * the reference does and is the only channel left: the model can still be TOLD that a value must be at
 * least 5, even where the decoder will not enforce it.
 *
 * Nothing else is sanitized — the tool `input_schema` path still passes schemas through untouched, since
 * that decoder accepts the full dialect.
 */
internal fun sanitizeAnthropicJsonSchema(schema: JsonObject): JsonObject = sanitizeSchema(schema)

private fun sanitizeDefinition(definition: JsonElement): JsonElement =
    if (definition is JsonObject) sanitizeSchema(definition) else definition

@Suppress("CyclomaticComplexMethod")
private fun sanitizeSchema(schema: JsonObject): JsonObject {
    // A reference names a definition that was itself sanitized; anything beside it is ignored by every
    // resolver, so carrying it through would only reintroduce the keywords just removed.
    schema["\$ref"]?.let { return buildJsonObject { put("\$ref", it) } }

    return buildJsonObject {
        PASSTHROUGH_KEYS.forEach { key -> schema[key]?.let { put(key, it) } }

        // `oneOf` is exclusive-or and `anyOf` is inclusive; the decoder implements only the latter, and
        // the reference accepts the widening rather than dropping the branch structure entirely.
        val union = schema["anyOf"] ?: schema["oneOf"]
        (union as? JsonArray)?.let { put("anyOf", JsonArray(it.map(::sanitizeDefinition))) }
        (schema["allOf"] as? JsonArray)?.let { put("allOf", JsonArray(it.map(::sanitizeDefinition))) }

        listOf("definitions", "\$defs").forEach { key ->
            (schema[key] as? JsonObject)?.let { defs ->
                put(key, JsonObject(defs.mapValues { (_, value) -> sanitizeDefinition(value) }))
            }
        }

        val properties = schema["properties"] as? JsonObject
        if (schema["type"]?.stringOrNull() == "object" || properties != null) {
            properties?.let { put("properties", JsonObject(it.mapValues { (_, v) -> sanitizeDefinition(v) })) }
            // Required by the decoder, not merely permitted: an object schema without it is rejected.
            put("additionalProperties", JsonPrimitive(false))
            schema["required"]?.let { put("required", it) }
        }

        schema["items"]?.let { items ->
            put("items", if (items is JsonArray) JsonArray(items.map(::sanitizeDefinition)) else sanitizeDefinition(items))
        }

        schema["format"]?.stringOrNull()?.takeIf { it in SUPPORTED_FORMATS }?.let { put("format", JsonPrimitive(it)) }

        constraintDescription(schema)?.let { extra ->
            val existing = schema["description"]?.stringOrNull()
            put("description", JsonPrimitive(if (existing == null) extra else "$existing\n$extra"))
        }
    }
}

/** The dropped constraints, restated as prose the model can still act on. */
private fun constraintDescription(schema: JsonObject): String? {
    val parts = CONSTRAINT_KEYS.mapNotNull { key ->
        val value = schema[key] ?: return@mapNotNull null
        if (value is JsonPrimitive && value.content == "false") return@mapNotNull null
        "${key.spacedOut()}: ${value.stringOrNull() ?: value}"
    }.toMutableList()

    schema["format"]?.stringOrNull()?.takeIf { it !in SUPPORTED_FORMATS }?.let { parts += "format: $it" }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("; ", postfix = ".")
}

/** `exclusiveMinimum` reads as `exclusive minimum` — the description is for a model, not a parser. */
private fun String.spacedOut(): String = buildString {
    this@spacedOut.forEach { if (it.isUpperCase()) append(' ').append(it.lowercaseChar()) else append(it) }
}

private val PASSTHROUGH_KEYS =
    listOf("\$schema", "\$id", "title", "description", "default", "const", "enum", "type")

private val CONSTRAINT_KEYS = listOf(
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems",
    "minProperties", "maxProperties", "not",
)

private val SUPPORTED_FORMATS = setOf(
    "date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid",
)
