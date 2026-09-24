package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.JsonParseError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The JSON configuration every provider should use.
 *
 * `ignoreUnknownKeys` is the important one: vendors add response fields without warning, and a strict
 * parser turns a harmless addition into a total outage. `explicitNulls = false` keeps optional fields off
 * the wire entirely, which matters because several vendors reject an explicit `null` where they accept an
 * absent key.
 */
public val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    // NOT lenient. A lenient parser accepts unquoted keys and single-quoted values that no vendor emits
    // and `JSON.parse` rejects, so a corrupted body parses into a wrong-shaped object instead of
    // raising — which turns a diagnosable wire failure into a silently empty result.
    isLenient = false
}

/** Parses [text], raising [JsonParseError] with the offending body attached rather than a bare exception. */
public fun parseJsonElement(text: String, json: Json = ProviderJson): JsonElement =
    runCatching { json.parseToJsonElement(text) }
        .getOrElse { throw JsonParseError(text = text, cause = it) }

/** As [parseJsonElement], but requires an object. */
public fun parseJsonObject(text: String, json: Json = ProviderJson): JsonObject =
    parseJsonElement(text, json) as? JsonObject
        ?: throw JsonParseError(text = text)

/**
 * Parses, or returns null.
 *
 * For the streaming case where a malformed frame should be skipped rather than kill the generation — a
 * keep-alive some proxies inject mid-stream, for instance.
 */
public fun parseJsonElementOrNull(text: String, json: Json = ProviderJson): JsonElement? =
    runCatching { json.parseToJsonElement(text) }.getOrNull()
