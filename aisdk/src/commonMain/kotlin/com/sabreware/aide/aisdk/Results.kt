package com.sabreware.aide.aisdk

import kotlinx.coroutines.flow.Flow

/**
 * Response identity, as far as the provider chose to reveal it.
 *
 * [timestamp] is epoch milliseconds rather than a date type: this module deliberately depends on nothing
 * but kotlinx-serialization and coroutines, and a `Long` needs no dependency to be correct on every
 * target. A consumer that wants a calendar type converts at its own edge.
 */
public data class ResponseMetadata(
    /** The vendor's response id, the handle a support ticket asks for. */
    val id: String? = null,
    /** When the response was created, epoch milliseconds — see the class doc for why not a date type. */
    val timestamp: Long? = null,
    /** The model the vendor says actually served the call — not always the one that was asked for. */
    val modelId: String? = null,
)

/** The request as sent, kept for telemetry and for the bug report that follows a wire-level surprise. */
public data class RequestInfo(val body: String? = null)

/** The response as received, minus the body a stream never has in one piece. */
public data class ResponseInfo(
    val metadata: ResponseMetadata = ResponseMetadata(),
    val headers: Map<String, String>? = null,
    val body: String? = null,
)

/** The result of [LanguageModel.doGenerate]. */
public data class GenerateResult(
    /** What the model produced, in order. Order is part of the contract — see [Content]. */
    val content: List<Content>,
    /** Why generation stopped — normalized and verbatim; see [FinishReason]. */
    val finishReason: FinishReason,
    /** What the call cost — see [Usage] for why every count is nullable. */
    val usage: Usage,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** The request as sent — the first thing a wire-level bug report needs. */
    val request: RequestInfo? = null,
    /** The response as received — see [ResponseInfo]. */
    val response: ResponseInfo? = null,
)

/**
 * The result of [LanguageModel.doStream].
 *
 * [stream] is cold: collecting it issues the request, and cancelling the collecting coroutine cancels
 * the request. That is why the specification has no abort parameter.
 */
public data class StreamResult(
    /** One [StreamPart] per provider event, delimited blocks interleaved — see [StreamPart]. */
    val stream: Flow<StreamPart>,
    /** The request as sent — the first thing a wire-level bug report needs. */
    val request: RequestInfo? = null,
    /** What is known of the response when the stream opens: headers, never a whole body. */
    val response: ResponseInfo? = null,
)
