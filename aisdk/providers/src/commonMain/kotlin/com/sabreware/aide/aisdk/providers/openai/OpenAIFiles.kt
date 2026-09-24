package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileDeleteResult
import com.sabreware.aide.aisdk.FileDownloadResult
import com.sabreware.aide.aisdk.FileMetadataResult
import com.sabreware.aide.aisdk.FileOperationOptions
import com.sabreware.aide.aisdk.FileUploadContent
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.FileUploadResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.forProvider
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI's Files API — `POST /v1/files` (multipart), and the three reads on an id: `GET /v1/files/{id}`,
 * `GET /v1/files/{id}/content`, `DELETE /v1/files/{id}`.
 *
 * The mint for a [FileData.Reference] on this vendor: the returned id is what a prompt sends as
 * `{"type":"input_file","file_id":…}` on every later turn instead of re-uploading the bytes, and it is
 * the same endpoint [OpenAIResponsesBatchModel] uploads its JSONL through.
 *
 * Wire details worth reading rather than guessing:
 *
 * - `purpose` is REQUIRED by the API. It defaults to `assistants` here — the reference's default, and
 *   the purpose a file a prompt will reference almost always has — so a caller who only wants a file id
 *   does not have to know OpenAI's purpose vocabulary. `providerOptions.openai.purpose` overrides it.
 * - `expiresAfter` goes out as the bracketed PAIR `expires_after[anchor]` / `expires_after[seconds]`.
 *   The endpoint is a form, not JSON, and a nested object has no other spelling in one; the anchor is
 *   always `created_at` because that is the only anchor the API defines.
 * - A [FileUploadContent.Stream] is written as its flow produces chunks, fields first and the file last
 *   — the order the reference's stream path sends, so a vendor reading fields as the parts stream past
 *   has them before the bytes. Nothing larger than one chunk is resident.
 * - A file id becomes a path segment that cannot climb out of `/files/`: `.` and `..` are encoded
 *   TWICE, as the reference does, because a URL parser normalizes a single `%2E` back into the dot
 *   segment it stands for.
 */
internal class OpenAIFiles(
    http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : ProviderFiles {

    override val provider: String = OPENAI_PROVIDER_ID

    private val http = http.withErrorStructure(OpenAIErrorStructure)

    override suspend fun uploadFile(options: FileUploadOptions): FileUploadResult {
        val openai = options.providerOptions?.forProvider(OPENAI_PROVIDER_ID)
        val fields = buildList {
            add(MultipartPart.Field("purpose", openai?.optString("purpose") ?: DEFAULT_PURPOSE))
            openai?.get("expiresAfter")?.takeIf { it != JsonNull }
                ?.let { addAll(expiresAfterFields(it.fileTtlSeconds("expiresAfter", "OpenAI"))) }
        }
        // The reference's Blob default: an upload must be named, and `blob` is what a browser calls an
        // unnamed one — kept so recorded fixtures stay comparable.
        val fileName = options.filename ?: "blob"
        val url = "$baseUrl/files"
        val callHeaders = combineHeaders(headers, options.headers)

        val result = when (val content = options.content) {
            is FileUploadContent.Stream -> http.postMultipartStream(
                url = url,
                parts = fields + MultipartPart.Stream(
                    field = "file",
                    fileName = fileName,
                    content = content.bytes,
                    byteSize = content.byteSize,
                    contentType = options.mediaType,
                ),
                headers = callHeaders,
            )
            is FileUploadContent.Inline -> http.postMultipartParts(
                url = url,
                parts = listOf(
                    MultipartPart.File(
                        field = "file",
                        fileName = fileName,
                        bytes = content.data.inlineBytesOrNull() ?: throw notAnUpload(content.data),
                        contentType = options.mediaType,
                    ),
                ) + fields,
                headers = callHeaders,
            )
        }
        val response = ProviderJson.decodeFromJsonElement(OpenAIFileResponse.serializer(), result.value)

        return FileUploadResult(
            providerReference = mapOf(OPENAI_PROVIDER_ID to response.id),
            mediaType = options.mediaType,
            filename = response.filename ?: options.filename,
            byteSize = response.bytes,
            createdAt = response.createdAt?.let { it * MILLIS_PER_SECOND },
            expiresAt = response.expiresAt?.let { it * MILLIS_PER_SECOND },
            providerMetadata = mapOf(OPENAI_PROVIDER_ID to response.toFileMetadata()),
        )
    }

    override suspend fun getFileMetadata(options: FileOperationOptions): FileMetadataResult {
        val result = http.getJson(fileUrl(options), combineHeaders(headers, options.headers))
        val response = ProviderJson.decodeFromJsonElement(OpenAIFileResponse.serializer(), result.value)
        return FileMetadataResult(
            providerReference = mapOf(OPENAI_PROVIDER_ID to response.id),
            filename = response.filename,
            byteSize = response.bytes,
            createdAt = response.createdAt?.let { it * MILLIS_PER_SECOND },
            expiresAt = response.expiresAt?.let { it * MILLIS_PER_SECOND },
            providerMetadata = mapOf(OPENAI_PROVIDER_ID to response.toFileMetadata()),
        )
    }

    /**
     * Cold, and read as it arrives: a stored file is on its way to disk, and holding it whole to hand
     * over something the caller writes out chunk by chunk is the memory the stream exists to save. Our
     * own origin, so the credentials ride along; the guard drops them if a redirect leaves it.
     *
     * The media type is NOT reported: `ProviderHttp.getByteStream` opens the response when the flow is
     * collected, so the `Content-Type` the reference reads before returning does not exist yet here.
     */
    override suspend fun downloadFile(options: FileOperationOptions): FileDownloadResult = FileDownloadResult(
        content = http.getByteStream(
            url = "${fileUrl(options)}/content",
            headers = combineHeaders(headers, options.headers),
            trustedOrigin = baseUrl,
        ),
    )

    override suspend fun deleteFile(options: FileOperationOptions): FileDeleteResult {
        val fileId = options.file.fileIdFor(OPENAI_PROVIDER_ID)
        val result = http.delete(fileUrl(fileId), combineHeaders(headers, options.headers))
        val body = result.value as? JsonObject
        return FileDeleteResult(
            providerReference = mapOf(OPENAI_PROVIDER_ID to (body?.optString("id") ?: fileId)),
            // A blank 2xx is the vendor confirming with nothing to add — see `ProviderHttp.delete`.
            deleted = body?.optBoolean("deleted") ?: true,
        )
    }

    private fun fileUrl(options: FileOperationOptions): String = fileUrl(options.file.fileIdFor(OPENAI_PROVIDER_ID))

    private fun fileUrl(fileId: String): String = "$baseUrl/files/${encodeFileId(fileId)}"

    private companion object {
        const val DEFAULT_PURPOSE = "assistants"
    }
}

private const val MILLIS_PER_SECOND = 1000L

/** The TTL a file store accepts: one hour to thirty days, the range OpenAI and xAI both document. */
internal val FILE_TTL_SECONDS: LongRange = 3_600L..2_592_000L

/**
 * A TTL option, validated BEFORE any request goes out, as the reference's schema does: an integer in
 * [FILE_TTL_SECONDS]. A rejected TTL costs nothing, where a rejected upload costs the whole transfer.
 */
internal fun JsonElement.fileTtlSeconds(argument: String, vendor: String): Long {
    val seconds = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
    if (seconds == null || seconds !in FILE_TTL_SECONDS) {
        throw InvalidArgumentError(
            message = "$vendor $argument must be an integer between ${FILE_TTL_SECONDS.first} and " +
                "${FILE_TTL_SECONDS.last} seconds. Received $this.",
            argument = argument,
        )
    }
    return seconds
}

/** `expires_after` as a multipart form spells a nested object: the bracketed pair, anchored at creation. */
internal fun expiresAfterFields(seconds: Long): List<MultipartPart> = listOf(
    MultipartPart.Field("expires_after[anchor]", "created_at"),
    MultipartPart.Field("expires_after[seconds]", seconds.toString()),
)

/**
 * The vendor's id out of a provider reference, refused when absent or blank: a blank id would address
 * `/files/` itself, and an id under some other vendor's key names a file this store never saw.
 */
internal fun Map<String, String>.fileIdFor(providerId: String): String {
    val id = this[providerId]
    if (id.isNullOrBlank()) {
        throw InvalidArgumentError(message = "file reference is missing an '$providerId' file id.", argument = "file")
    }
    return id
}

/**
 * The reference's `encodePathSegment`: percent-encoded, with `.` and `..` encoded TWICE. A URL parser
 * normalizes a single `%2E` back into the dot segment it stands for, and `/files/../batches` is a
 * request that has left the file store.
 */
internal fun encodeFileId(id: String): String = when (id) {
    "." -> "%252E"
    ".." -> "%252E%252E"
    else -> id.encodeURLPathPart()
}

/**
 * A URL is the vendor's fetch and a reference already IS an id; guessing either into an upload would
 * silently store the wrong thing under a fresh id.
 */
private fun notAnUpload(data: FileData): InvalidArgumentError = InvalidArgumentError(
    message = "uploadFile takes file content (Bytes or Text); got ${data::class.simpleName}",
    argument = "data",
)

/**
 * The bytes of a [FileData] that carries its content inline, or null for the two forms that do not.
 *
 * Shared by the file, skill and batch uploads, each of which refuses a URL or a reference with its own
 * message — the message names the argument, the rule is the same.
 */
internal fun FileData.inlineBytesOrNull(): ByteArray? = when (this) {
    is FileData.Bytes -> bytes
    is FileData.Text -> text.encodeToByteArray()
    is FileData.Url, is FileData.Reference -> null
}

/**
 * `POST /v1/files` and `GET /v1/files/{id}` — the same object; the id is the whole point, the rest is
 * metadata worth not losing. Ref: `files/openai-files-api.ts`. `expires_at` is null on a file with no
 * expiry, which is most of them.
 */
@Serializable
internal data class OpenAIFileResponse(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val bytes: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val filename: String? = null,
    val purpose: String? = null,
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
) {

    /** The reference's `toFileMetadata`: every recorded field, nulls omitted. */
    fun toFileMetadata(): JsonObject = buildJsonObject {
        filename?.let { put("filename", it) }
        purpose?.let { put("purpose", it) }
        bytes?.let { put("bytes", it) }
        createdAt?.let { put("createdAt", it) }
        status?.let { put("status", it) }
        expiresAt?.let { put("expiresAt", it) }
    }
}
