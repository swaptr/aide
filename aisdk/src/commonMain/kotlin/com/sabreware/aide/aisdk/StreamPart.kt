package com.sabreware.aide.aisdk

import kotlinx.serialization.json.JsonElement

/**
 * One event in a streamed generation.
 *
 * **Blocks are delimited, not inferred.** Text, reasoning and tool input each arrive as a
 * `*Start` / `*Delta`* / `*End` triple sharing an [id]. That id is the correlation key, and it is what
 * makes interleaved blocks — a model that thinks, starts a tool call, thinks again — expressible without
 * a consumer guessing where one block stopped and the next began.
 *
 * The alternative, which AIDE ran on until this port, is to hang a signature off a text event and treat
 * an empty-text event as a block terminator by convention. That works until two blocks interleave, and
 * it has no place to put a payload that arrives after the text it belongs to.
 *
 * Any part may carry [ProviderMetadata]; on [ReasoningEnd] in particular it is where a signature lands,
 * because vendors emit the signed payload when the block closes rather than with its text.
 */
public sealed interface StreamPart {

    /** Opens the stream, carrying anything the provider had to ignore about the call. */
    public data class StreamStart(val warnings: List<Warning> = emptyList()) : StreamPart

    /** Response id / model / timestamp, emitted as soon as the provider reveals it. */
    public data class ResponseMetadataPart(val metadata: ResponseMetadata) : StreamPart

    /** Opens a text block. [id] is the key that joins the deltas and the end to it. */
    public data class TextStart(
        val id: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** Append-only text for the open block sharing [id]. Deltas concatenate; they never restate. */
    public data class TextDelta(
        val id: String,
        val delta: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** Closes the text block sharing [id]. */
    public data class TextEnd(
        val id: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** Opens a reasoning block. [id] is the key that joins the deltas and [ReasoningEnd] to it. */
    public data class ReasoningStart(
        val id: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** Append-only reasoning text for the open block sharing [id]. */
    public data class ReasoningDelta(
        val id: String,
        val delta: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /**
     * Closes a reasoning block.
     *
     * This is where a signed or encrypted payload arrives — Anthropic's `signature_delta`, Gemini's
     * `thoughtSignature`. A provider MUST surface it in [providerMetadata] here; a runtime MUST carry it
     * onto the assembled [Content.Reasoning] and back out in the next request, unmodified.
     */
    public data class ReasoningEnd(
        val id: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /**
     * A tool call begins. [id] correlates the input deltas that follow; it is the tool call id.
     */
    public data class ToolInputStart(
        val id: String,
        val toolName: String,
        val providerExecuted: Boolean = false,
        val dynamic: Boolean = false,
        val title: String? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** A fragment of the tool's JSON input. Fragments concatenate; they are never cumulative. */
    public data class ToolInputDelta(
        val id: String,
        val delta: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** Closes the tool-input block sharing [id] — the tool call id from [ToolInputStart]. */
    public data class ToolInputEnd(
        val id: String,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /** A complete tool call. Providers that never stream partial input emit only this. */
    public data class ToolCallPart(val toolCall: Content.ToolCall) : StreamPart

    /** A result from a provider-executed tool. */
    public data class ToolResultPart(val toolResult: Content.ToolResult) : StreamPart

    /** The provider is asking for approval before executing a tool. */
    public data class ToolApprovalRequestPart(val request: Content.ToolApprovalRequest) : StreamPart

    /** A complete generated file. Files do not stream in fragments. */
    public data class FilePart(val file: Content.File) : StreamPart

    /** A complete file generated during reasoning. */
    public data class ReasoningFilePart(val file: Content.ReasoningFile) : StreamPart

    /** A source the model consulted. */
    public data class SourcePart(val source: Content.Source) : StreamPart

    /** A provider block with no standardized mapping. */
    public data class CustomPart(val custom: Content.Custom) : StreamPart

    /** Terminal part: usage and finish reason. Exactly one per successful stream. */
    public data class Finish(
        val usage: Usage,
        val finishReason: FinishReason,
        val providerMetadata: ProviderMetadata? = null,
    ) : StreamPart

    /**
     * The untouched provider payload, emitted only when [CallOptions.includeRawChunks] is set.
     *
     * A debugging affordance, and the reason a provider never has to choose between mapping a field and
     * making it observable.
     */
    public data class Raw(val value: JsonElement) : StreamPart

    /**
     * An error.
     *
     * Streamed rather than thrown, because a provider can emit several and a stream can carry on past
     * one. A runtime decides whether any given error is terminal; the provider only reports it.
     */
    public data class Error(val error: Throwable) : StreamPart
}
