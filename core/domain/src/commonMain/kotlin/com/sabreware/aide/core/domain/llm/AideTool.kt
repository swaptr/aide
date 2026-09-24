package com.sabreware.aide.core.domain.llm

import com.sabreware.aide.core.domain.model.ProviderId
import kotlinx.serialization.json.JsonObject

sealed class AideTool {
    abstract val name: String
    abstract val description: String

    abstract val surfaces: Set<Surface>

    data class Function(
        override val name: String,
        override val description: String,
        val parametersSchema: JsonObject,
        // Suspend so handlers can do async work (network search, user-confirm gates, verification)
        // off the dispatcher thread instead of parking it with runBlocking. A plain non-suspending
        // lambda still satisfies this type, so the many synchronous tools are unaffected.
        val handler: suspend (args: JsonObject) -> JsonObject,
        override val surfaces: Set<Surface> = setOf(Surface.CHAT),
        val maxCallsPerTurn: Int? = null,
        val errorCodes: Set<String> = emptySet(),
        val promptDoc: String? = null,
        val category: String? = null,
        val requiresActivation: Boolean = false,
        /**
         * True only for a tool that **observes and changes nothing**. Replaying such a call inside one turn
         * is free, so the dispatcher may answer it from its idempotency cache instead of invoking the
         * handler.
         *
         * It defaults to false, and that default is load-bearing. This used to be `cacheable = true`: every
         * tool that did not think about it was memoized, so a second "text Bob 'running late'" returned the
         * first call's success envelope, the SMS handler was never invoked, and the model reported success.
         * A property of the tool ("does it only read?") is answerable at the declaration site; a property of
         * the cache ("may this be cached?") is not.
         */
        val readOnly: Boolean = false,
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
