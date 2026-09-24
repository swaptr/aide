package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.GenerateResult

/**
 * What each [Step] retains beyond the content itself.
 *
 * Every switch defaults to off, matching the reference: a round's request body carries every image and
 * file the prompt holds as base64, and a twenty-round run that keeps twenty copies of the same
 * attachments in its steps is a run that runs out of memory on a phone. The bug report that needs the
 * body turns the switch on for the run that reproduces it.
 *
 * The request MESSAGES the reference also gates have no counterpart here: a [Step] never held them, and
 * [RunEvent.StepStart] carries the options the round sent — its prompt included — to a consumer that
 * wants them as they go by rather than retained.
 */
public data class RunInclude(
    /** Keep [Step.request]'s body. Off, the [com.sabreware.aide.aisdk.RequestInfo] stays with a null body. */
    val requestBody: Boolean = false,
    /** Keep [Step.response]'s body. Headers and metadata are kept either way — they are small. */
    val responseBody: Boolean = false,
    /**
     * Forward the provider's untouched payloads as [com.sabreware.aide.aisdk.StreamPart.Raw]. Sets
     * [com.sabreware.aide.aisdk.CallOptions.includeRawChunks] on every round; a caller that set it on the
     * options directly gets the same result.
     */
    val rawChunks: Boolean = false,
)

/** Drops whatever this configuration does not retain from a round's result. */
internal fun RunInclude.shed(result: GenerateResult): GenerateResult = result.copy(
    request = if (requestBody) result.request else result.request?.copy(body = null),
    response = if (responseBody) result.response else result.response?.copy(body = null),
)
