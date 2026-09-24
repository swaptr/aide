package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.InvalidPromptError
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.UnsupportedFunctionalityError
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.util.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// DeepSeek's image input, the two halves the shared OpenAI-compatible wire gets wrong or cannot check.

/**
 * Flattens OpenAI's nested file part into DeepSeek's flat one.
 *
 * The shared prompt converter writes a vendor-held file as OpenAI spells it —
 * `{"type":"file","file":{"file_id":…}}` — and inline non-image bytes as
 * `{"type":"file","file":{"file_data":…,"filename":…}}`. DeepSeek reads the same three keys at the
 * TOP of the part: `{"type":"file","file_id":…}` and `{"type":"file","file_data":…,"filename":…}`
 * (https://api-docs.deepseek.com/api/create-chat-completion). The nested shape is not rejected — it
 * is a part with an unknown key, which is a model that cannot see the image and says nothing about
 * it. This runs as the compat model's body transform, so the id a [DeepSeekFiles] upload returns
 * reaches the wire in the form the vendor documents.
 */
internal fun deepSeekRequestBody(body: JsonObject): JsonObject {
    val messages = body["messages"] as? JsonArray ?: return body
    var changed = false
    val rewritten = messages.map { message ->
        val flat = (message as? JsonObject)?.withFlatFileParts() ?: message
        if (flat !== message) changed = true
        flat
    }
    // Most turns carry no file part; rebuilding a long history for nothing is the cost this avoids.
    return if (changed) JsonObject(body + ("messages" to JsonArray(rewritten))) else body
}

private fun JsonObject.withFlatFileParts(): JsonObject {
    val content = this["content"] as? JsonArray ?: return this
    var changed = false
    val parts = content.map { part ->
        val flat = (part as? JsonObject)?.flattenedFilePart() ?: part
        if (flat !== part) changed = true
        flat
    }
    return if (changed) JsonObject(this + ("content" to JsonArray(parts))) else this
}

private fun JsonObject.flattenedFilePart(): JsonObject {
    if ((this["type"] as? JsonPrimitive)?.content != "file") return this
    val nested = this["file"] as? JsonObject ?: return this
    return buildJsonObject {
        put("type", "file")
        nested.forEach { (key, value) -> put(key, value) }
    }
}

/**
 * The image-part rules the vendor documents and the wire cannot enforce.
 *
 * DeepSeek accepts JPEG, PNG, GIF and WebP and an `image_url` of at most 8192 characters; anything
 * else is a 400 spent to learn what the part already said. A [FileData.Reference] is exempt — the
 * format was checked when the file was uploaded — and the bytes outrank the declared type where the
 * two disagree, exactly as the shared converter resolves them.
 */
internal fun validateDeepSeekImageParts(prompt: List<ModelMessage>) {
    for (message in prompt) {
        if (message !is ModelMessage.User) continue
        for (part in message.content) {
            if (part !is UserPart.File || part.mediaType.substringBefore('/') != "image") continue
            when (val data = part.data) {
                is FileData.Url -> {
                    if (data.url.length > DEEPSEEK_IMAGE_URL_MAX_LENGTH) {
                        throw InvalidPromptError(
                            "DeepSeek image URLs must not exceed $DEEPSEEK_IMAGE_URL_MAX_LENGTH characters.",
                            prompt,
                        )
                    }
                    requireSupportedImage(part.mediaType)
                }
                is FileData.Bytes -> requireSupportedImage(MediaType.detect(data.bytes) ?: part.mediaType)
                is FileData.Reference, is FileData.Text -> Unit
            }
        }
    }
}

private fun requireSupportedImage(mediaType: String) {
    if (mediaType !in DEEPSEEK_IMAGE_MEDIA_TYPES) {
        throw UnsupportedFunctionalityError(
            functionality = "DeepSeek image media type $mediaType",
            message = "DeepSeek supports JPEG, PNG, GIF, and WebP image inputs.",
        )
    }
}

/** The documented ceiling on an `image_url` — a data URL counts every base64 character. */
private const val DEEPSEEK_IMAGE_URL_MAX_LENGTH = 8192
