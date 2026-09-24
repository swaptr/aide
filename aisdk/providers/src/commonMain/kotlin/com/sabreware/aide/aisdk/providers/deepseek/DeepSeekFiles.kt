package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.FileUploadResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.providers.options.optInt
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DeepSeek's Files API — `POST /files`, multipart, IMAGES ONLY.
 *
 * DeepSeek's file store is the upload half of its vision input: an uploaded image is named on later
 * turns as a flat `{"type":"file","file_id":…}` part (see `DeepSeekFileParts.kt`), which is what the
 * returned [FileData.Reference] id plugs into. The documented constraints — JPEG, PNG, GIF or WebP,
 * 64 MiB, `purpose` always `user_data`, an optional expiry between one hour and thirty days
 * (https://api-docs.deepseek.com/api/create-file) — are checked HERE, before any bytes go out: a 64 MiB
 * upload that the server rejects for its media type has cost the caller the whole transfer to learn
 * something the first four bytes already said.
 *
 * The 512-character filename ceiling is the reference's, not the vendor's — the docs name no limit.
 * Kept, because the reference has recorded it and a limit that is too strict costs a rename, where one
 * that is too lax costs a failed upload.
 */
internal class DeepSeekFiles(
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : ProviderFiles {

    override val provider: String = DEEPSEEK_PROVIDER_ID

    override suspend fun uploadFile(options: FileUploadOptions): FileUploadResult {
        val bytes = when (val data = options.data) {
            is FileData.Bytes -> data.bytes
            is FileData.Text -> data.text.encodeToByteArray()
            // A URL is the vendor's fetch and a reference already IS an id; guessing either into an
            // upload would silently store the wrong thing under a fresh id.
            is FileData.Url, is FileData.Reference -> throw InvalidArgumentError(
                message = "uploadFile takes file content (Bytes or Text); got ${data::class.simpleName}",
                argument = "data",
            )
        }
        val expiresAfter = options.providerOptions?.get(DEEPSEEK_PROVIDER_ID)?.optInt("expiresAfter")
        if (expiresAfter != null && expiresAfter !in EXPIRES_AFTER_SECONDS) {
            throw InvalidArgumentError(
                message = "DeepSeek expiresAfter must be between ${EXPIRES_AFTER_SECONDS.first} and " +
                    "${EXPIRES_AFTER_SECONDS.last} seconds. Received $expiresAfter.",
                argument = "expiresAfter",
            )
        }
        validateDeepSeekUpload(bytes, options.mediaType, options.filename)

        val parts = buildList {
            add(
                MultipartPart.File(
                    field = "file",
                    // The reference's Blob default: an upload must be named, and `blob` is what a
                    // browser calls an unnamed one — kept so recorded fixtures stay comparable.
                    fileName = options.filename ?: "blob",
                    bytes = bytes,
                    contentType = options.mediaType,
                ),
            )
            add(MultipartPart.Field("purpose", "user_data"))
            expiresAfter?.let {
                // Bracketed, not nested: multipart has no objects, and this is how DeepSeek's schema
                // spells the pair.
                add(MultipartPart.Field("expires_after[anchor]", "created_at"))
                add(MultipartPart.Field("expires_after[seconds]", it.toString()))
            }
        }

        // The call's own headers ride the upload, as the reference's do since 5190b67.
        val result = http.postMultipartParts(
            url = "$baseUrl/files",
            parts = parts,
            headers = combineHeaders(headers, options.headers),
        )
        val response = decodeDeepSeekFileResponse(result.value)

        return FileUploadResult(
            providerReference = mapOf(DEEPSEEK_PROVIDER_ID to response.id),
            // DeepSeek records no media type of its own, so the declared one is the only one there is.
            mediaType = options.mediaType,
            filename = response.filename ?: options.filename,
            providerMetadata = mapOf(
                DEEPSEEK_PROVIDER_ID to buildJsonObject {
                    response.kind?.let { put("object", it) }
                    response.filename?.let { put("filename", it) }
                    response.purpose?.let { put("purpose", it) }
                    response.bytes?.let { put("bytes", it) }
                    response.createdAt?.let { put("createdAt", it) }
                    response.expiresAt?.let { put("expiresAt", it) }
                },
            ),
        )
    }
}

// ---- Validation ----------------------------------------------------------------------------------

/** 64 MiB, the vendor's documented ceiling. */
internal const val DEEPSEEK_MAX_FILE_BYTES: Long = 64L * 1024 * 1024

/** The reference's ceiling; DeepSeek documents none. Counted in code points, as the reference does. */
internal const val DEEPSEEK_MAX_FILENAME_LENGTH: Int = 512

/** One hour to thirty days — the documented range of `expires_after[seconds]`. */
private val EXPIRES_AFTER_SECONDS = 3_600..2_592_000

/** The four formats DeepSeek accepts; `image/jpg` is the alias the reference honours alongside them. */
internal val DEEPSEEK_IMAGE_MEDIA_TYPES: Set<String> =
    setOf("image/gif", "image/jpeg", "image/jpg", "image/png", "image/webp")

/**
 * Declared types that say nothing about the format. An upload declared this way is accepted on the
 * strength of its bytes or its filename extension, and refused when neither says anything either.
 */
private val GENERIC_MEDIA_TYPES = setOf(
    "", "application/binary", "application/octet-stream", "binary/octet-stream", "image", "image/*",
)

private val SUPPORTED_EXTENSIONS = setOf("gif", "jpeg", "jpg", "png", "webp")

private const val SUPPORTED_FORMATS = "DeepSeek file uploads support JPEG, PNG, GIF, and WebP images."

/**
 * The reference's `validateFileUpload`, in the same order: size, filename, then the media type — sniffed
 * bytes outrank the declaration, a supported declaration outranks a generic one, and a generic
 * declaration needs bytes or an extension to vouch for it.
 */
internal fun validateDeepSeekUpload(bytes: ByteArray, mediaType: String, filename: String?) {
    if (bytes.size > DEEPSEEK_MAX_FILE_BYTES) {
        throw InvalidArgumentError(
            message = "DeepSeek file uploads must not exceed 64 MiB (${DEEPSEEK_MAX_FILE_BYTES.grouped()} bytes). " +
                "Received ${bytes.size.toLong().grouped()} bytes.",
            argument = "data",
        )
    }
    if (filename != null) {
        val length = filename.count { !it.isLowSurrogate() }
        if (length > DEEPSEEK_MAX_FILENAME_LENGTH) {
            throw InvalidArgumentError(
                message = "DeepSeek filenames must not exceed $DEEPSEEK_MAX_FILENAME_LENGTH characters. " +
                    "Received $length characters.",
                argument = "filename",
            )
        }
    }

    val normalized = mediaType.substringBefore(';').trim().lowercase()
    val detected = MediaType.detect(bytes)
    if (detected != null && detected !in DEEPSEEK_IMAGE_MEDIA_TYPES) {
        throw InvalidArgumentError(
            message = "$SUPPORTED_FORMATS Detected unsupported file content type \"$detected\".",
            argument = "data",
        )
    }
    if (normalized in DEEPSEEK_IMAGE_MEDIA_TYPES) return
    if (normalized !in GENERIC_MEDIA_TYPES) {
        throw InvalidArgumentError(
            message = "$SUPPORTED_FORMATS Received unsupported media type \"$mediaType\".",
            argument = "mediaType",
        )
    }
    if (detected != null || filename.hasSupportedExtension()) return
    throw InvalidArgumentError(
        message = "$SUPPORTED_FORMATS Provide a supported media type or a filename ending in " +
            ".jpg, .jpeg, .png, .gif, or .webp. Received \"$mediaType\".",
        argument = "mediaType",
    )
}

private fun String?.hasSupportedExtension(): Boolean {
    val name = this ?: return false
    val dot = name.lastIndexOf('.')
    return dot != -1 && name.substring(dot + 1).lowercase() in SUPPORTED_EXTENSIONS
}

/** `67108864` as `67,108,864` — the reference's `en-US` grouping, so the message reads as a size. */
private fun Long.grouped(): String {
    val digits = toString()
    return buildString {
        digits.forEachIndexed { index, char ->
            if (index > 0 && (digits.length - index) % GROUP == 0) append(',')
            append(char)
        }
    }
}

private const val GROUP = 3

// ---- Response --------------------------------------------------------------------------------------

/** `POST /files` response — the id is the whole point; the rest is metadata worth not losing. */
internal data class DeepSeekFileResponse(
    val id: String,
    val kind: String? = null,
    val bytes: Long? = null,
    val createdAt: Long? = null,
    val filename: String? = null,
    val purpose: String? = null,
    val expiresAt: Long? = null,
)

/**
 * Decodes and VALIDATES the upload response, field by field.
 *
 * Every field but `id` is optional — an incomplete response must not fail an upload that succeeded —
 * but a field that IS present must be the documented shape: `object` is always `file`, `purpose`
 * always `user_data`, the counts non-negative integers. The reference validates the same way, and the
 * reason is metadata: a `bytes: -1` carried into `providerMetadata` is a number a caller will trust.
 */
internal fun decodeDeepSeekFileResponse(element: JsonElement): DeepSeekFileResponse {
    val body = element as? JsonObject ?: throw invalidField("response", element)
    fun present(key: String): JsonElement? = body[key]?.takeIf { it !is JsonNull }
    fun string(key: String): String? = present(key)?.let { it.stringOrNull() ?: throw invalidField(key, element) }
    fun count(key: String): Long? = present(key)?.let { it.nonNegativeOrNull() ?: throw invalidField(key, element) }

    val id = string("id") ?: throw invalidField("id", element)
    val kind = string("object")?.also { if (it != "file") throw invalidField("object", element) }
    val purpose = string("purpose")?.also { if (it != "user_data") throw invalidField("purpose", element) }
    return DeepSeekFileResponse(
        id = id,
        kind = kind,
        bytes = count("bytes"),
        createdAt = count("created_at"),
        filename = string("filename"),
        purpose = purpose,
        expiresAt = count("expires_at"),
    )
}

private fun invalidField(field: String, data: JsonElement): InvalidResponseDataError = InvalidResponseDataError(
    message = "DeepSeek returned an invalid file response: \"$field\" is not the documented shape.",
    data = data,
)

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.nonNegativeOrNull(): Long? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 }
