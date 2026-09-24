package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.Tool
import kotlinx.serialization.json.JsonObject

/**
 * A tool the VENDOR defines: it owns the name, the input schema and — for the executed kind — the
 * result.
 *
 * A provider-defined tool is declared once, next to the provider that knows it, and instantiated per
 * call with whatever configuration the caller chose. Without a shared shape each provider grows its own
 * ad-hoc pair of "the id string" and "the name string" kept in agreement by hand, which is exactly how a
 * call comes back naming a tool the client has never heard of.
 *
 * Two kinds, and the difference is who runs it:
 *
 * - **Provider-defined** ([providerDefinedTool]) — the vendor supplies the schema, the CLIENT executes.
 *   Anthropic's text editor and computer-use tools are these: the model emits a call in a shape the
 *   vendor specified, and the runtime has to actually do it.
 * - **Provider-executed** ([providerExecutedTool]) — the vendor runs it on its own servers and streams
 *   the result back. Web search and code execution are these; the runtime never dispatches one and
 *   could not, since it holds no implementation.
 *
 * [inputSchema] and [outputSchema] are kept here rather than on [Tool.ProviderDefined] because the
 * specification's tool carries only what a runtime needs at call time. They are what lets a runtime
 * validate a call the model made against the shape the vendor promised, and the provider decode the
 * result it gets back. The two behavioural flags — [providerExecuted] and [supportsDeferredResults] —
 * DO ride onto the instantiated tool, because the runtime's loop needs them to decide who dispatches
 * a call and whether an unanswered one is an error.
 */
public class ProviderToolFactory internal constructor(
    /**
     * Vendor-namespaced identifier, `vendor.tool_name` — the stable join key.
     *
     * It is what [Tool.ProviderDefined.id] carries, so a provider matches an incoming tool back to its
     * definition by id and never by the name, which the caller is free to change.
     */
    public val id: String,
    /** What the VENDOR calls this tool on the wire. */
    public val wireName: String,
    public val inputSchema: JsonSchema,
    public val outputSchema: JsonSchema?,
    /** Whether the vendor runs it. False means the runtime has to. */
    public val providerExecuted: Boolean,
    /**
     * Whether a result may arrive in a later turn than the call.
     *
     * True for the vendor tools that hand off to a client tool mid-flight: the vendor's own result is
     * deferred until the client's comes back, so a runtime that requires every call to be answered
     * within its turn would report a missing result for a call that is merely still running.
     */
    public val supportsDeferredResults: Boolean,
) {

    /**
     * Instantiates the tool for one call.
     *
     * [name] defaults to the vendor's own, which is what a caller wants unless it is deliberately
     * renaming the tool for the model — in which case [ToolNameMapping] translates both directions and
     * [id] is what keeps the two ends joined.
     *
     * [args] is the vendor's configuration blob, passed through untouched: a max result count, an
     * allowed-domain list, a display size. Passing it as data is what lets a new vendor option ship
     * without a change here.
     */
    public operator fun invoke(
        args: JsonObject = EmptyArgs,
        name: String = wireName,
    ): Tool.ProviderDefined = Tool.ProviderDefined(
        name = name,
        id = id,
        args = args,
        providerExecuted = providerExecuted,
        supportsDeferredResults = supportsDeferredResults,
    )

    private companion object {
        val EmptyArgs = JsonObject(emptyMap())
    }
}

/** A vendor-specified tool the CLIENT executes. See [ProviderToolFactory]. */
public fun providerDefinedTool(
    id: String,
    wireName: String,
    inputSchema: JsonSchema,
    outputSchema: JsonSchema? = null,
): ProviderToolFactory = ProviderToolFactory(
    id = requireNamespaced(id),
    wireName = wireName,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    providerExecuted = false,
    supportsDeferredResults = false,
)

/** A tool the VENDOR executes on its own servers. See [ProviderToolFactory]. */
public fun providerExecutedTool(
    id: String,
    wireName: String,
    inputSchema: JsonSchema,
    outputSchema: JsonSchema,
    supportsDeferredResults: Boolean = false,
): ProviderToolFactory = ProviderToolFactory(
    id = requireNamespaced(id),
    wireName = wireName,
    inputSchema = inputSchema,
    outputSchema = outputSchema,
    providerExecuted = true,
    supportsDeferredResults = supportsDeferredResults,
)

/**
 * The id → vendor-name table [ToolNameMapping.from] takes.
 *
 * The two compose on the id: a factory declares what the vendor calls its tool, and the mapping
 * translates whatever the caller named it back to that, in both directions.
 */
public fun List<ProviderToolFactory>.wireToolNames(): Map<String, String> =
    associate { it.id to it.wireName }

/**
 * An id that names no vendor is a collision waiting to happen: two providers with a `web_search` tool
 * would be indistinguishable to a runtime matching on id, which is the one thing the id is for.
 */
private fun requireNamespaced(id: String): String {
    require(id.substringBefore('.').isNotEmpty() && id.substringAfter('.', "").isNotEmpty()) {
        "Provider tool id must be 'vendor.tool_name', was '$id'"
    }
    return id
}
