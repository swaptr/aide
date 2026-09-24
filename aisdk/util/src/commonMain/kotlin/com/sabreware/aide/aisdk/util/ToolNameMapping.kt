package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.Tool

/**
 * Translates between the tool names a client uses and the ones a vendor insists on.
 *
 * Provider-defined tools are the reason this exists. A vendor knows its built-in tool by a fixed name —
 * `web_search_20250305`, say — while the client refers to it by whatever it called it. Without a
 * translation both directions, a call comes back naming a tool the client has never heard of, and the
 * result goes out naming one the vendor does not recognise.
 *
 * Unmapped names pass through unchanged, so ordinary function tools need no entry and cost nothing.
 */
public class ToolNameMapping private constructor(
    private val customToProvider: Map<String, String>,
    private val providerToCustom: Map<String, String>,
) {

    /** The vendor's name for a tool the client calls [customToolName]. */
    public fun toProviderToolName(customToolName: String): String =
        customToProvider[customToolName] ?: customToolName

    /** The client's name for a tool the vendor calls [providerToolName]. */
    public fun toCustomToolName(providerToolName: String): String =
        providerToCustom[providerToolName] ?: providerToolName

    public companion object {

        /** A mapping that changes nothing, for a call with no provider-defined tools. */
        public val Identity: ToolNameMapping = ToolNameMapping(emptyMap(), emptyMap())

        /**
         * Builds a mapping from the call's tools.
         *
         * [providerToolNames] maps a provider tool's stable id to the name that vendor uses on the wire;
         * only [Tool.ProviderDefined] entries with a known id are mapped, since a function tool is
         * already named the same on both sides.
         */
        public fun from(
            tools: List<Tool>?,
            providerToolNames: Map<String, String>,
        ): ToolNameMapping {
            if (tools.isNullOrEmpty() || providerToolNames.isEmpty()) return Identity
            val customToProvider = mutableMapOf<String, String>()
            val providerToCustom = mutableMapOf<String, String>()
            tools.filterIsInstance<Tool.ProviderDefined>().forEach { tool ->
                providerToolNames[tool.id]?.let { providerName ->
                    customToProvider[tool.name] = providerName
                    providerToCustom[providerName] = tool.name
                }
            }
            return ToolNameMapping(customToProvider, providerToCustom)
        }
    }
}
