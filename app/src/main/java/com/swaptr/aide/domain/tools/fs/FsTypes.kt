package com.swaptr.aide.domain.tools.fs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class RegisteredRoot(
    val key: String,
    val displayName: String,
    val absolutePath: String,
)

data class FsEntry(
    val name: String,
    val relPath: String,
    val isDir: Boolean,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val lastModifiedMs: Long? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("name", name)
        put("relPath", relPath)
        put("isDir", isDir)
        if (sizeBytes != null) put("sizeBytes", sizeBytes)
        if (mimeType != null) put("mimeType", mimeType)
        if (lastModifiedMs != null) put("lastModifiedMs", lastModifiedMs)
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("relPath", JsonPrimitive(relPath))
        put("isDir", JsonPrimitive(isDir))
        if (sizeBytes != null) put("sizeBytes", JsonPrimitive(sizeBytes))
        if (mimeType != null) put("mimeType", JsonPrimitive(mimeType))
        if (lastModifiedMs != null) put("lastModifiedMs", JsonPrimitive(lastModifiedMs))
    }
}

sealed interface ReadTextResult {
    data class Text(val entry: FsEntry, val text: String, val truncated: Boolean) : ReadTextResult
    data class Binary(val entry: FsEntry, val reason: String) : ReadTextResult
    data object NotFound : ReadTextResult
}

object FsErrorCode {
    const val UNKNOWN_ROOT = "UNKNOWN_ROOT"
    const val PATH_ESCAPE = "PATH_ESCAPE"
    const val NOT_FOUND = "NOT_FOUND"
    const val NOT_A_DIR = "NOT_A_DIR"
    const val IS_A_DIR = "IS_A_DIR"
    const val BINARY_FILE = "BINARY_FILE"
    const val TOO_LARGE = "TOO_LARGE"
    const val EXISTS = "EXISTS"
    const val IO_ERROR = "IO_ERROR"
    const val BAD_ARGS = "BAD_ARGS"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val CONFIRM_TIMEOUT = "CONFIRM_TIMEOUT"
    // Distinct from a user-typed "no": session torn down via Stop/clear.
    const val CANCELLED_BY_USER = "CANCELLED_BY_USER"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val BACKEND_UNAVAILABLE = "BACKEND_UNAVAILABLE"
}

class FsException(val errorCode: String, message: String) : RuntimeException(message)
