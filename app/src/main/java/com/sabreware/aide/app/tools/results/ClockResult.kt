package com.sabreware.aide.app.tools.results

import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class ClockResult : ToolResult {

    data class Launched(val action: String) : ClockResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("action", JsonPrimitive(action))
        }
    }

    data class Err(val code: String, val message: String) : ClockResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}
