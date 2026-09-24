package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.CallOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The call's `providerOptions["google"]`, which had no reader at all.
 *
 * Everything that makes Gemini usable beyond a plain completion lives here — safety thresholds, the
 * context cache, billing labels, image output, media resolution — and none of it was reachable:
 * `CallOptions.providerOptions` was declared, carried through the runtime, and dropped by every
 * provider. A caller could set `safetySettings` and watch its content be blocked anyway, with nothing to
 * say the setting never left the process.
 *
 * Unlike Anthropic's, Gemini's wire is already `camelCase`, so nothing here is re-spelled: a field is
 * read where it changes the request's shape, and otherwise passed through as the caller wrote it.
 */
internal class GoogleOptions private constructor(private val raw: JsonObject) {

    /** `cachedContents/{id}` — context Google already holds, billed at the cache rate. */
    val cachedContent: String? = raw.stringOrNull("cachedContent")

    /** Vertex billing labels; the Gemini API ignores them. */
    val labels: Map<String, String>? = (raw["labels"] as? JsonObject)
        ?.mapNotNull { (key, value) -> value.stringOrNull()?.let { key to it } }
        ?.toMap()
        ?.takeIf { it.isNotEmpty() }

    val serviceTier: String? = raw.stringOrNull("serviceTier")

    /** `TEXT` and/or `IMAGE`. Without `IMAGE` an image model answers in prose about the picture. */
    val responseModalities: List<String>? = (raw["responseModalities"] as? JsonArray)
        ?.mapNotNull { it.stringOrNull() }
        ?.takeIf { it.isNotEmpty() }

    val mediaResolution: String? = raw.stringOrNull("mediaResolution")

    val imageConfig: JsonObject? = raw["imageConfig"] as? JsonObject

    val audioTimestamp: Boolean? = raw.boolOrNull("audioTimestamp")

    val retrievalConfig: JsonObject? = raw["retrievalConfig"] as? JsonObject

    /**
     * Whether a JSON response format is served by a constrained decoder.
     *
     * Google's OpenAPI dialect cannot express every JSON Schema. Turning this off is the escape hatch
     * for a schema that survives the conversion but that Gemini's decoder then rejects — the caller gets
     * `application/json` and no schema rather than a 400.
     */
    val structuredOutputs: Boolean = raw.boolOrNull("structuredOutputs") ?: true

    /** An explicit `thinkingConfig`, which wins over anything derived from the neutral effort. */
    val thinkingConfig: JsonObject? = raw["thinkingConfig"] as? JsonObject

    /**
     * The safety settings, or the shorthand expanded.
     *
     * A caller almost always wants the same threshold on every category, and writing five objects to say
     * so is how one gets forgotten. `threshold` alone expands to the full list.
     */
    val safetySettings: List<JsonObject>? = (raw["safetySettings"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.takeIf { it.isNotEmpty() }
        ?: raw.stringOrNull("threshold")?.let { threshold ->
            SAFETY_CATEGORIES.map {
                buildJsonObject {
                    put("category", it)
                    put("threshold", threshold)
                }
            }
        }

    /** An explicit `thinkingConfig` object, translated to the wire type. */
    fun thinkingConfigOrNull(): GoogleThinkingConfig? = thinkingConfig?.let {
        GoogleThinkingConfig(
            includeThoughts = it.boolOrNull("includeThoughts"),
            thinkingLevel = it.stringOrNull("thinkingLevel"),
            thinkingBudget = runCatching { it["thinkingBudget"]?.jsonPrimitive?.intOrNull }.getOrNull(),
        )
    }

    companion object {

        fun of(options: CallOptions): GoogleOptions =
            GoogleOptions(options.providerOptions?.get(GOOGLE_PROVIDER_ID) ?: JsonObject(emptyMap()))

        /**
         * The categories a `threshold` shorthand expands over.
         *
         * `HARM_CATEGORY_UNSPECIFIED` is deliberately absent: Google rejects a setting for it.
         */
        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_CIVIC_INTEGRITY",
        )
    }
}

internal fun JsonObject.stringOrNull(key: String): String? = this[key]?.stringOrNull()

internal fun JsonObject.boolOrNull(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
