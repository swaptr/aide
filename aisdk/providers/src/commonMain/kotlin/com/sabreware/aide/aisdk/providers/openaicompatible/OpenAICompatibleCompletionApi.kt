package com.sabreware.aide.aisdk.providers.openaicompatible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The legacy Completions wire (POST /completions), response side only.
//
// The request is a flat object of optional fields and is built as JSON in
// OpenAICompatibleCompletionLanguageModel, where the vendor's own options are spread beside it — the
// same shape the chat model uses, for the same reason: a data class with a field per vendor knob is the
// per-vendor-class design this package exists to avoid.
//
// One type serves both the single JSON document and the stream chunks. The reference keeps two schemas
// that differ only in which fields are nullable, and a chunk here is simply a document whose `choices`
// carry one delta each and whose `usage` is absent until the last frame.

@Serializable
internal data class OpenAICompletionResponse(
    val id: String? = null,
    /** Epoch SECONDS on this wire; the contract wants millis. */
    val created: Long? = null,
    val model: String? = null,
    val choices: List<OpenAICompletionChoice> = emptyList(),
    /**
     * Kept raw rather than decoded: a vendor's `convertUsage` reads counters this wire model has
     * never heard of, and `ignoreUnknownKeys` would have dropped them by the time a typed field saw it.
     */
    val usage: JsonObject? = null,
)

@Serializable
internal data class OpenAICompletionChoice(
    /** A complete answer on the document, one fragment on a chunk. Fragments concatenate. */
    val text: String = "",
    val index: Int? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    /**
     * `{tokens, token_logprobs, top_logprobs}`, carried whole into `providerMetadata`.
     *
     * Not modelled: the reference passes the object through untouched, and re-typing it here would be
     * one more place for a field to be dropped between the wire and the caller who asked for it.
     */
    val logprobs: JsonElement? = null,
)
