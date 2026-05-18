package com.swaptr.aide.domain.llm.ollama

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Sources: https://docs.ollama.com plus canonical ollama/docs/api.md.

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true,
    val tools: List<OllamaToolDef>? = null,
    val options: JsonObject? = null,
    val think: JsonElement? = null,
    val format: JsonElement? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable
data class OllamaMessage(
    val role: String,
    val content: String = "",
    val thinking: String = "",
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OllamaToolDef(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function",
    val function: OllamaToolFunction,
)

@Serializable
data class OllamaToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class OllamaToolCall(
    val function: OllamaToolCallFunction,
)

@Serializable
data class OllamaToolCallFunction(
    val name: String,
    val arguments: JsonElement,
)

@Serializable
data class OllamaChatStreamChunk(
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    val error: String? = null,
)

@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaTagEntry> = emptyList(),
)

@Serializable
data class OllamaTagEntry(
    val name: String,
    val model: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: OllamaTagDetails? = null,
)

@Serializable
data class OllamaTagDetails(
    @SerialName("parent_model") val parentModel: String? = null,
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
)

@Serializable
data class OllamaShowRequest(val model: String)

@Serializable
data class OllamaShowResponse(
    val capabilities: List<String> = emptyList(),
    val details: OllamaShowDetails? = null,
    @SerialName("model_info") val modelInfo: JsonObject? = null,
)

@Serializable
data class OllamaShowDetails(
    val family: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
)

@Serializable
data class OllamaPullRequest(
    val model: String,
    val stream: Boolean = true,
)

@Serializable
data class OllamaPullStreamChunk(
    val status: String? = null,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
    val error: String? = null,
)
