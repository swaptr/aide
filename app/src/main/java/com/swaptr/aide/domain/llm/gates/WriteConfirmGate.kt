package com.swaptr.aide.domain.llm.gates

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// Bridges sync tool-handler thread to async Allow/Cancel dialog.
// Three denial reasons → distinct dispatcher error codes.
@Singleton
class WriteConfirmGate @Inject constructor() {

    data class KeyValue(val key: String, val value: String)

    enum class Severity { INFO, WARN, DANGER }

    data class Prompt(
        val opId: String,
        val toolName: String,
        val summary: String,
        val details: List<KeyValue> = emptyList(),
        val severity: Severity = Severity.WARN,
    )

    sealed class Result {
        data object Approved : Result()
        data class Denied(val reason: Reason) : Result()
        enum class Reason {
            USER_REJECTED,
            TIMEOUT,
            CANCELLED_BY_STOP,
        }
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result>>()
    private val emitter = AtomicReference<(Prompt) -> Unit>({ })

    fun bindEmitter(sink: (Prompt) -> Unit) {
        emitter.set(sink)
    }

    fun unbindEmitter() {
        emitter.set { }
    }

    fun await(prompt: Prompt, timeoutMs: Long = 60_000L): Result = runBlocking {
        val deferred = CompletableDeferred<Result>()
        pending[prompt.opId] = deferred
        emitter.get().invoke(prompt)
        try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: Result.Denied(Result.Reason.TIMEOUT)
        } finally {
            pending.remove(prompt.opId)
        }
    }

    fun resolve(opId: String, approved: Boolean) {
        val outcome = if (approved) Result.Approved
        else Result.Denied(Result.Reason.USER_REJECTED)
        pending.remove(opId)?.complete(outcome)
    }

    fun cancelAll() {
        val snapshot = pending.values.toList()
        pending.clear()
        snapshot.forEach { it.complete(Result.Denied(Result.Reason.CANCELLED_BY_STOP)) }
    }
}
