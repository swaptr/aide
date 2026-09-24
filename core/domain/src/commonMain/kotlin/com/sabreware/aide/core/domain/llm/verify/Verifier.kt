package com.sabreware.aide.core.domain.llm.verify

import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
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

/**
 * Run a side effect, then look at the world to see whether it took.
 *
 * The ordering rule that matters: **[op] has already happened by the time [verifier] runs**, so nothing the
 * verifier does may turn a completed side effect into a reported failure. Only the timeout used to be
 * converted; every other throw escaped and discarded the successful envelope with it. The verifiers issue
 * plain content-resolver queries, so an OEM rejecting a projection column — or contacts permission revoked
 * between the write and the poll — made AddContact / CalendarAddEvent / SetAlarm report failure for work
 * that really happened. The model's move on "failed" is to retry, which duplicates it.
 *
 * A verifier that cannot answer yields [VerificationResult.VerificationImpossible]: the envelope keeps its
 * `ok`, and `verified: false` with a symbolic reason says only that we could not confirm it.
 *
 * Suspend: the tool handler that calls this is suspend, so verification runs without blocking a thread.
 */
suspend fun <R> runWithVerify(
    op: () -> JsonObject,
    verifier: Verifier<R>,
    evidenceSerializer: (R) -> JsonElement,
    timeoutMs: Long = 1_500,
): JsonObject {
    val envelope = op()
    if (!isOk(envelope)) return envelope
    val outcome = try {
        withTimeoutOrNull(timeoutMs) { verifier.verify() } ?: VerificationResult.NotVerified("timeout")
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        AideLog.w(TAG, "verifier threw after a successful op — reporting unverified, not failed", t)
        VerificationResult.VerificationImpossible(VERIFIER_ERROR)
    }
    return augment(envelope, outcome, evidenceSerializer)
}

private const val TAG = "Verify"

/** Symbolic, like every other reason string here: the model branches on it, so it must not carry a message. */
const val VERIFIER_ERROR = "verifier_error"

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
