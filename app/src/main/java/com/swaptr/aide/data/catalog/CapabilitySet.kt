package com.swaptr.aide.data.catalog

// Engines must silently drop unsupported fields (warn-and-continue) — a degraded reply
// beats an exception in the streaming pipeline.
data class CapabilitySet(
    val visionIn: Boolean = false,
    val audioIn: Boolean = false,
    /** Model accepts and dispatches locally-executed function tools. */
    val toolsLocal: Boolean = false,
    /** Provider-native tool keys the model accepts (e.g. `"web_search"`, `"google_search"`). */
    val toolsNative: Set<String> = emptySet(),
    val structuredOutput: StructuredOutput = StructuredOutput.None,
    val thinking: ThinkingMode = ThinkingMode.None,
    val embeddings: Boolean = false,
    val maxContext: Int,
    val maxOutput: Int,
) {
    enum class StructuredOutput { None, JsonMode, JsonSchema }

    sealed interface ThinkingMode {
        data object None : ThinkingMode
        data object Toggle : ThinkingMode
        data class Levels(val levels: Set<String>) : ThinkingMode
    }
}
