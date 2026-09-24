package com.sabreware.aide.aisdk.providers.azure

import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerExecutedTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The server-side tools an Azure OpenAI deployment serves.
 *
 * **The ids are OpenAI's, and that is not an oversight.** Azure hosts OpenAI's own Responses API, so a
 * deployment reads `web_search` from the same item types and answers with the same call shapes; the
 * reference makes the point by re-exporting OpenAI's tool objects unchanged rather than minting Azure
 * ones. Renaming the ids here would break the join: our Responses model matches an incoming tool back
 * to its definition by id, and `azure.web_search` matches nothing it knows.
 *
 * Five of OpenAI's set, which is what Azure documents as available. The two OpenAI tools a client has
 * to execute — computer use and the local shell — are absent for the same reason they are absent from
 * the reference's Azure surface: Azure does not offer them.
 *
 * Every one is provider-EXECUTED: the deployment runs it and streams the result back inside the
 * assistant turn, so the runtime never dispatches one.
 */
public object AzureTools {

    /**
     * Web search.
     *
     * The input schema is empty because the model composes its own query — the tool is activated by
     * being offered, and `args` carries the configuration (a location hint, a context size) rather
     * than the search itself.
     */
    public val webSearch: ProviderToolFactory = providerExecutedTool(
        id = "openai.web_search",
        wireName = "web_search",
        inputSchema = JsonObject(emptyMap()),
        outputSchema = searchOutputSchema(),
    )

    /**
     * The earlier `web_search_preview` surface, still the one some deployments expose.
     *
     * Kept as a separate tool rather than a flag on [webSearch]: the two are different wire names, and
     * a deployment that serves one 400s on the other.
     */
    public val webSearchPreview: ProviderToolFactory = providerExecutedTool(
        id = "openai.web_search_preview",
        wireName = "web_search_preview",
        inputSchema = JsonObject(emptyMap()),
        outputSchema = searchOutputSchema(),
    )

    /** Retrieval over vector stores attached to the deployment. */
    public val fileSearch: ProviderToolFactory = providerExecutedTool(
        id = "openai.file_search",
        wireName = "file_search",
        inputSchema = JsonObject(emptyMap()),
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("queries") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("results") { put("type", "array") }
            }
        },
    )

    /**
     * Python execution in a sandboxed container.
     *
     * The one tool here whose input is not empty: the model sends the `code` it wants run and the
     * `containerId` it should run in, and a caller reading a call needs both to make sense of it.
     */
    public val codeInterpreter: ProviderToolFactory = providerExecutedTool(
        id = "openai.code_interpreter",
        wireName = "code_interpreter",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("code") { put("type", "string") }
                putJsonObject("containerId") { put("type", "string") }
            }
        },
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("outputs") { put("type", "array") }
            }
        },
    )

    /** Image generation from inside a turn, returning the picture as assistant content. */
    public val imageGeneration: ProviderToolFactory = providerExecutedTool(
        id = "openai.image_generation",
        wireName = "image_generation",
        inputSchema = JsonObject(emptyMap()),
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("result") { put("type", "string") }
            }
        },
    )

    /** Every Azure tool, for a [com.sabreware.aide.aisdk.util.ToolNameMapping]. */
    public val all: List<ProviderToolFactory> =
        listOf(webSearch, webSearchPreview, fileSearch, codeInterpreter, imageGeneration)
}

/**
 * What both search tools report back: what the model did, and where it read.
 *
 * `action` and `sources` are the two fields worth modelling — the rest of the payload rides through
 * `providerMetadata` untouched, which is what keeps a field OpenAI adds next month from being dropped
 * by our own parser.
 */
private fun searchOutputSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") { put("type", "object") }
        putJsonObject("sources") { put("type", "array") }
    }
}
