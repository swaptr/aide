package com.swaptr.aide.data.chat

import android.util.Base64
import com.swaptr.aide.domain.llm.ollama.OllamaMessage
import com.swaptr.aide.domain.llm.ollama.OllamaToolCall
import com.swaptr.aide.domain.llm.ollama.OllamaToolCallFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

// Audio dropped (Ollama has no audio channel); Ollama's assistant role is `assistant`, not `model`.
fun AideMessage.toOllama(): OllamaMessage {
    val text = parts.filterIsInstance<AidePart.Text>().joinToString("") { it.text }

    val images = mutableListOf<String>()
    for (p in parts) when (p) {
        is AidePart.ImageBytes -> images += p.bytesBase64
        is AidePart.ImageFile -> readFileAsBase64(p.path)?.let { images += it }
        else -> Unit
    }

    val toolResponse = parts.filterIsInstance<AidePart.ToolResponse>().firstOrNull()
    if (toolResponse != null) {
        return OllamaMessage(
            role = "tool",
            content = toolResponse.json,
            toolName = toolResponse.name,
        )
    }

    val toolCalls = parts.filterIsInstance<AidePart.ToolCall>().map { call ->
        OllamaToolCall(
            function = OllamaToolCallFunction(
                name = call.name,
                arguments = call.argsJson.parseToJsonObjectOrEmpty(),
            ),
        )
    }

    return OllamaMessage(
        role = role.toOllamaRole(),
        content = text,
        images = images.takeIf { it.isNotEmpty() },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() },
    )
}

private val OllamaJson = Json { ignoreUnknownKeys = true }

private fun String.parseToJsonObjectOrEmpty(): JsonElement =
    runCatching { OllamaJson.parseToJsonElement(this) as? JsonObject }
        .getOrNull()
        ?: JsonObject(emptyMap())

private fun AideRole.toOllamaRole(): String = when (this) {
    AideRole.User -> "user"
    AideRole.Model -> "assistant"
    AideRole.System -> "system"
    AideRole.Tool -> "tool"
}

private fun readFileAsBase64(path: String): String? = runCatching {
    val bytes = File(path).readBytes()
    Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()
