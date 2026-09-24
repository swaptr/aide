package com.sabreware.aide.aisdk.providers.options

import com.sabreware.aide.aisdk.ProviderOptions
import kotlinx.serialization.json.JsonObject

/**
 * Options read across two namespaces: the canonical one always, the hosting provider's on top.
 *
 * One wire is served by several providers — Anthropic's body goes out over the direct API, Bedrock and
 * Vertex; OpenAI's Responses wire is also Azure's, xAI's and HuggingFace's; the google models run on
 * Vertex unchanged. A caller files options under the id of the provider it actually constructed, so a
 * provider that read only its canonical namespace would silently ignore them, and one that read only
 * its own would break every prompt written against the canonical id.
 *
 * So both are read and [custom] wins **field by field**. The merge is shallow, matching the reference:
 * a nested object under the custom key replaces the canonical one wholesale rather than being merged
 * into it. That is the rule `aisdk/DESIGN.md` records for the Anthropic family, and it is the same rule
 * everywhere else it appears — this function is the one copy of it.
 *
 * @param canonical the wire's own id — `anthropic`, `openai`, `google`.
 * @param custom the hosting provider's id — `amazon-bedrock`, `azure`, `google-vertex`, `minimax`.
 *   Null, or equal to [canonical], reads the canonical namespace alone.
 * @return the merged object, EMPTY when neither namespace is present — a caller asking for options a
 *   prompt never carried wants an empty read, not a null to unwrap at every site.
 */
internal fun ProviderOptions?.mergedFor(canonical: String, custom: String?): JsonObject {
    val base = this?.get(canonical) ?: JsonObject(emptyMap())
    val overlay = custom?.takeIf { it != canonical }?.let { this?.get(it) } ?: return base
    return JsonObject(base + overlay)
}

/**
 * [mergedFor], but null when NEITHER namespace was present.
 *
 * For the caller that has to tell "no options at all" from "options that happened to be empty" —
 * a request builder deciding whether to emit a vendor block at all, rather than one reading fields
 * out of it.
 */
internal fun ProviderOptions?.mergedForOrNull(canonical: String, custom: String?): JsonObject? {
    if (this == null || (this[canonical] == null && custom?.let { this[it] } == null)) return null
    return mergedFor(canonical, custom)
}
