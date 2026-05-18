package com.swaptr.aide.domain.llm.verify

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Reason strings are symbolic (no_match, timeout, permission_denied) — the LLM branches on them.
interface Verifier<R> {
    suspend fun verify(): VerificationResult<R>
}

sealed class VerificationResult<out R> {
    data class Verified<R>(val evidence: R) : VerificationResult<R>()
    data class NotVerified(val reason: String) : VerificationResult<Nothing>()
    data class VerificationImpossible(val reason: String) : VerificationResult<Nothing>()
}

// runBlocking because the call site is the sync tool-handler thread.
fun <R> runWithVerify(
    op: () -> JsonObject,
    verifier: Verifier<R>,
    evidenceSerializer: (R) -> JsonElement,
    timeoutMs: Long = 1_500,
): JsonObject {
    val envelope = op()
    if (!isOk(envelope)) return envelope
    val outcome = runBlocking {
        withTimeoutOrNull(timeoutMs) { verifier.verify() }
            ?: VerificationResult.NotVerified("timeout")
    }
    return augment(envelope, outcome, evidenceSerializer)
}

private fun <R> augment(
    envelope: JsonObject,
    result: VerificationResult<R>,
    evidenceSerializer: (R) -> JsonElement,
): JsonObject = buildJsonObject {
    envelope.forEach { (k, v) ->
        // Drop stale verified/reason/evidence from stub Launched envelopes — we set them below.
        if (k != "verified" && k != "reason" && k != "evidence") put(k, v)
    }
    when (result) {
        is VerificationResult.Verified -> {
            put("verified", JsonPrimitive(true))
            put("evidence", evidenceSerializer(result.evidence))
        }
        is VerificationResult.NotVerified -> {
            put("verified", JsonPrimitive(false))
            put("reason", JsonPrimitive(result.reason))
        }
        is VerificationResult.VerificationImpossible -> {
            put("verified", JsonPrimitive(false))
            put("reason", JsonPrimitive(result.reason))
        }
    }
}

private fun isOk(envelope: JsonObject): Boolean =
    (envelope["ok"] as? JsonPrimitive)?.content == "true"
