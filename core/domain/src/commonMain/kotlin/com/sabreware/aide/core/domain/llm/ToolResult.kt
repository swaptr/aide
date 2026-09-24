package com.sabreware.aide.core.domain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

interface ToolResult {
    fun toEnvelope(): JsonObject
}

object ToolEnvelope {

    inline fun success(extras: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("ok", JsonPrimitive(true))
            extras()
        }

    inline fun failure(
        code: String,
        message: String,
        extras: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("errorCode", JsonPrimitive(code))
        put("error", JsonPrimitive(message))
        extras()
    }
}

fun JsonElement.toAnyValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> boolean
        intOrNull != null -> intOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
    is JsonObject -> entries.associate { (k, v) -> k to v.toAnyValue() }
    is JsonArray -> map { it.toAnyValue() }
}

fun JsonObject.toAnyMap(): Map<String, Any?> =
    entries.associate { (k, v) -> k to v.toAnyValue() }
