package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerExecutedTool
import com.sabreware.aide.aisdk.util.wireToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * xAI's eight agent tools: web and X search, code execution, image generation and viewing, file
 * search, and a hosted MCP server.
 *
 * **Every one is provider-executed.** xAI runs them on its own servers and streams the result back
 * inside the assistant turn, so the runtime never dispatches one and could not — it holds no
 * implementation of "search X". Declaring any of them client-executed would make the loop wait for a
 * result it is supposed to be receiving, and then report the call as unanswered.
 *
 * None supports deferred results: xAI answers within the turn that called, so an unanswered call here
 * really is an error rather than a job still running.
 *
 * The input schemas are empty objects, which is not an oversight — it is what the vendor specifies. A
 * caller does not fill in arguments for these; the MODEL decides what to search for, and the caller's
 * configuration (which domains are allowed, which vector store to search) rides in [args] at
 * declaration time instead. The output schemas are the shapes xAI promises to send back, kept so a
 * runtime can validate a result against what was advertised.
 */
public object XaiTools {

    /**
     * `z.object({})` on the reference side: these tools take no model-supplied input.
     *
     * Declared INSIDE the object and first. As a private top-level val it lived on the file class,
     * so building the first tool forced that class to initialize — which computed
     * [xaiProviderToolNames] from `XaiTools.all` while `all` was still null. Object initialization
     * runs in declaration order, so this has to come before its first reader.
     */
    private val EmptyObjectSchema: JsonSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("additionalProperties", false)
    }

    /**
     * Web search, xAI's server-side crawler.
     *
     * `args` accepts `allowedDomains` / `excludedDomains` (at most five each, per the vendor),
     * `enableImageSearch` and `enableImageUnderstanding`.
     */
    public val webSearch: ProviderToolFactory = providerExecutedTool(
        id = "xai.web_search",
        wireName = "web_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                // `action` is a discriminated union on the reference side; carried here as an object
                // rather than re-declared, because a schema is cargo in this library and narrowing one
                // is how a vendor's next action type becomes a validation failure of ours.
                putJsonObject("action") { put("type", "object") }
                putJsonObject("sources") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "object") }
                }
            }
        },
    )

    /**
     * Search over X posts.
     *
     * `args` accepts `allowedXHandles` / `excludedXHandles` (at most ten each), `fromDate`, `toDate`,
     * `enableImageUnderstanding` and `enableVideoUnderstanding`.
     */
    public val xSearch: ProviderToolFactory = providerExecutedTool(
        id = "xai.x_search",
        wireName = "x_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string") }
                putJsonObject("posts") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("author") { put("type", "string") }
                            putJsonObject("text") { put("type", "string") }
                            putJsonObject("url") { put("type", "string") }
                            putJsonObject("likes") { put("type", "number") }
                        }
                    }
                }
            }
            putJsonArray("required") { add("query"); add("posts") }
        },
    )

    /**
     * Run code in xAI's sandbox.
     *
     * Named `code_execution` here and `code_interpreter` on the wire — the one tool whose two names
     * differ, which is exactly what [ProviderToolFactory.id] exists to keep joined.
     */
    public val codeExecution: ProviderToolFactory = providerExecutedTool(
        id = "xai.code_execution",
        wireName = "code_interpreter",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("output") { put("type", "string") }
                putJsonObject("error") { put("type", "string") }
            }
            putJsonArray("required") { add("output") }
        },
    )

    /** Look at an image the conversation already contains. Takes no configuration. */
    public val viewImage: ProviderToolFactory = providerExecutedTool(
        id = "xai.view_image",
        wireName = "view_image",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("description") { put("type", "string") }
                putJsonObject("objects") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("description") }
        },
    )

    /** Watch a video hosted on X. Takes no configuration. */
    public val viewXVideo: ProviderToolFactory = providerExecutedTool(
        id = "xai.view_x_video",
        wireName = "view_x_video",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("transcript") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("duration") { put("type", "number") }
            }
            putJsonArray("required") { add("description") }
        },
    )

    /**
     * Generate or edit an image mid-conversation.
     *
     * `args` accepts `action`: `auto` (the default — both), `generate`, or `edit`. The result arrives
     * as base64 in `result`, with the prompt the model wrote for the image model in `prompt`.
     */
    public val imageGeneration: ProviderToolFactory = providerExecutedTool(
        id = "xai.image_generation",
        wireName = "image_generation",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("result") { put("type", "string") }
                putJsonObject("prompt") { put("type", "string") }
            }
            putJsonArray("required") { add("result") }
        },
    )

    /**
     * Search the caller's uploaded collections.
     *
     * `args` REQUIRES `vectorStoreIds` — xAI has no default collection, so a file search declared
     * without one searches nothing and reports no error. `maxNumResults` is optional.
     */
    public val fileSearch: ProviderToolFactory = providerExecutedTool(
        id = "xai.file_search",
        wireName = "file_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("queries") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                // Nullable rather than absent when nothing matched: a null `results` is xAI saying it
                // searched and found nothing, which is not the same as not having searched.
                putJsonObject("results") { put("type", "array") }
            }
            putJsonArray("required") { add("queries") }
        },
    )

    /**
     * Let xAI call a hosted MCP server on the caller's behalf.
     *
     * `args` REQUIRES `serverUrl`, and accepts `serverLabel`, `serverDescription`, `allowedTools`,
     * `headers` and `authorization`. Its id is `xai.mcp`, matching the vendor, while the model sees
     * `mcp`.
     */
    public val mcpServer: ProviderToolFactory = providerExecutedTool(
        id = "xai.mcp",
        wireName = "mcp",
        inputSchema = EmptyObjectSchema,
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("arguments") { put("type", "string") }
                // Deliberately untyped: what an MCP tool returns depends on which tool was called.
                putJsonObject("result") { }
            }
            putJsonArray("required") { add("name"); add("arguments") }
        },
    )

    /** Every xAI agent tool, for a caller offering the lot and for [xaiProviderToolNames]. */
    public val all: List<ProviderToolFactory> = listOf(
        webSearch, xSearch, codeExecution, viewImage, viewXVideo, imageGeneration, fileSearch, mcpServer,
    )
}

/** Tool id to the name xAI uses on the wire, for `ToolNameMapping`. */
internal val xaiProviderToolNames: Map<String, String> = XaiTools.all.wireToolNames()

/**
 * How each tool's declaration-time `args` is spelled on xAI's wire.
 *
 * A per-tool table rather than a generic camelCase-to-snake_case pass, for the reason the OpenAI
 * Responses builder gives: an MCP tool's `headers` are arbitrary HTTP header NAMES used as keys, and a
 * recursive rename corrupts every one of them. Only the documented options are renamed; anything else
 * a caller passes rides through untouched, so an option xAI ships after this file was written still
 * reaches the wire.
 *
 * The `type` each tool becomes is [ProviderToolFactory.wireName], which is why `code_execution` and
 * `code_interpreter` do not need an entry here.
 */
internal val XaiToolArgumentNames: Map<String, Map<String, String>> = mapOf(
    "xai.web_search" to mapOf(
        "allowedDomains" to "allowed_domains",
        "excludedDomains" to "excluded_domains",
        "enableImageSearch" to "enable_image_search",
        "enableImageUnderstanding" to "enable_image_understanding",
    ),
    "xai.x_search" to mapOf(
        "allowedXHandles" to "allowed_x_handles",
        "excludedXHandles" to "excluded_x_handles",
        "fromDate" to "from_date",
        "toDate" to "to_date",
        "enableImageUnderstanding" to "enable_image_understanding",
        "enableVideoUnderstanding" to "enable_video_understanding",
    ),
    "xai.file_search" to mapOf(
        "vectorStoreIds" to "vector_store_ids",
        "maxNumResults" to "max_num_results",
    ),
    "xai.mcp" to mapOf(
        "serverUrl" to "server_url",
        "serverLabel" to "server_label",
        "serverDescription" to "server_description",
        "allowedTools" to "allowed_tools",
        // `headers` and `authorization` are already the wire spelling, and `headers` in particular must
        // never be walked into — see the KDoc above.
    ),
    // view_image, view_x_video and image_generation carry either nothing or `action`, which xAI spells
    // the same way we do.
)

/**
 * xAI's tool body: the wire `type`, plus the caller's configuration with the documented keys renamed.
 *
 * Kept next to the tools it describes rather than inside the Responses request builder, because the
 * builder serves four vendors now and each one's tool dialect belongs with that vendor.
 */
internal fun xaiToolBody(id: String, wireName: String, args: JsonObject): JsonObject {
    val renames = XaiToolArgumentNames[id].orEmpty()
    return buildJsonObject {
        put("type", wireName)
        args.forEach { (key, value) -> put(renames[key] ?: key, value) }
    }
}
