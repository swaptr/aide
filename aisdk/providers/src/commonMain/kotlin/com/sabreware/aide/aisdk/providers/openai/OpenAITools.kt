package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerDefinedTool
import com.sabreware.aide.aisdk.util.providerExecutedTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Responses API's own tools, as factories — `OpenAITools.webSearch(args)` is the reference's
 * `openai.tools.webSearch(args)`.
 *
 * Thirteen tools in two kinds, and the kind is the one fact a caller cannot get wrong here because the
 * factory fixes it. **Provider-executed** tools — search, file search, the code interpreter, image
 * generation, MCP and programmatic tool calling — run on OpenAI's servers and come back as a call/result
 * pair inside the turn; the runtime never dispatches one. **Provider-defined** tools — the computer, the
 * two shells, `apply_patch`, `custom` and tool search — are shaped by OpenAI but RUN BY THE CLIENT: the
 * model emits a call in the vendor's schema and the runtime has to act on it. A caller that built one of
 * these by hand with the flag the wrong way round would either wait forever for a result OpenAI was
 * never going to send, or try to execute a search it holds no implementation of.
 *
 * The input schemas are what the MODEL fills in, so the executed tools' are empty — their configuration
 * (which vector stores, which domains, what image size) rides in `args` at declaration time and is
 * spelled out per factory below. The output schemas are what OpenAI promises to send back. Both are
 * cargo: ported from the reference's zod declarations and never validated here.
 *
 * This object is also the single source of the id → wire-name table the Responses request builder
 * consults (`OpenAIProviderToolNames`), so a tool exists on the wire exactly when it exists here.
 */
public object OpenAITools {

    /**
     * Web search with citations.
     *
     * `args`: `externalWebAccess` (fetch live pages, or cached), `filters.allowedDomains` /
     * `filters.blockedDomains` (at most 100 each, no scheme), `searchContextSize` (`low` / `medium` /
     * `high`) and `userLocation` (`type: "approximate"` plus `country` / `city` / `region` /
     * `timezone`). Every key is renamed to OpenAI's snake_case on the way out.
     */
    public val webSearch: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.web_search",
        wireName = "web_search",
        inputSchema = emptyObjectSchema(),
        outputSchema = objectSchema {
            put("action", searchActionSchema(withQueries = true))
            put(
                "sources",
                array(
                    anyOf(
                        objectSchema("type", "url") {
                            put("type", literal("url"))
                            put("url", string())
                        },
                        objectSchema("type", "name") {
                            put("type", literal("api"))
                            put("name", string())
                        },
                    ),
                ),
            )
        },
    )

    /**
     * The earlier `web_search_preview` surface.
     *
     * A separate tool rather than a flag on [webSearch]: the two are different wire names, a deployment
     * that serves one 400s on the other, and both answer with the same `web_search_call` item — which
     * is why the request builder records which of the two was offered. `args`: `searchContextSize` and
     * `userLocation`, as for [webSearch].
     */
    public val webSearchPreview: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.web_search_preview",
        wireName = "web_search_preview",
        inputSchema = emptyObjectSchema(),
        outputSchema = objectSchema { put("action", searchActionSchema(withQueries = false)) },
    )

    /**
     * Retrieval over vector stores the caller uploaded.
     *
     * `args` REQUIRES `vectorStoreIds`; `maxNumResults`, `ranking` (`ranker`, `scoreThreshold`) and
     * `filters` (a comparison or compound filter, sent as-is) are optional. `ranking` goes out as
     * `ranking_options`.
     */
    public val fileSearch: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.file_search",
        wireName = "file_search",
        inputSchema = emptyObjectSchema(),
        outputSchema = objectSchema("queries", "results") {
            put("queries", array(string()))
            // Null when the search ran and matched nothing — not the same as not having searched.
            put(
                "results",
                nullable(
                    array(
                        objectSchema("attributes", "fileId", "filename", "score", "text") {
                            put("attributes", record(anyValue()))
                            put("fileId", string())
                            put("filename", string())
                            put("score", number())
                            put("text", string())
                        },
                    ),
                ),
            )
        },
    )

    /**
     * Python in a sandboxed container.
     *
     * `args`: `container`, either an existing container id (a string) or `{fileIds: [...]}` to seed a
     * new one. OpenAI requires the field, so an absent one goes out as `{"type": "auto"}` — the
     * reference does the same, and a request without it is a 400. The one executed tool whose INPUT is
     * not empty: the model sends the `code` it ran and the `containerId` it ran in.
     */
    public val codeInterpreter: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.code_interpreter",
        wireName = "code_interpreter",
        inputSchema = objectSchema("containerId") {
            put("code", nullable(string()))
            put("containerId", string())
        },
        outputSchema = objectSchema {
            put(
                "outputs",
                nullable(
                    array(
                        anyOf(
                            objectSchema("type", "logs") {
                                put("type", literal("logs"))
                                put("logs", string())
                            },
                            objectSchema("type", "url") {
                                put("type", literal("image"))
                                put("url", string())
                            },
                        ),
                    ),
                ),
            )
        },
    )

    /**
     * Image generation from inside a turn; the picture comes back base64 in `result`.
     *
     * `args`: `action` (`generate` / `edit` / `auto`), `background` (`auto` / `opaque` /
     * `transparent`), `inputFidelity` (`low` / `high`), `inputImageMask` (`fileId` and/or `imageUrl`),
     * `model` (default `gpt-image-1`), `moderation` (`auto` / `low`), `outputCompression` (0–100),
     * `outputFormat` (`png` / `jpeg` / `webp`), `partialImages` (0–3, streaming only), `quality`
     * (`auto` / `low` / `medium` / `high`, plus `xhigh` / `max` on the GPT Image 2.5 models) and `size`
     * (`auto`, `1024x1024`, `1024x1536`, `1536x1024`, or any `WIDTHxHEIGHT` with both divisible by 16
     * on GPT Image 2 and later). Every value is cargo: the vendor validates it, this library does not.
     */
    public val imageGeneration: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.image_generation",
        wireName = "image_generation",
        inputSchema = emptyObjectSchema(),
        outputSchema = objectSchema("result") { put("result", string()) },
    )

    /**
     * Let OpenAI call a remote MCP server, or one of its service connectors, on the caller's behalf.
     *
     * `args` REQUIRES `serverLabel` and one of `serverUrl` or `connectorId`; `allowedTools` (a name
     * list, or `{readOnly, toolNames}`), `authorization`, `headers`, `requireApproval` (`always` /
     * `never` / `{never: {toolNames}}`) and `serverDescription` are optional. `headers` is passed
     * through untouched — its keys are HTTP header names the caller chose, which is why the request
     * builder renames per tool rather than walking the whole blob.
     *
     * `requireApproval` is NOT defaulted to `never` here, where the reference does. Absent, OpenAI asks
     * before each call and the request comes back as a `ToolApprovalRequest` the runtime already knows
     * how to answer; a library that silently waives that on the caller's behalf has made a policy
     * decision that belongs to the caller.
     */
    public val mcp: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.mcp",
        wireName = "mcp",
        inputSchema = emptyObjectSchema(),
        outputSchema = objectSchema("type", "serverLabel", "name", "arguments") {
            put("type", literal("call"))
            put("serverLabel", string())
            put("name", string())
            put("arguments", string())
            put("output", nullable(string()))
            // A string or whatever JSON the server sent back; deliberately untyped.
            put("error", anyValue())
        },
    )

    /**
     * Hosted JavaScript that orchestrates the call's other tools.
     *
     * The one tool here that supports DEFERRED results: the program may call a client tool mid-run, in
     * which case OpenAI's own result waits for the client's, and a runtime requiring every call
     * answered within its turn would report a missing result for one that is merely still running.
     * The `fingerprint` in its input is a replay token and must be carried back verbatim.
     *
     * The reference additionally wraps this tool in its runtime's `experimental_toolCaller`, which
     * stamps `allowedCallers: ["programmatic"]` onto the other tools' provider options; that is a
     * runtime affordance, not a wire one, and is not part of this factory.
     */
    public val programmaticToolCalling: ProviderToolFactory = providerExecutedTool(
        id = "$OPENAI_PROVIDER_ID.programmatic_tool_calling",
        wireName = "programmatic_tool_calling",
        inputSchema = objectSchema("code", "fingerprint") {
            put("code", string())
            put("fingerprint", string())
        },
        outputSchema = objectSchema("result", "status") {
            put("result", string())
            put("status", enumeration("completed", "incomplete"))
        },
        supportsDeferredResults = true,
    )

    /**
     * Computer use: batched UI actions the CLIENT executes, answered with a screenshot.
     *
     * Provider-DEFINED — OpenAI shapes the call, the runtime has to perform it. Run it in an isolated
     * environment, treat what is on screen as untrusted, and gate consequential actions on a human:
     * `pendingSafetyChecks` is the model flagging exactly those, and the answer acknowledges them by id.
     * Takes no `args`.
     */
    public val computer: ProviderToolFactory = providerDefinedTool(
        id = "$OPENAI_PROVIDER_ID.computer",
        wireName = "computer",
        inputSchema = objectSchema("actions", "pendingSafetyChecks", "status") {
            put("actions", array(computerActionSchema()))
            put("pendingSafetyChecks", array(safetyCheckSchema()))
            put("status", enumeration("in_progress", "completed", "incomplete"))
        },
        outputSchema = objectSchema("output") {
            put(
                "output",
                anyOf(
                    screenshotSchema(requiredField = "imageUrl"),
                    screenshotSchema(requiredField = "fileId"),
                ),
            )
            put("acknowledgedSafetyChecks", array(safetyCheckSchema()))
        },
    )

    /**
     * A shell command the CLIENT runs — `gpt-5-codex`'s tool.
     *
     * Provider-defined: the model proposes `command`, `workingDirectory`, `env`, a timeout; the
     * runtime executes and returns the output. Sandbox it. Takes no `args`.
     */
    public val localShell: ProviderToolFactory = providerDefinedTool(
        id = "$OPENAI_PROVIDER_ID.local_shell",
        wireName = "local_shell",
        inputSchema = objectSchema("action") {
            put(
                "action",
                objectSchema("type", "command") {
                    put("type", literal("exec"))
                    put("command", array(string()))
                    put("timeoutMs", number())
                    put("user", string())
                    put("workingDirectory", string())
                    put("env", record(string()))
                },
            )
        },
        outputSchema = objectSchema("output") { put("output", string()) },
    )

    /**
     * The GPT-5.1 shell: a list of commands, each answered with stdout, stderr and an outcome.
     *
     * Provider-defined; sandbox or allow-list before forwarding anything to a real shell. `args`:
     * `environment`, one of `{type: "local", skills?}`, `{type: "containerReference", containerId}` or
     * `{type: "containerAuto", fileIds?, memoryLimit?, networkPolicy?, skills?}` — mapped arm by arm on
     * the way out, since each arm carries a different field set and the discriminator itself is renamed.
     */
    public val shell: ProviderToolFactory = providerDefinedTool(
        id = "$OPENAI_PROVIDER_ID.shell",
        wireName = "shell",
        inputSchema = objectSchema("action") {
            put(
                "action",
                objectSchema("commands") {
                    put("commands", array(string()))
                    put("timeoutMs", number())
                    put("maxOutputLength", number())
                },
            )
        },
        outputSchema = objectSchema("output") {
            put(
                "output",
                array(
                    objectSchema("stdout", "stderr", "outcome") {
                        put("stdout", string())
                        put("stderr", string())
                        put(
                            "outcome",
                            anyOf(
                                objectSchema("type") { put("type", literal("timeout")) },
                                objectSchema("type", "exitCode") {
                                    put("type", literal("exit"))
                                    put("exitCode", number())
                                },
                            ),
                        )
                    },
                ),
            )
        },
    )

    /**
     * Structured file edits — create, update, delete — that the CLIENT applies and reports on.
     *
     * Provider-defined. Configured entirely by being present: the reference emits a bare `{type}` and
     * ignores whatever `args` ride along, and so does the request builder.
     */
    public val applyPatch: ProviderToolFactory = providerDefinedTool(
        id = "$OPENAI_PROVIDER_ID.apply_patch",
        wireName = "apply_patch",
        inputSchema = objectSchema("callId", "operation") {
            put("callId", string())
            put(
                "operation",
                anyOf(
                    objectSchema("type", "path", "diff") {
                        put("type", literal("create_file"))
                        put("path", string())
                        put("diff", string())
                    },
                    objectSchema("type", "path") {
                        put("type", literal("delete_file"))
                        put("path", string())
                    },
                    objectSchema("type", "path", "diff") {
                        put("type", literal("update_file"))
                        put("path", string())
                        put("diff", string())
                    },
                ),
            )
        },
        outputSchema = objectSchema("status") {
            put("status", enumeration("completed", "failed"))
            put("output", string())
        },
    )

    /**
     * A tool whose input is free text constrained by a grammar, not JSON.
     *
     * The one provider tool whose NAME reaches the wire: the model calls it by the caller's name and
     * the reply names it back, which is why the request builder tracks custom tools by name. `args`:
     * `description`, `format` (`{type: "text"}`, or `{type: "grammar", syntax: "regex" | "lark",
     * definition}`) and `async` (GPT-6 and later: the model continues without waiting for the result;
     * dropped with a warning on an earlier model). Its input schema is a plain string, and it declares
     * no output schema.
     */
    public val customTool: ProviderToolFactory = providerDefinedTool(
        id = "$OPENAI_PROVIDER_ID.custom",
        wireName = "custom",
        inputSchema = string(),
    )

    /**
     * Search over deferred tools — functions declared with `defer_loading` that the model pulls into
     * context only when it decides it needs them.
     *
     * `args`: `execution` (`server`, the default, or `client`), plus `description` and `parameters` for
     * the client-executed form, where the model emits a `tool_search_call` and the runtime performs the
     * lookup. Provider-defined for that reason; with server execution the call is answered by OpenAI.
     */
    public val toolSearch: ProviderToolFactory = providerDefinedTool(
        id = "$OPENAI_PROVIDER_ID.tool_search",
        wireName = "tool_search",
        inputSchema = objectSchema {
            put("arguments", anyValue())
            // Snake-cased on purpose: the reference keeps the wire spelling for this one field.
            put("call_id", nullable(string()))
        },
        outputSchema = objectSchema("tools") { put("tools", array(record(anyValue()))) },
    )

    /** Every OpenAI tool, in the reference's order; the source of `OpenAIProviderToolNames`. */
    public val all: List<ProviderToolFactory> = listOf(
        applyPatch,
        customTool,
        codeInterpreter,
        computer,
        fileSearch,
        imageGeneration,
        localShell,
        shell,
        webSearchPreview,
        webSearch,
        mcp,
        programmaticToolCalling,
        toolSearch,
    )
}

// ---- JSON Schema, spelled the way the reference's zod declarations read ----------------------------
//
// Functions rather than shared vals: a top-level val in this file would live on the file class, and
// building the first tool above would force that class to initialize while `OpenAITools` is still
// mid-construction — the ordering trap `XaiTools` documents. A function has no state to be null.

/** `z.object({...})` — properties plus the ones that must be present. */
private fun objectSchema(vararg required: String, properties: JsonObjectBuilder.() -> Unit): JsonSchema =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject(properties))
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

/** `z.object({})`: the model supplies nothing; declaring the tool is the whole activation. */
private fun emptyObjectSchema(): JsonSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { })
    put("additionalProperties", false)
}

private fun string(): JsonSchema = buildJsonObject { put("type", "string") }

private fun number(): JsonSchema = buildJsonObject { put("type", "number") }

private fun array(items: JsonSchema): JsonSchema = buildJsonObject {
    put("type", "array")
    put("items", items)
}

private fun enumeration(vararg values: String): JsonSchema = buildJsonObject {
    put("type", "string")
    putJsonArray("enum") { values.forEach { add(it) } }
}

private fun literal(value: String): JsonSchema = buildJsonObject {
    put("type", "string")
    put("const", value)
}

private fun anyOf(vararg options: JsonSchema): JsonSchema = buildJsonObject {
    putJsonArray("anyOf") { options.forEach { add(it) } }
}

private fun nullable(schema: JsonSchema): JsonSchema = anyOf(schema, buildJsonObject { put("type", "null") })

/** `z.record(z.string(), values)`. */
private fun record(values: JsonSchema): JsonSchema = buildJsonObject {
    put("type", "object")
    put("additionalProperties", values)
}

/** `z.unknown()`: the empty schema, which accepts anything. */
private fun anyValue(): JsonSchema = JsonObject(emptyMap())

/**
 * What a web search did: a query, a page opened, a pattern found in it.
 *
 * [withQueries] is the difference between the two search tools' outputs: `web_search` reports the
 * list of queries the model ran, `web_search_preview` only ever reported one.
 */
private fun searchActionSchema(withQueries: Boolean): JsonSchema = anyOf(
    objectSchema("type") {
        put("type", literal("search"))
        put("query", string())
        if (withQueries) put("queries", array(string()))
    },
    objectSchema("type") {
        put("type", literal("openPage"))
        put("url", nullable(string()))
    },
    objectSchema("type") {
        put("type", literal("findInPage"))
        put("url", nullable(string()))
        put("pattern", nullable(string()))
    },
)

private fun safetyCheckSchema(): JsonSchema = objectSchema("id") {
    put("id", string())
    put("code", string())
    put("message", string())
}

/** The screenshot the client returns; the two arms differ only in which reference is mandatory. */
private fun screenshotSchema(requiredField: String): JsonSchema = objectSchema("type", requiredField) {
    put("type", literal("computer_screenshot"))
    put("imageUrl", string())
    put("fileId", string())
    put("detail", enumeration("auto", "low", "high", "original"))
}

private fun computerActionSchema(): JsonSchema {
    val keys = array(string())
    return anyOf(
        objectSchema("type", "button", "x", "y") {
            put("type", literal("click"))
            put("button", enumeration("left", "right", "wheel", "back", "forward"))
            put("x", number())
            put("y", number())
            put("keys", keys)
        },
        objectSchema("type", "x", "y") {
            put("type", literal("double_click"))
            put("x", number())
            put("y", number())
            put("keys", keys)
        },
        objectSchema("type", "path") {
            put("type", literal("drag"))
            put(
                "path",
                array(
                    objectSchema("x", "y") {
                        put("x", number())
                        put("y", number())
                    },
                ),
            )
            put("keys", keys)
        },
        objectSchema("type", "keys") {
            put("type", literal("keypress"))
            put("keys", keys)
        },
        objectSchema("type", "x", "y") {
            put("type", literal("move"))
            put("x", number())
            put("y", number())
            put("keys", keys)
        },
        objectSchema("type") { put("type", literal("screenshot")) },
        objectSchema("type", "x", "y", "scrollX", "scrollY") {
            put("type", literal("scroll"))
            put("x", number())
            put("y", number())
            put("scrollX", number())
            put("scrollY", number())
            put("keys", keys)
        },
        objectSchema("type", "text") {
            put("type", literal("type"))
            put("text", string())
        },
        objectSchema("type") { put("type", literal("wait")) },
    )
}
