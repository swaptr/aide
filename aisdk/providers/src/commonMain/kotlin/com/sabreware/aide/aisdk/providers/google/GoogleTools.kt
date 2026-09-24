package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.JsonSchema
import com.sabreware.aide.aisdk.Tool
import com.sabreware.aide.aisdk.ToolChoice
import com.sabreware.aide.aisdk.Warning
import com.sabreware.aide.aisdk.util.ProviderToolFactory
import com.sabreware.aide.aisdk.util.providerExecutedTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Gemini's built-in tools — the seven the reference exports as `google.tools.*`.
 *
 * **Every one is provider-executed.** Google runs them on its own servers and folds the result into the
 * answer — a grounded citation, a fetched page, the output of the code it wrote — so the runtime never
 * dispatches one and could not, holding no search index of its own. None supports deferred results:
 * Gemini answers within the turn that called.
 *
 * The input schemas are empty for all but [codeExecution], and that is what the vendor specifies rather
 * than an omission: a caller does not fill in a search query, the MODEL decides what to look up, and
 * the caller's configuration (which file-search stores, which RAG corpus, a time-range filter) rides in
 * `args` at declaration time. Code execution is the one whose call the model writes out — the language
 * and the code — which is why it alone has an input to validate.
 *
 * On the wire every one of these is a SIBLING KEY of `functionDeclarations` in the `tools` array, never
 * an entry in it; [googleBuiltInTools] is the one table that says which key, and it is derived from the
 * factories here so an id can never exist on one side and not the other.
 */
public object GoogleTools {

    /**
     * `z.object({})` on the reference side: these tools take no model-supplied input.
     *
     * Declared inside the object and first: a top-level val would live on the file class, and building
     * the first tool would then initialize that class — which computes [googleBuiltInTools] from this
     * object while it is still half-built. Object initialization runs in declaration order.
     */
    private val EmptyObjectSchema: JsonSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        put("additionalProperties", false)
    }

    /**
     * Google Search grounding: real-time web content behind the answer, cited as sources.
     *
     * `args` accepts `searchTypes` (`webSearch`, `imageSearch`) and a `timeRangeFilter`
     * (`startTime`/`endTime`), passed through whole. Gemini 2.0 or newer.
     */
    public val googleSearch: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.google_search",
        wireName = "google_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = EmptyObjectSchema,
    )

    /**
     * Grounding on a compliance-focused web index — no customer-data logging, VPC service controls.
     *
     * Vertex AI only, Gemini 2.0 or newer; the Gemini API rejects it. Takes no configuration.
     */
    public val enterpriseWebSearch: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.enterprise_web_search",
        wireName = "enterprise_web_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = EmptyObjectSchema,
    )

    /** Google Maps grounding — places, distances, opening hours. Takes no configuration. */
    public val googleMaps: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.google_maps",
        wireName = "google_maps",
        inputSchema = EmptyObjectSchema,
        outputSchema = EmptyObjectSchema,
    )

    /** Fetches the URLs the prompt mentions and reads them into context. Takes no configuration. */
    public val urlContext: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.url_context",
        wireName = "url_context",
        inputSchema = EmptyObjectSchema,
        outputSchema = EmptyObjectSchema,
    )

    /**
     * Retrieval over Gemini File Search stores.
     *
     * `args` REQUIRES `fileSearchStoreNames` — fully-qualified store resource names,
     * `fileSearchStores/my-store-123` — and accepts `topK` and a `metadataFilter` (AIP-160 syntax).
     * Gemini 2.5 and Gemini 3.
     */
    public val fileSearch: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.file_search",
        wireName = "file_search",
        inputSchema = EmptyObjectSchema,
        outputSchema = EmptyObjectSchema,
    )

    /**
     * Python the model writes and Google runs.
     *
     * The one tool here whose call the model authors, so the input is real: the `language` and the
     * `code`. The result is the `outcome` (`OUTCOME_OK` and its siblings) and the program's `output`.
     * The language model reports the pair as a provider-executed call and its result, under this
     * tool's wire name.
     */
    public val codeExecution: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.code_execution",
        wireName = "code_execution",
        inputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("language") {
                    put("type", "string")
                    put("description", "The programming language of the code.")
                }
                putJsonObject("code") {
                    put("type", "string")
                    put("description", "The code to be executed.")
                }
            }
            putJsonArray("required") { add("language"); add("code") }
            put("additionalProperties", false)
        },
        outputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("outcome") {
                    put("type", "string")
                    put("description", "The outcome of the execution (e.g., \"OUTCOME_OK\").")
                }
                putJsonObject("output") {
                    put("type", "string")
                    put("description", "The output from the code execution.")
                }
            }
            putJsonArray("required") { add("outcome"); add("output") }
            put("additionalProperties", false)
        },
    )

    /**
     * RAG over a Vertex RAG Store. Vertex Gemini models only.
     *
     * `args` REQUIRES `ragCorpus` — `projects/{project}/locations/{location}/ragCorpora/{corpus}` — and
     * accepts `topK`. The only built-in whose configuration this layer RESHAPES on the way out: Google
     * spells it `retrieval.vertex_rag_store.rag_resources.rag_corpus`, snake_case and nested.
     */
    public val vertexRagStore: ProviderToolFactory = providerExecutedTool(
        id = "$GOOGLE_PROVIDER_ID.vertex_rag_store",
        wireName = "vertex_rag_store",
        inputSchema = EmptyObjectSchema,
        outputSchema = EmptyObjectSchema,
    )

    /** Every Gemini built-in, in the reference's order. */
    public val all: List<ProviderToolFactory> = listOf(
        googleSearch, enterpriseWebSearch, googleMaps, urlContext, fileSearch, codeExecution, vertexRagStore,
    )
}

/**
 * What Gemini can be asked to do, read off the model id.
 *
 * Google's ids are open-ended, so the classification excludes the generations known to need a legacy
 * request shape rather than listing the ones that do not — an unrecognised `gemini-*` inherits the
 * newest behaviour, which is what keeps a model released next month working without a table edit.
 */
internal data class GoogleModelCapabilities(
    /** The built-in tool suite: search, URL context, code execution, maps. Gemini 2 and newer. */
    val supportsGemini2Tools: Boolean,
    val supportsFileSearch: Boolean,
    /** Gemini 3 can mix function tools with built-in ones; earlier generations cannot. */
    val usesGemini3Features: Boolean,
)

internal fun googleModelCapabilities(modelId: String): GoogleModelCapabilities {
    val isGemini = GEMINI.containsMatchIn(modelId)
    val isGemini2 = GEMINI_2.containsMatchIn(modelId)
    val isPreGemini2 = GEMINI_1.containsMatchIn(modelId) ||
        PRE_GEMINI_2_ALIAS.containsMatchIn(modelId)
    val usesGemini3Features = isGemini && !isPreGemini2 && !isGemini2

    return GoogleModelCapabilities(
        supportsGemini2Tools = (isGemini && !isPreGemini2) || "nano-banana" in modelId.lowercase(),
        supportsFileSearch = GEMINI_2_5.containsMatchIn(modelId) || usesGemini3Features,
        usesGemini3Features = usesGemini3Features,
    )
}

private val GEMINI = Regex("(^|/)gemini-", RegexOption.IGNORE_CASE)
private val GEMINI_1 = Regex("(^|/)gemini-1([.-]|$)", RegexOption.IGNORE_CASE)
private val GEMINI_2 = Regex("(^|/)gemini-2([.-]|$)", RegexOption.IGNORE_CASE)
private val GEMINI_2_5 = Regex("(^|/)gemini-2\\.5([.-]|$)", RegexOption.IGNORE_CASE)
private val PRE_GEMINI_2_ALIAS =
    Regex("(^|/)(gemini-pro(-vision)?$|gemini-robotics-er-1\\.5([.-]|$))", RegexOption.IGNORE_CASE)

/**
 * How one built-in reaches the wire: which generation serves it, and the `tools` entry it becomes.
 *
 * Keyed by the factory rather than by a second copy of the id string, so the id exists in exactly one
 * place. A row is added by adding a factory; a factory with no row is the foreign-id case and is refused
 * with a warning rather than sent under a key Gemini has never heard of.
 */
internal class GoogleBuiltInTool(
    val factory: ProviderToolFactory,
    /** Whether a model with these capabilities serves the tool. */
    val supportedBy: (GoogleModelCapabilities) -> Boolean,
    /** Why not, for the warning that replaces it. */
    val unsupported: String,
    /** The `tools` entry, from the caller's declaration-time `args`. */
    val entry: (JsonObject) -> GoogleToolEntry,
)

private const val NEEDS_GEMINI_2 = "requires Gemini 2.0 or newer."

private val NoConfiguration = JsonObject(emptyMap())

/**
 * The one id → wire table, derived from [GoogleTools].
 *
 * `args` is passed through whole where Google takes a blob (search, file search): Google keeps adding
 * fields to these, and re-declaring them here would mean a new one is rejected by OUR parser. The tools
 * that take no configuration send an empty object regardless of `args`, which is the reference's
 * behaviour and what the vendor documents.
 */
internal val googleBuiltInTools: Map<String, GoogleBuiltInTool> = listOf(
    GoogleBuiltInTool(GoogleTools.googleSearch, { it.supportsGemini2Tools }, NEEDS_GEMINI_2) {
        GoogleToolEntry(googleSearch = it)
    },
    GoogleBuiltInTool(GoogleTools.enterpriseWebSearch, { it.supportsGemini2Tools }, NEEDS_GEMINI_2) {
        GoogleToolEntry(enterpriseWebSearch = NoConfiguration)
    },
    GoogleBuiltInTool(GoogleTools.googleMaps, { it.supportsGemini2Tools }, NEEDS_GEMINI_2) {
        GoogleToolEntry(googleMaps = NoConfiguration)
    },
    GoogleBuiltInTool(GoogleTools.urlContext, { it.supportsGemini2Tools }, NEEDS_GEMINI_2) {
        GoogleToolEntry(urlContext = NoConfiguration)
    },
    GoogleBuiltInTool(
        GoogleTools.fileSearch,
        { it.supportsFileSearch },
        "The file search tool is only supported with Gemini 2.5 and Gemini 3 models.",
    ) { GoogleToolEntry(fileSearch = it) },
    GoogleBuiltInTool(GoogleTools.codeExecution, { it.supportsGemini2Tools }, NEEDS_GEMINI_2) {
        GoogleToolEntry(codeExecution = NoConfiguration)
    },
    GoogleBuiltInTool(GoogleTools.vertexRagStore, { it.supportsGemini2Tools }, NEEDS_GEMINI_2) {
        GoogleToolEntry(retrieval = ragStore(it))
    },
).associateBy { it.factory.id }

/** The `tools` array, the `toolConfig` that goes with it, and anything that had to be dropped. */
internal data class PreparedGoogleTools(
    val tools: List<GoogleToolEntry>?,
    val toolConfig: GoogleToolConfig?,
    val warnings: List<Warning>,
)

/**
 * Gemini's `tools` and `toolConfig`.
 *
 * Two things here are not obvious from the wire format. Google's built-in tools are SIBLING KEYS of
 * `functionDeclarations` rather than entries in it, so a request offering both is several array entries;
 * and only Gemini 3 accepts both at once — on an earlier model the pair is silently one or the other, so
 * the mix is warned about rather than sent and half-honoured.
 *
 * Every function schema goes out VERBATIM under `parametersJsonSchema`: Gemini reads JSON Schema there,
 * `$ref`, `additionalProperties` and array bounds included, so nothing is rewritten and nothing is lost on
 * the way. (The OpenAPI rewrite this replaced inlined every reference and dropped `minItems`, and could
 * not express a recursive schema at all.)
 */
@Suppress("CyclomaticComplexMethod")
internal fun prepareGoogleTools(
    tools: List<Tool>?,
    toolChoice: ToolChoice?,
    modelId: String,
    isVertex: Boolean = false,
): PreparedGoogleTools {
    if (tools.isNullOrEmpty()) return PreparedGoogleTools(null, null, emptyList())

    val caps = googleModelCapabilities(modelId)
    val warnings = mutableListOf<Warning>()
    val functionTools = tools.filterIsInstance<Tool.Function>()
    val providerTools = tools.filterIsInstance<Tool.ProviderDefined>()

    if (functionTools.isNotEmpty() && providerTools.isNotEmpty() && !caps.usesGemini3Features) {
        warnings += Warning.Unsupported(feature = "combination of function and provider-defined tools")
    }

    val declarations = functionTools.map { it.toDeclaration() }
    val builtIn = providerTools.mapNotNull { it.toBuiltIn(caps, warnings) }

    if (providerTools.isNotEmpty()) {
        // Gemini 3 takes the two together, and `VALIDATED` is the mode that lets the model choose
        // between a built-in tool and a declared one within a single turn.
        if (declarations.isNotEmpty() && caps.usesGemini3Features && builtIn.isNotEmpty()) {
            return PreparedGoogleTools(
                tools = builtIn + GoogleToolEntry(functionDeclarations = declarations),
                toolConfig = GoogleToolConfig(
                    functionCallingConfig = toolChoice?.toFunctionCallingConfig()
                        ?: GoogleFunctionCallingConfig(mode = "VALIDATED"),
                    // Without this the model runs its server-side tool and reports nothing about it, so
                    // the caller sees an answer with no evidence of the search that produced it.
                    includeServerSideToolInvocations = if (isVertex) null else true,
                ),
                warnings = warnings,
            )
        }
        return PreparedGoogleTools(builtIn.takeIf { it.isNotEmpty() }, null, warnings)
    }

    // `strict` asks the vendor to constrain generation to the schema; `VALIDATED` is how Gemini spells it.
    val strict = functionTools.any { it.strict == true }
    val config = toolChoice?.toFunctionCallingConfig()
        ?.let { if (it.mode == "AUTO" && strict) it.copy(mode = "VALIDATED") else it }
        ?: GoogleFunctionCallingConfig(mode = "VALIDATED").takeIf { strict }

    return PreparedGoogleTools(
        tools = listOf(GoogleToolEntry(functionDeclarations = declarations)),
        toolConfig = config?.let { GoogleToolConfig(functionCallingConfig = it) },
        warnings = warnings,
    )
}

/** One function declaration: the schema as the caller wrote it, and Gemini resolves it. */
private fun Tool.Function.toDeclaration(): GoogleFunctionDeclaration = GoogleFunctionDeclaration(
    name = name,
    description = description ?: "",
    parametersJsonSchema = inputSchema,
)

/**
 * A built-in tool, or a warning naming the model that cannot serve it.
 *
 * The id is the specification's, not the wire key: a caller writes `google.google_search` and Gemini
 * reads `googleSearch`. An id outside [googleBuiltInTools] is refused with a warning rather than sent —
 * Gemini rejects a `tools` entry it does not recognise, and the warning names the tool where the 400
 * would name nothing.
 */
private fun Tool.ProviderDefined.toBuiltIn(
    caps: GoogleModelCapabilities,
    warnings: MutableList<Warning>,
): GoogleToolEntry? {
    val builtIn = googleBuiltInTools[id]
    val details = when {
        builtIn == null -> "This provider does not model that Gemini tool."
        !builtIn.supportedBy(caps) -> builtIn.unsupported
        else -> return builtIn.entry(args)
    }
    warnings += Warning.Unsupported(feature = "provider-defined tool $id", details = details)
    return null
}

private fun ragStore(args: JsonObject): JsonObject = buildJsonObject {
    put(
        "vertex_rag_store",
        buildJsonObject {
            put("rag_resources", buildJsonObject { args["ragCorpus"]?.let { put("rag_corpus", it) } })
            args["topK"]?.let { put("similarity_top_k", it) }
        },
    )
}

/**
 * Gemini's function-calling mode.
 *
 * There is no "this exact tool" mode; `ANY` plus a one-name allow-list is the documented way to pin one.
 */
internal fun ToolChoice.toFunctionCallingConfig(): GoogleFunctionCallingConfig = when (this) {
    ToolChoice.Auto -> GoogleFunctionCallingConfig(mode = "AUTO")
    ToolChoice.None -> GoogleFunctionCallingConfig(mode = "NONE")
    ToolChoice.Required -> GoogleFunctionCallingConfig(mode = "ANY")
    is ToolChoice.Specific -> GoogleFunctionCallingConfig(
        mode = "ANY",
        allowedFunctionNames = listOf(toolName),
    )
}
