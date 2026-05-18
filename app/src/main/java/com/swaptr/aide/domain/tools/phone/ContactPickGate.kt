package com.swaptr.aide.domain.tools.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactPickGate @Inject constructor() {

    data class ContactPickResult(
        val displayName: String?,
        val number: String,
    )

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ContactPickResult?>>()
    private val emitter = AtomicReference<(String) -> Unit>({ })

    fun bindEmitter(sink: (String) -> Unit) {
        emitter.set(sink)
    }

    fun unbindEmitter() {
        emitter.set { }
    }

    fun await(opId: String, timeoutMs: Long = 60_000L): ContactPickResult? = runBlocking {
        val deferred = CompletableDeferred<ContactPickResult?>()
        pending[opId] = deferred
        emitter.get().invoke(opId)
        try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(opId)
        }
    }

    fun resolve(opId: String, result: ContactPickResult?) {
        pending.remove(opId)?.complete(result)
    }

    fun cancelAll() {
        val snapshot = pending.values.toList()
        pending.clear()
        snapshot.forEach { it.complete(null) }
    }
}
