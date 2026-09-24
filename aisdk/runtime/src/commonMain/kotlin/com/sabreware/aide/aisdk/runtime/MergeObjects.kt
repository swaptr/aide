package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.ProviderOptions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Deep-merges [overrides] over [base]: nested objects merge key by key, everything else is replaced.
 *
 * Arrays are replaced rather than concatenated, because the values this merges are vendor option blobs
 * and a list in one of those is a complete setting — Anthropic's `betas`, OpenAI's `modalities`. A
 * caller that overrides `betas` means *this* list, and appending would silently keep whatever the layer
 * below asked for.
 */
public fun mergeJsonObjects(base: JsonObject?, overrides: JsonObject?): JsonObject? {
    if (base == null) return overrides
    if (overrides == null) return base
    return JsonObject(
        base.toMutableMap().apply {
            for ((key, override) in overrides) {
                val existing = this[key]
                this[key] = if (existing is JsonObject && override is JsonObject) {
                    mergeJsonObjects(existing, override) as JsonElement
                } else {
                    override
                }
            }
        },
    )
}

/** The same merge, one level up: provider namespaces merge, and each namespace's blob merges deeply. */
public fun mergeProviderOptions(base: ProviderOptions?, overrides: ProviderOptions?): ProviderOptions? {
    if (base == null) return overrides
    if (overrides == null) return base
    return base.toMutableMap().apply {
        for ((provider, override) in overrides) {
            this[provider] = mergeJsonObjects(this[provider], override) ?: override
        }
    }
}

/**
 * Folds the provider metadata of several calls that together answered one request.
 *
 * Keeping only the first is what a fan-out does by accident, and it is silently lossy: `maxImagesPerCall`
 * is 1 on DALL-E 3 and every Imagen variant, so a request for two images kept the first one's
 * `revisedPrompt` and dropped the second's while the result still looked populated. The same shape loses
 * a later embedding chunk's block.
 *
 * Merging is per provider id and then per key, so two calls to the same vendor combine rather than one
 * replacing the other, and a later value wins a genuine collision — it came from the later call.
 */
public fun List<ProviderMetadata?>.mergeProviderMetadata(): ProviderMetadata? {
    val present = filterNotNull().filter { it.isNotEmpty() }
    if (present.isEmpty()) return null
    return buildMap {
        present.forEach { metadata ->
            metadata.forEach { (providerId, block) ->
                put(providerId, mergeJsonObjects(get(providerId), block) ?: block)
            }
        }
    }
}
