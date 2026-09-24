package com.sabreware.aide.app.tools.results

import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class DeviceResult : ToolResult {

    /**
     * Torch change requested. `verified` is true only when the camera HAL's torch
     * callback confirmed the new state; on timeout it stays false with a reason.
     */
    data class Torch(val on: Boolean, val verified: Boolean, val reason: String? = null) : DeviceResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("flashlight_on", JsonPrimitive(on))
            put("verified", JsonPrimitive(verified))
            if (reason != null) put("reason", JsonPrimitive(reason))
        }
    }

    data class Launched(val action: String) : DeviceResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("action", JsonPrimitive(action))
        }
    }

    data class Err(val code: String, val message: String) : DeviceResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}
