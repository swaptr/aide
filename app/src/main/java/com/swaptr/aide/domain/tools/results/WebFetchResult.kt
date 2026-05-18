package com.swaptr.aide.domain.tools.results

import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class WebFetchResult : ToolResult {

    data class Page(
        val url: String,
        val text: String,
        val truncated: Boolean = false,
        val note: String? = null,
    ) : WebFetchResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("url", JsonPrimitive(url))
            put("text", JsonPrimitive(text))
            put("truncated", JsonPrimitive(truncated))
            if (note != null) put("note", JsonPrimitive(note))
        }
    }

    data class Empty(val url: String, val note: String) : WebFetchResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("url", JsonPrimitive(url))
            put("text", JsonPrimitive(""))
            put("note", JsonPrimitive(note))
        }
    }

    data class Err(val code: String, val message: String, val url: String? = null) : WebFetchResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message) {
            if (url != null) put("url", JsonPrimitive(url))
        }
    }
}
