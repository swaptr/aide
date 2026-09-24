package com.sabreware.aide.core.domain.tools.results

import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class CalculatorResult : ToolResult {

    data class Ok(val value: Double, val formatted: String) : CalculatorResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("result", JsonPrimitive(value))
            put("formatted", JsonPrimitive(formatted))
        }
    }

    data class Err(val code: String, val message: String) : CalculatorResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}
