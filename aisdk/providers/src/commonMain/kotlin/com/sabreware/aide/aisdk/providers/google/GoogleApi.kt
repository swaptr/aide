package com.sabreware.aide.aisdk.providers.google

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.sabreware.aide.aisdk.util.ProviderJson
import kotlinx.serialization.json.JsonObject

// Gemini generateContent wire types (POST :streamGenerateContent?alt=sse).
//
// The classic `contents`/`parts` surface, which is what every current integration speaks. Google's newer
// Interactions API (`steps`, stateful mode) is a separate surface, and CHAT over it is deferred — see
// TODO.md. Transcription already posts there (`GoogleTranscriptionModel`), because that is the only
// wire Google offers for it; the deferral is about the stateful chat surface, not the endpoint.
//
// Request models declare no Kotlin defaults for required fields: ProviderJson sets encodeDefaults = false.
//
// Several request fields are typed as raw JSON rather than as Kotlin enums — safety settings, the image
// config, the retrieval config. They come from the caller's `providerOptions` and go straight to Google;
// re-declaring Google's enumerations here would mean a new aspect ratio or harm category is rejected by
// OUR parser, on a field this layer has no opinion about.

@Serializable
internal data class GoogleRequest(
    val contents: List<GoogleContent>,
    @SerialName("systemInstruction") val systemInstruction: GoogleContent? = null,
    val tools: List<GoogleToolEntry>? = null,
    @SerialName("toolConfig") val toolConfig: GoogleToolConfig? = null,
    @SerialName("generationConfig") val generationConfig: GoogleGenerationConfig? = null,
    @SerialName("safetySettings") val safetySettings: List<JsonObject>? = null,
    /** `cachedContents/{id}` — context Google already holds, billed at the cache rate. */
    @SerialName("cachedContent") val cachedContent: String? = null,
    /** Billing labels. Vertex only; the Gemini API ignores them. */
    val labels: Map<String, String>? = null,
    @SerialName("serviceTier") val serviceTier: String? = null,
)

@Serializable
internal data class GoogleContent(
    /** `user` or `model`. Absent on systemInstruction. */
    val role: String? = null,
    val parts: List<GooglePart>,
)

/**
 * A content part.
 *
 * `thought` marks a reasoning part, and [thoughtSignature] is the encrypted continuity token. The
 * signature can ride a thought part, a text part, an `inlineData` part OR a functionCall part depending
 * on the surface and model, so it is modelled at the part level and carried opaquely either way — which
 * also means a future placement needs no change here.
 *
 * One flat type rather than a sealed union because Gemini's own schema is a union whose arms overlap:
 * `executableCode`, `codeExecutionResult` and `text` share an arm, and a part carrying an empty `text`
 * plus a signature is a real wire shape that a stricter model would reject.
 */
@Serializable
internal data class GooglePart(
    val text: String? = null,
    val thought: Boolean? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("inlineData") val inlineData: GoogleInlineData? = null,
    @SerialName("fileData") val fileData: GoogleFileData? = null,
    @SerialName("functionCall") val functionCall: GoogleFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GoogleFunctionResponse? = null,
    @SerialName("executableCode") val executableCode: GoogleExecutableCode? = null,
    @SerialName("codeExecutionResult") val codeExecutionResult: GoogleCodeExecutionResult? = null,
)

@Serializable
internal data class GoogleInlineData(
    @SerialName("mimeType") val mimeType: String,
    /** Base64. */
    val data: String,
)

@Serializable
internal data class GoogleFileData(
    @SerialName("mimeType") val mimeType: String,
    @SerialName("fileUri") val fileUri: String,
)

@Serializable
internal data class GoogleFunctionCall(
    val name: String,
    /** Structured, unlike OpenAI's stringified arguments. */
    val args: JsonObject? = null,
    val id: String? = null,
)

/**
 * A tool result going back to the model.
 *
 * [response] is `{name, content}` rather than a bare payload because that is the shape Google documents
 * and the shape its models are trained on; [parts] carries anything that is not text — an image a tool
 * returned — which pre-Gemini-3 models reject and current ones require for multimodal results.
 */
@Serializable
internal data class GoogleFunctionResponse(
    val name: String,
    val response: JsonObject,
    val id: String? = null,
    val parts: List<GoogleFunctionResponsePart>? = null,
)

@Serializable
internal data class GoogleFunctionResponsePart(
    @SerialName("inlineData") val inlineData: GoogleInlineData,
)

/** Python the model wrote for the code-execution tool to run. */
@Serializable
internal data class GoogleExecutableCode(
    val language: String? = null,
    val code: String,
)

@Serializable
internal data class GoogleCodeExecutionResult(
    val outcome: String? = null,
    val output: String? = null,
)

/**
 * One entry of the `tools` array.
 *
 * Google's built-in tools are sibling keys of `functionDeclarations` rather than entries in it, and each
 * one is its own object — so a request may carry several [GoogleToolEntry] entries. (Named for the wire,
 * not the tool: [GoogleTools] is the caller-facing factory surface.)
 */
@Serializable
internal data class GoogleToolEntry(
    @SerialName("functionDeclarations") val functionDeclarations: List<GoogleFunctionDeclaration>? = null,
    @SerialName("googleSearch") val googleSearch: JsonObject? = null,
    @SerialName("googleMaps") val googleMaps: JsonObject? = null,
    @SerialName("enterpriseWebSearch") val enterpriseWebSearch: JsonObject? = null,
    @SerialName("urlContext") val urlContext: JsonObject? = null,
    @SerialName("codeExecution") val codeExecution: JsonObject? = null,
    @SerialName("fileSearch") val fileSearch: JsonObject? = null,
    /** Vertex RAG stores; the only built-in whose payload this layer reshapes rather than passes on. */
    val retrieval: JsonObject? = null,
)

@Serializable
internal data class GoogleFunctionDeclaration(
    val name: String,
    val description: String? = null,
    /**
     * The caller's JSON Schema, verbatim.
     *
     * Gemini resolves `$ref` and `$defs` here itself and takes `additionalProperties`, `const` and the
     * array bounds a generated schema carries — every keyword the OpenAPI-dialect `parameters` field
     * refused, which is why this port used to rewrite each schema and lose `minItems` on the way.
     */
    @SerialName("parametersJsonSchema") val parametersJsonSchema: JsonObject,
)

@Serializable
internal data class GoogleToolConfig(
    @SerialName("functionCallingConfig") val functionCallingConfig: GoogleFunctionCallingConfig? = null,
    @SerialName("retrievalConfig") val retrievalConfig: JsonObject? = null,
    /**
     * Whether the model reports the built-in tools it ran.
     *
     * Off by default on the Gemini API, which means a grounded answer arrives with no record of the
     * search that grounded it. Vertex rejects the field outright.
     */
    @SerialName("includeServerSideToolInvocations")
    val includeServerSideToolInvocations: Boolean? = null,
)

@Serializable
internal data class GoogleFunctionCallingConfig(
    /** `AUTO` | `ANY` | `NONE` | `VALIDATED`. */
    val mode: String,
    @SerialName("allowedFunctionNames") val allowedFunctionNames: List<String>? = null,
)

@Serializable
internal data class GoogleGenerationConfig(
    val temperature: Double? = null,
    @SerialName("topP") val topP: Double? = null,
    @SerialName("topK") val topK: Int? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    @SerialName("frequencyPenalty") val frequencyPenalty: Double? = null,
    @SerialName("presencePenalty") val presencePenalty: Double? = null,
    @SerialName("stopSequences") val stopSequences: List<String>? = null,
    @SerialName("responseMimeType") val responseMimeType: String? = null,
    /** JSON Schema, with `const` respelled — see `sanitizeResponseJsonSchema`. */
    @SerialName("responseJsonSchema") val responseJsonSchema: JsonObject? = null,
    @SerialName("responseModalities") val responseModalities: List<String>? = null,
    @SerialName("thinkingConfig") val thinkingConfig: GoogleThinkingConfig? = null,
    @SerialName("mediaResolution") val mediaResolution: String? = null,
    @SerialName("imageConfig") val imageConfig: JsonObject? = null,
    @SerialName("audioTimestamp") val audioTimestamp: Boolean? = null,
    val seed: Int? = null,
)

/**
 * Thinking configuration.
 *
 * `includeThoughts` is what makes the trace visible at all — Gemini returns no thought summaries by
 * default, so a client that omits it sees signatures with no readable reasoning. Depth is set by
 * [thinkingLevel] on current models and by [thinkingBudget] on the ones that predate it.
 */
@Serializable
internal data class GoogleThinkingConfig(
    @SerialName("includeThoughts") val includeThoughts: Boolean? = null,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    @SerialName("thinkingBudget") val thinkingBudget: Int? = null,
)

// ---- Response -------------------------------------------------------------------------------------

@Serializable
internal data class GoogleResponseChunk(
    val candidates: List<GoogleCandidate> = emptyList(),
    @SerialName("usageMetadata") val usageMetadata: GoogleUsage? = null,
    @SerialName("modelVersion") val modelVersion: String? = null,
    @SerialName("responseId") val responseId: String? = null,
    @SerialName("promptFeedback") val promptFeedback: GooglePromptFeedback? = null,
)

@Serializable
internal data class GoogleCandidate(
    val content: GoogleContent? = null,
    @SerialName("finishReason") val finishReason: String? = null,
    @SerialName("finishMessage") val finishMessage: String? = null,
    @SerialName("safetyRatings") val safetyRatings: List<JsonObject>? = null,
    /**
     * Held RAW, and decoded into [GoogleGroundingMetadata] only for the chunks — see [groundingChunks].
     *
     * Only the chunks become content; everything else in here (the search queries, the search-entry-point
     * widget Google requires publishers to render, the per-segment support scores) rides to
     * `providerMetadata` whole. A typed model would have to name every field to keep it, and would drop
     * the one Google adds next month.
     */
    @SerialName("groundingMetadata") val groundingMetadata: JsonObject? = null,
    @SerialName("urlContextMetadata") val urlContextMetadata: JsonObject? = null,
    val index: Int? = null,
)

/**
 * The grounding chunks, or none when the metadata does not decode.
 *
 * Lenient on purpose: grounding is decoration on an answer that has already arrived, and a chunk shape
 * this port has not seen should cost the citation, not the generation.
 */
internal fun GoogleCandidate.groundingChunks(): List<GoogleGroundingChunk> = groundingMetadata
    ?.let { runCatching { ProviderJson.decodeFromJsonElement(GoogleGroundingMetadata.serializer(), it) }.getOrNull() }
    ?.groundingChunks
    .orEmpty()

@Serializable
internal data class GooglePromptFeedback(
    @SerialName("blockReason") val blockReason: String? = null,
    @SerialName("safetyRatings") val safetyRatings: List<JsonObject>? = null,
)

/**
 * The typed half of a candidate's grounding metadata: the chunks, which become one `Content.Source` each.
 *
 * Decoded from [GoogleCandidate.groundingMetadata] on demand rather than at chunk-decode time, so the raw
 * object stays available for `providerMetadata`.
 */
@Serializable
internal data class GoogleGroundingMetadata(
    @SerialName("groundingChunks") val groundingChunks: List<GoogleGroundingChunk>? = null,
)

@Serializable
internal data class GoogleGroundingChunk(
    val web: GoogleWebChunk? = null,
    val image: GoogleImageChunk? = null,
    @SerialName("retrievedContext") val retrievedContext: GoogleRetrievedContext? = null,
    val maps: GoogleMapsChunk? = null,
)

@Serializable
internal data class GoogleWebChunk(val uri: String, val title: String? = null)

@Serializable
internal data class GoogleImageChunk(
    /** Google requires attribution to the page the image was found on, not to the image itself. */
    @SerialName("sourceUri") val sourceUri: String,
    @SerialName("imageUri") val imageUri: String? = null,
    val title: String? = null,
)

@Serializable
internal data class GoogleRetrievedContext(
    val uri: String? = null,
    val title: String? = null,
    @SerialName("fileSearchStore") val fileSearchStore: String? = null,
)

@Serializable
internal data class GoogleMapsChunk(val uri: String? = null, val title: String? = null)

@Serializable
internal data class GoogleUsage(
    @SerialName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int? = null,
    @SerialName("thoughtsTokenCount") val thoughtsTokenCount: Int? = null,
    @SerialName("cachedContentTokenCount") val cachedContentTokenCount: Int? = null,
    @SerialName("totalTokenCount") val totalTokenCount: Int? = null,
    @SerialName("trafficType") val trafficType: String? = null,
    @SerialName("serviceTier") val serviceTier: String? = null,
)
