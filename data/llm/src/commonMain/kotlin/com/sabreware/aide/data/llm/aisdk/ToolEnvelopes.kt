package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.core.domain.llm.ToolEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * AIDE's tool envelope in the spec's [ToolOutput] vocabulary, and back.
 *
 * Three places read a tool's outcome — the executor that answers the model, the
 * [com.sabreware.aide.core.domain.llm.ChatStreamEvent.ToolCallCompleted] the use case persists, and the
 * session's own history for the next turn — and this file is the one translation all three share, so
 * they cannot disagree about what a failure looks like.
 *
 * The envelope is AIDE's `{ok, errorCode, error, …}` object ([ToolEnvelope]). A failed envelope becomes
 * [ToolOutput.ErrorJson] rather than a flagged [ToolOutput.Json]: the spec sends an error result FLAGGED
 * where the vendor supports it, and a model told "this failed" in the wire's own terms retries something
 * else instead of parsing prose for the word "error".
 */
internal fun JsonObject.toToolOutput(): ToolOutput =
    if (isOk()) ToolOutput.Json(this) else ToolOutput.ErrorJson(this)

/** What a [ToolOutput] persists as: the envelope text, and the error code when it is a failure. */
internal data class EnvelopeJson(val json: String, val error: String?)

/**
 * The persisted form of a result the runtime produced.
 *
 * Only [ToolOutput.Json] and [ToolOutput.ErrorJson] come from AIDE's own dispatcher; the other arms are
 * the runtime's — an invalid call it refused to run, a denial, a plain-text tool — and each is folded
 * into an envelope so the transcript holds one shape whoever produced the result.
 */
internal fun ToolOutput.toEnvelopeJson(): EnvelopeJson = when (this) {
    is ToolOutput.Json -> EnvelopeJson(value.toString(), value.envelopeErrorCode())
    is ToolOutput.ErrorJson -> EnvelopeJson(value.toString(), value.envelopeErrorCode() ?: GENERIC_ERROR)
    // The runtime's own refusal: an unknown tool name, unparseable input, a missing required property.
    is ToolOutput.ErrorText -> ToolEnvelope.failure(INVALID_CALL, value).let { EnvelopeJson(it.toString(), INVALID_CALL) }
    is ToolOutput.ExecutionDenied ->
        ToolEnvelope.failure(USER_CANCELLED, reason ?: "the tool call was not approved")
            .let { EnvelopeJson(it.toString(), USER_CANCELLED) }
    is ToolOutput.Text -> EnvelopeJson(ToolEnvelope.success { put("text", JsonPrimitive(value)) }.toString(), null)
    is ToolOutput.Multipart -> {
        val text = value.filterIsInstance<ToolOutput.Multipart.Item.Text>().joinToString("\n") { it.text }
        EnvelopeJson(ToolEnvelope.success { put("text", JsonPrimitive(text)) }.toString(), null)
    }
}

/**
 * A tool call's arguments as the object the dispatcher takes.
 *
 * Blank and unparseable both read as `{}`: the runtime already normalizes a valid call's input to an
 * object string, and an invalid one is never executed — so what remains is the argument the event and
 * the transcript show for it, where an empty object beats a crash on a half-streamed string.
 */
internal fun String.parseArgsOrEmpty(): JsonObject =
    runCatching { ARG_JSON.parseToJsonElement(this).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun JsonObject.isOk(): Boolean = (this["ok"] as? JsonPrimitive)?.content == "true"

/** The envelope's `errorCode` when it reports a failure; null for a success or a non-envelope value. */
private fun kotlinx.serialization.json.JsonElement.envelopeErrorCode(): String? {
    val envelope = this as? JsonObject ?: return null
    if (envelope.isOk()) return null
    return (envelope["errorCode"] as? JsonPrimitive)?.content
}

private const val INVALID_CALL = "INVALID_CALL"
private const val USER_CANCELLED = "USER_CANCELLED"
private const val GENERIC_ERROR = "ERROR"

private val ARG_JSON = Json { ignoreUnknownKeys = true }
