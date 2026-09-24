package com.sabreware.aide.core.domain.tools.results

import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject

sealed class TimeResult : ToolResult {

    data class Now(val iso: String, val human: String, val zone: String) : TimeResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("iso", kotlinx.serialization.json.JsonPrimitive(iso))
            put("human", kotlinx.serialization.json.JsonPrimitive(human))
            put("zone", kotlinx.serialization.json.JsonPrimitive(zone))
        }
    }
}
