package com.swaptr.aide.domain.llm

import com.swaptr.aide.data.catalog.ProviderId
import kotlinx.serialization.json.JsonObject

sealed class AideTool {
    abstract val name: String
    abstract val description: String

    abstract val surfaces: Set<Surface>

    data class Function(
        override val name: String,
        override val description: String,
        val parametersSchema: JsonObject,
        // LiteRT invokes handlers from a non-coroutine native thread so they must be synchronous.
        val handler: (args: JsonObject) -> JsonObject,
        override val surfaces: Set<Surface> = setOf(Surface.CHAT),
        val maxCallsPerTurn: Int? = null,
        val errorCodes: Set<String> = emptySet(),
        val promptDoc: String? = null,
        val category: String? = null,
        val requiresActivation: Boolean = false,
    ) : AideTool()

    data class ProviderNative(
        override val name: String,
        override val description: String,
        val providerKey: String,
        val scope: Set<ProviderId>,
        val config: JsonObject? = null,
        override val surfaces: Set<Surface> = setOf(Surface.CHAT),
    ) : AideTool()
}
