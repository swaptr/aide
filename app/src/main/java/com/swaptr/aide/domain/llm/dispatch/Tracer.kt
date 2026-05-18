package com.swaptr.aide.domain.llm.dispatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Tracer @Inject constructor() {

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

    private val _spans = MutableStateFlow<List<Span>>(emptyList())

    val snapshot: StateFlow<List<Span>> = _spans.asStateFlow()

    @Synchronized
    fun record(span: Span) {
        val updated = listOf(span) + _spans.value
        _spans.value = if (updated.size > CAPACITY) updated.take(CAPACITY) else updated
    }

    fun clear() {
        _spans.value = emptyList()
    }

    companion object {
        const val CAPACITY = 200
        const val RESULT_PREVIEW_CHARS = 240
    }
}
