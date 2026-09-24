package com.sabreware.aide.core.domain.tools.results

import com.sabreware.aide.core.domain.llm.ToolEnvelope
import com.sabreware.aide.core.domain.llm.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class FileSystemResult : ToolResult {

    data class Listing(
        val rootKey: String,
        val relPath: String,
        val entries: List<JsonObject>,
        val truncated: Boolean,
    ) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("relPath", JsonPrimitive(relPath))
            put("entries", JsonArray(entries))
            put("truncated", JsonPrimitive(truncated))
        }
    }

    data class Found(
        val rootKey: String,
        val relPath: String,
        val pattern: String,
        val entries: List<JsonObject>,
        val truncated: Boolean,
    ) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("relPath", JsonPrimitive(relPath))
            put("pattern", JsonPrimitive(pattern))
            put("entries", JsonArray(entries))
            put("truncated", JsonPrimitive(truncated))
        }
    }

    data class Info(val rootKey: String, val entry: JsonObject) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("entry", entry)
        }
    }

    data class TextRead(val entry: JsonObject, val text: String, val truncated: Boolean) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("entry", entry)
            put("text", JsonPrimitive(text))
            put("truncated", JsonPrimitive(truncated))
        }
    }

    data class BinaryRead(val code: String, val message: String, val entry: JsonObject) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message) {
            put("entry", entry)
        }
    }

    data class DirCreated(val rootKey: String, val entry: JsonObject) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("entry", entry)
        }
    }

    data class Moved(val rootKey: String, val entry: JsonObject) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("entry", entry)
        }
    }

    data class Copied(val rootKey: String, val entry: JsonObject) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("entry", entry)
        }
    }

    data class Deleted(
        val rootKey: String,
        val relPath: String,
        val deleted: Boolean,
        val trashed: Boolean,
    ) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.success {
            put("rootKey", JsonPrimitive(rootKey))
            put("relPath", JsonPrimitive(relPath))
            put("deleted", JsonPrimitive(deleted))
            put("trashed", JsonPrimitive(trashed))
        }
    }

    data class Err(val code: String, val message: String) : FileSystemResult() {
        override fun toEnvelope(): JsonObject = ToolEnvelope.failure(code, message)
    }
}
