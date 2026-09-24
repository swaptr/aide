package com.sabreware.aide.core.domain.tools.phone

import com.sabreware.aide.core.domain.llm.gates.ToolTurnId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

class ContactPickGate {

    data class ContactPickResult(
        val displayName: String?,
        val number: String,
    )

    /** A live emitter registration. Closing it removes **only this one**. */
    fun interface Binding {
        fun unbind()
    }

    private val lock = SynchronizedObject()
    private val pending = mutableMapOf<String, CompletableDeferred<ContactPickResult?>>()

    /** Same registry, same routing rules, as [com.sabreware.aide.core.domain.llm.gates.WriteConfirmGate].
     *  This one had the opposite half of the original bug: it was bound per turn and never unbound at all,
     *  so a picker request could still be routed to a screen that had gone away. */
    private class Entry(val token: Any, val ownerId: String?, val sink: (String) -> Unit)

    private val emitters = mutableListOf<Entry>()

    fun bindEmitter(ownerId: String? = null, sink: (String) -> Unit): Binding {
        val token = Any()
        synchronized(lock) { emitters += Entry(token, ownerId, sink) }
        return Binding {
            synchronized(lock) { emitters.removeAll { it.token === token } }
        }
    }

    suspend fun await(opId: String, timeoutMs: Long = 60_000L): ContactPickResult? {
        val ownerId = currentCoroutineContext()[ToolTurnId]?.id
        val sink = synchronized(lock) {
            if (ownerId != null) emitters.lastOrNull { it.ownerId == ownerId }?.sink
            else emitters.lastOrNull()?.sink
        } ?: return null
        val deferred = CompletableDeferred<ContactPickResult?>()
        synchronized(lock) { pending[opId] = deferred }
        sink.invoke(opId)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            synchronized(lock) { pending.remove(opId) }
        }
    }

    fun resolve(opId: String, result: ContactPickResult?) {
        synchronized(lock) { pending.remove(opId) }?.complete(result)
    }

    fun cancelAll() {
        val snapshot = synchronized(lock) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
        snapshot.forEach { it.complete(null) }
    }
}
