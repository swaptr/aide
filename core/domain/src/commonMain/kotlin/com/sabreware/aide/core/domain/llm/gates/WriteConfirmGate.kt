package com.sabreware.aide.core.domain.llm.gates

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

// Bridges sync tool-handler thread to async Allow/Cancel dialog.
// Three denial reasons → distinct dispatcher error codes.
class WriteConfirmGate {

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
        /** [remember] = "don't ask again": persist as always-allow (Approved) or never-allow (Denied). */
        data class Approved(val remember: Boolean = false) : Result()
        data class Denied(val reason: Reason, val remember: Boolean = false) : Result()
        enum class Reason {
            USER_REJECTED,
            TIMEOUT,
            CANCELLED_BY_STOP,

            /** No surface is currently able to show a dialog, so nobody could have approved this. */
            NO_HOST,
        }
    }

    /** A live emitter registration. Closing it removes **only this one**. */
    fun interface Binding {
        fun unbind()
    }

    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<String, CompletableDeferred<Result>>()

    /**
     * Every host that can currently show a confirm dialog, in bind order.
     *
     * It used to be a single slot with an unconditional `unbindEmitter()`, and the gate, the use case and
     * the dispatcher are all process singletons — so a voice turn would bind, a chat turn would finish and
     * clear the slot, and the voice turn's next tool would emit into a no-op and wait out the full 60s
     * timeout with no dialog ever appearing.
     *
     * Three rules replace that: an unbind removes only the registration it was handed (so finishing one
     * turn cannot silence another); a prompt from a turn goes to the host THAT turn bound (the [ToolTurnId]
     * on the awaiting coroutine matches [Entry.ownerId], so a background turn's prompt can no longer land
     * on the foreground surface's host); and a caller with no turn identity falls back to the most recently
     * bound host.
     */
    private class Entry(val token: Any, val ownerId: String?, val sink: (Prompt) -> Unit)

    private val emitters = mutableListOf<Entry>()

    fun bindEmitter(ownerId: String? = null, sink: (Prompt) -> Unit): Binding {
        val token = Any()
        synchronized(lock) { emitters += Entry(token, ownerId, sink) }
        return Binding {
            synchronized(lock) { emitters.removeAll { it.token === token } }
        }
    }

    suspend fun await(prompt: Prompt, timeoutMs: Long = 60_000L): Result {
        val ownerId = currentCoroutineContext()[ToolTurnId]?.id
        val sink = synchronized(lock) {
            // A turn that declared identity gets ITS host or nothing: if its binding is gone, its collector
            // is gone, and showing the dialog on another surface would let a user approve a dead turn's op.
            if (ownerId != null) emitters.lastOrNull { it.ownerId == ownerId }?.sink
            else emitters.lastOrNull()?.sink
        }
            // Denying immediately beats suspending for a minute to reach the same answer: nothing is on
            // screen to approve it, and the model gets a code it can act on now.
            ?: return Result.Denied(Result.Reason.NO_HOST)
        val deferred = CompletableDeferred<Result>()
        synchronized(lock) { pending[prompt.opId] = deferred }
        sink.invoke(prompt)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: Result.Denied(Result.Reason.TIMEOUT)
        } finally {
            synchronized(lock) { pending.remove(prompt.opId) }
        }
    }

    fun resolve(opId: String, approved: Boolean, remember: Boolean = false) {
        val outcome = if (approved) Result.Approved(remember)
        else Result.Denied(Result.Reason.USER_REJECTED, remember)
        synchronized(lock) { pending.remove(opId) }?.complete(outcome)
    }

    fun cancelAll() {
        val snapshot = synchronized(lock) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
        snapshot.forEach { it.complete(Result.Denied(Result.Reason.CANCELLED_BY_STOP)) }
    }
}
