package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Shared across the toolset definitions (split out of the former AideTools god file).

val BOTH_SURFACES: Set<Surface> = setOf(Surface.CHAT, Surface.IME, Surface.VOICE)
// IME has no foreground activity to host destination intents; voice overlay does.
val CHAT_AND_VOICE: Set<Surface> = setOf(Surface.CHAT, Surface.VOICE)
// AlertDialog / ContactPickGate host required — neither IME nor voice overlay can host.
val CHAT_ONLY: Set<Surface> = setOf(Surface.CHAT)

fun stringProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

fun stringArrayProp(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

fun objectSchema(
    requiredProps: List<Pair<String, JsonObject>>,
    optionalProps: List<Pair<String, JsonObject>> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        requiredProps.forEach { (k, v) -> put(k, v) }
        optionalProps.forEach { (k, v) -> put(k, v) }
    })
    if (requiredProps.isNotEmpty()) {
        put("required", buildJsonArray { requiredProps.forEach { (k, _) -> add(JsonPrimitive(k)) } })
    }
}
