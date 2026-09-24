package com.sabreware.aide.core.domain.tools.results

import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

sealed class WebSearchResult : ToolResult {

    data class Hit(val title: String, val snippet: String, val url: String)

    data class Hits(val results: List<Hit>) : WebSearchResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("results", buildJsonArray {
                results.forEach { hit ->
                    add(buildJsonObject {
                        put("title", JsonPrimitive(hit.title))
                        put("snippet", JsonPrimitive(hit.snippet))
                        put("url", JsonPrimitive(hit.url))
                    })
                }
            })
        }
    }

    data object Empty : WebSearchResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("results", buildJsonArray { })
            put("note", JsonPrimitive("no results"))
        }
    }

    data class Err(val code: String, val message: String) : WebSearchResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}
