package com.sabreware.aide.core.domain.llm.dispatch

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Tracer {

    data class Span(
        val ts: Long,
        val tool: String,
        val argsHash: String,
        val resultPreview: String,
        val ok: Boolean,
        val errorCode: String?,
        val durationMs: Long,
        val surface: String,
        val modelId: String,
        val idempotentHit: Boolean,
        val rateLimited: Boolean,
        val verified: Boolean?,
    )

    private val lock = SynchronizedObject()
    private val _spans = MutableStateFlow<List<Span>>(emptyList())

    val snapshot: StateFlow<List<Span>> = _spans.asStateFlow()

    fun record(span: Span) {
        synchronized(lock) {
            val updated = listOf(span) + _spans.value
            _spans.value = if (updated.size > CAPACITY) updated.take(CAPACITY) else updated
        }
    }

    fun clear() {
        _spans.value = emptyList()
    }

    companion object {
        const val CAPACITY = 200
        const val RESULT_PREVIEW_CHARS = 240
    }
}
