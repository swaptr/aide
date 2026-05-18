package com.swaptr.aide.domain.tools.results

import com.swaptr.aide.domain.llm.ToolEnvelope
import com.swaptr.aide.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class PhoneResult : ToolResult {

    data class Launched(val action: String) : PhoneResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("action", JsonPrimitive(action))
        }
    }

    data class Picked(val displayName: String, val number: String) : PhoneResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("name", JsonPrimitive(displayName))
            put("number", JsonPrimitive(number))
        }
    }

    data class Err(val code: String, val message: String) : PhoneResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}
