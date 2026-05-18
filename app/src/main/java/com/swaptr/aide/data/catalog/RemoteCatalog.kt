package com.swaptr.aide.data.catalog

// Every Ollama tag surfaces; `isCloud` toggles cosmetics and native web_search advertising.
object RemoteCatalog {

    // Conservative defaults at tag-list time; hydrated lazily via /api/show on selection
    // (per-tag /api/show is too expensive to do up-front for large libraries).
    fun ollamaSpec(
        tagName: String,
        isCloud: Boolean,
        capabilities: List<String> = emptyList(),
        contextLength: Long? = null,
    ): ModelSpec {
        val caps = capabilities.map { it.lowercase() }.toSet()
        // No-capabilities defaults match the previous (pre-/api/show) behaviour so a
        // first-launch render before hydration runs still lets the user pick a tag.
        val unknown = caps.isEmpty()
        val supportsThinking = "thinking" in caps
        val supportsVision = "vision" in caps
        val supportsTools = unknown || "tools" in caps
        val maxContext = contextLength?.coerceIn(1L, Int.MAX_VALUE.toLong())?.toInt() ?: 8192
        return ModelSpec(
            id = if (isCloud) "ollama-cloud:$tagName" else "ollama:$tagName",
            displayName = tagName,
            family = tagName.substringBefore(':'),
            params = "",
            quantization = if (isCloud) "(cloud)" else "",
            provider = ProviderId.OLLAMA,
            remoteName = tagName,
            minRamGb = 0,
            recommendedRamGb = 0,
            capabilities = CapabilitySet(
                visionIn = supportsVision,
                audioIn = false,
                toolsLocal = supportsTools,
                toolsNative = if (isCloud) setOf("web_search") else emptySet(),
                structuredOutput = CapabilitySet.StructuredOutput.JsonSchema,
                thinking = if (supportsThinking) {
                    CapabilitySet.ThinkingMode.Toggle
                } else {
                    CapabilitySet.ThinkingMode.None
                },
                embeddings = "embedding" in caps,
                maxContext = maxContext,
                maxOutput = 2048,
            ),
            // Ollama runs server-side so this is informational only — keep semantics aligned
            // with the rest of the app (GPU-first).
            defaultBackend = ModelBackend.GPU,
            licenseName = if (isCloud) "(see ollama.com)" else "(see Ollama Hub)",
            licenseUrl = "https://ollama.com/library/${tagName.substringBefore(':')}",
            sourceUrl = "https://ollama.com/library/${tagName.substringBefore(':')}",
        )
    }
}
