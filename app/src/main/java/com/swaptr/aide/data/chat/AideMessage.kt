package com.swaptr.aide.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// See: https://ai.google.dev/api/generate-content
// See: https://ai.google.dev/gemma/docs/core/prompt-structure
@Serializable
enum class AideRole {
    @SerialName("user") User,
    @SerialName("model") Model,
    @SerialName("system") System,
    @SerialName("tool") Tool;

    fun wireString(): String = when (this) {
        User -> "user"
        Model -> "model"
        System -> "system"
        Tool -> "tool"
    }

    companion object {
        fun fromWireString(value: String): AideRole = when (value.lowercase()) {
            "user" -> User
            "model", "assistant" -> Model
            "system" -> System
            "tool" -> Tool
            else -> User
        }
    }
}

@Serializable
sealed class AidePart {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AidePart()

    @Serializable
    @SerialName("image_bytes")
    data class ImageBytes(val bytesBase64: String) : AidePart()

    @Serializable
    @SerialName("image_file")
    data class ImageFile(val path: String) : AidePart()

    @Serializable
    @SerialName("audio_bytes")
    data class AudioBytes(val bytesBase64: String) : AidePart()

    @Serializable
    @SerialName("audio_file")
    data class AudioFile(val path: String) : AidePart()

    @Serializable
    @SerialName("tool_response")
    data class ToolResponse(
        val name: String,
        val json: String,
        val callId: String? = null,
        val error: String? = null,
    ) : AidePart()

    // UI-only chip record; wire adapters strip ToolCall parts before replay.
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val callId: String,
        val name: String,
        val argsJson: String,
    ) : AidePart()

    // UI-only reasoning trace; stripped before replay so the model doesn't see its own scratchpad.
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val text: String,
        val durationMs: Long,
    ) : AidePart()
}

@Serializable
data class AideMessage(
    val role: AideRole,
    val parts: List<AidePart>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val textContent: String
        get() = parts.filterIsInstance<AidePart.Text>().joinToString("") { it.text }

    companion object {
        fun user(text: String) = AideMessage(AideRole.User, listOf(AidePart.Text(text)))
        fun user(text: String, imagePath: String?): AideMessage {
            // ImageFile before text matches Gemma's chat-template ordering for multimodal turns.
            val parts = buildList {
                if (imagePath != null) add(AidePart.ImageFile(imagePath))
                add(AidePart.Text(text))
            }
            return AideMessage(AideRole.User, parts)
        }
        fun model(text: String) = AideMessage(AideRole.Model, listOf(AidePart.Text(text)))
        fun system(text: String) = AideMessage(AideRole.System, listOf(AidePart.Text(text)))
    }
}

// ignoreUnknownKeys: forward-compat for older builds reading rows with newer AidePart variants.
internal val MessageJson: Json = Json { ignoreUnknownKeys = true }

fun MessageEntity.toAideMessage(): AideMessage {
    val parts = partsJson?.let { json ->
        runCatching { MessageJson.decodeFromString<List<AidePart>>(json) }.getOrNull()
    } ?: listOf(AidePart.Text(text))
    return AideMessage(
        role = AideRole.fromWireString(role),
        parts = parts,
        createdAt = createdAt,
    )
}
