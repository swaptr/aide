package com.sabreware.aide.core.domain.chat

import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

/**
 * Opaque provider data, keyed first by provider id and then by that provider's own key.
 *
 * Carried verbatim and understood by nobody in between, which is precisely why nobody in between can
 * lose it. Mirrors `com.sabreware.aide.aisdk.ProviderMetadata`, deliberately: the wire adapters hand
 * these straight across.
 */
typealias ProviderPayload = Map<String, JsonObject>

@Serializable
sealed class AidePart {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AidePart()

    // mediaType captured at attach time (ImageStore re-encodes to JPEG); null falls back to a
    // base64-magic sniff in the wire mappers. Carrying it avoids mislabeling HEIC/AVIF/BMP.
    @Serializable
    @SerialName("image_bytes")
    data class ImageBytes(val bytesBase64: String, val mediaType: String? = null) : AidePart()

    @Serializable
    @SerialName("image_file")
    data class ImageFile(val path: String, val mediaType: String? = null) : AidePart()

    @Serializable
    @SerialName("audio_file")
    data class AudioFile(val path: String) : AidePart()

    /**
     * A binary document the model receives natively (today: PDF, gated on
     * [com.sabreware.aide.core.domain.model.ChatCapabilities.documentIn]). Text-extractable files never become this
     * part — they are inlined as [Text] at send time, so every engine (including on-device) reads them with
     * no capability needed. [name] is the user's filename, shown in the bubble chip and sent as the wire
     * `fileName` where the provider carries one.
     */
    @Serializable
    @SerialName("document_file")
    data class DocumentFile(val path: String, val mediaType: String, val name: String) : AidePart()

    @Serializable
    @SerialName("tool_response")
    data class ToolResponse(
        val name: String,
        val json: String,
        val callId: String? = null,
        val error: String? = null,
    ) : AidePart()

    /**
     * A tool call the model made. Replayed so the vendor can pair it with its result.
     *
     * [providerMetadata] matters here and not only on [Thinking]: Gemini puts its `thoughtSignature` on
     * the function-call part rather than on the thought, and a call replayed without it is rejected with
     * `Function call is missing a thought_signature` on the next round.
     */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val callId: String,
        val name: String,
        val argsJson: String,
        val providerMetadata: ProviderPayload? = null,
    ) : AidePart()

    /**
     * A reasoning/thinking trace, persisted so it can be replayed where the provider REQUIRES it.
     *
     * [providerMetadata] is the opaque, provider-namespaced payload that makes the block replayable:
     * Anthropic's `signature`, its `redacted_thinking` data, Gemini's `thoughtSignature`, OpenRouter's
     * whole `reasoning_details` block. AIDE models NONE of it — a field this layer does not have is data
     * it silently drops, and this is exactly where every LLM client currently loses signatures.
     *
     * A safety-redacted block is simply one of these with empty [text] and a payload; it used to be its
     * own part type, which meant two shapes to remember and no room for a third vendor's idea.
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val text: String,
        val durationMs: Long,
        val providerMetadata: ProviderPayload? = null,
    ) : AidePart()
}

@Serializable
data class AideMessage(
    val role: AideRole,
    val parts: List<AidePart>,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
) {
    val textContent: String
        get() = parts.filterIsInstance<AidePart.Text>().joinToString("") { it.text }

    companion object {
        const val JPEG_MEDIA_TYPE = "image/jpeg"

        fun user(text: String) = AideMessage(AideRole.User, listOf(AidePart.Text(text)))
        fun user(text: String, imagePath: String?): AideMessage {
            // ImageFile before text matches Gemma's chat-template ordering for multimodal turns.
            val parts = buildList {
                // ImageStore always re-encodes attachments to JPEG, so the mediaType is known here.
                if (imagePath != null) add(AidePart.ImageFile(imagePath, JPEG_MEDIA_TYPE))
                add(AidePart.Text(text))
            }
            return AideMessage(AideRole.User, parts)
        }
        fun model(text: String) = AideMessage(AideRole.Model, listOf(AidePart.Text(text)))
        fun system(text: String) = AideMessage(AideRole.System, listOf(AidePart.Text(text)))
    }
}

/** Well-known keys inside a [ProviderPayload]. Vendors spell the same idea differently. */
object ProviderPayloadKeys {
    /** Anthropic's thinking signature. */
    const val SIGNATURE: String = "signature"

    /** Anthropic's safety-redacted thinking payload. */
    const val REDACTED: String = "redactedData"

    /** Gemini's encrypted reasoning-continuity token. */
    const val THOUGHT_SIGNATURE: String = "thoughtSignature"
}

/**
 * A string value from any provider's namespace.
 *
 * Searches every namespace rather than requiring the caller to name one: a conversation may have been
 * held against a different provider than the one replaying it, and a signature that exists under some
 * other id is still the signature this block came with.
 */
fun ProviderPayload?.findString(key: String): String? = this
    ?.values
    ?.firstNotNullOfOrNull { entry ->
        (entry[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

/** Builds a payload for one provider. */
fun providerPayloadOf(providerId: String, vararg entries: Pair<String, String>): ProviderPayload =
    mapOf(providerId to JsonObject(entries.associate { (k, v) -> k to JsonPrimitive(v) }))

/** The signature this thinking block carries, under whichever name its vendor used. */
val AidePart.Thinking.signature: String?
    get() = providerMetadata.findString(ProviderPayloadKeys.SIGNATURE)
        ?: providerMetadata.findString(ProviderPayloadKeys.THOUGHT_SIGNATURE)

/** The safety-redacted payload, when this block is a redacted one. */
val AidePart.Thinking.redactedData: String?
    get() = providerMetadata.findString(ProviderPayloadKeys.REDACTED)

/** Whether this block can be replayed at all — an unsigned trace is scratchpad, not evidence. */
val AidePart.Thinking.isReplayable: Boolean
    get() = signature != null || redactedData != null
