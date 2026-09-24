package com.sabreware.aide.aisdk.providers.options

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed reads over a caller's `providerOptions` entry.
 *
 * The reference validates each vendor's options with a zod schema; there is no zod here, and inventing
 * one would be a much larger piece of machinery than the ~60 fields it would guard. What these do
 * instead is the half that actually matters on the wire: a value of the wrong JSON type reads as null
 * and the field is omitted, rather than being serialized as-is and rejected by the vendor.
 *
 * JSON null is treated as absent throughout, because that is how the reference's `.nullish()` options
 * behave — a caller writing `null` means "leave it to the vendor", not "send null".
 */
internal fun JsonObject.optString(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

internal fun JsonObject.optDouble(key: String): Double? = primitive(key)?.content?.toDoubleOrNull()

internal fun JsonObject.optInt(key: String): Int? = primitive(key)?.content?.toIntOrNull()

internal fun JsonObject.optBoolean(key: String): Boolean? = when (primitive(key)?.content) {
    "true" -> true
    "false" -> false
    else -> null
}

internal fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

/** The value under [key] verbatim, for a field whose shape the vendor — not we — defines. */
internal fun JsonObject.optElement(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

private fun JsonObject.primitive(key: String) =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonPrimitive }.getOrNull() }
