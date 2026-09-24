package com.sabreware.aide.aisdk.providers.xai

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
import com.sabreware.aide.aisdk.providers.openai.encodeFileId
import com.sabreware.aide.aisdk.providers.openai.fileIdFor
import com.sabreware.aide.aisdk.providers.openai.fileTtlSeconds
import com.sabreware.aide.aisdk.providers.options.optBoolean
import com.sabreware.aide.aisdk.providers.options.optString
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * xAI's Files API — `POST /v1/files` (multipart), and the three reads on an id: `GET /v1/files/{id}`,
 * `GET /v1/files/{id}/content`, `DELETE /v1/files/{id}`.
 *
 * Two consumers share it: a caller minting the [FileData.Reference] that xAI's `file_search` tool and
 * Responses `input_file` parts name, and [XaiResponsesBatchModel], whose JSONL goes through the same
 * endpoint before a batch can be created from it. The response decoder is therefore one class rather
 * than two copies that would drift.
 *
 * **Part order is load-bearing.** xAI reads `expires_after` and `team_id` only if they arrive BEFORE the
 * file part — an upload with the fields after the file is stored with neither applied and reports no
 * error — so every field precedes the file, on the buffered path and the streaming one alike. The
 * expiry is a flat `expires_after` in seconds (not OpenAI's bracketed pair), between one hour and
 * thirty days, validated before any bytes go out. The vendor's docs also list a `purpose` field; the
 * reference sends none and xAI's own batch guide uploads with `file` alone, so it is not invented here.
 */
internal class XaiFiles(
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : ProviderFiles {

    override val provider: String = XAI_PROVIDER_ID

    override suspend fun uploadFile(options: FileUploadOptions): FileUploadResult {
        val vendor = options.providerOptions?.get(XAI_PROVIDER_ID)
        val fields = buildList {
            vendor?.get("expiresAfter")?.takeIf { it != JsonNull }?.let { ttl ->
                add(MultipartPart.Field("expires_after", ttl.fileTtlSeconds("expiresAfter", "xAI").toString()))
            }
            vendor?.optString("teamId")?.let { add(MultipartPart.Field("team_id", it)) }
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
                parts = fields + MultipartPart.File(
                    field = "file",
                    fileName = fileName,
                    bytes = content.data.uploadBytes(),
                    contentType = options.mediaType,
                ),
                headers = callHeaders,
            )
        }
        val response = ProviderJson.decodeFromJsonElement(XaiFileResponse.serializer(), result.value)

        return FileUploadResult(
            providerReference = mapOf(XAI_PROVIDER_ID to response.id),
            // xAI records no media type of its own, so the declared one is the only one there is.
            mediaType = options.mediaType,
            filename = response.filename ?: options.filename,
            byteSize = response.bytes,
            createdAt = response.createdAt?.let { it * MILLIS_PER_SECOND },
            expiresAt = response.expiresAt?.let { it * MILLIS_PER_SECOND },
            providerMetadata = mapOf(XAI_PROVIDER_ID to response.toFileMetadata()),
        )
    }

    override suspend fun getFileMetadata(options: FileOperationOptions): FileMetadataResult {
        val result = http.getJson(fileUrl(options), combineHeaders(headers, options.headers))
        val response = ProviderJson.decodeFromJsonElement(XaiFileResponse.serializer(), result.value)
        return FileMetadataResult(
            providerReference = mapOf(XAI_PROVIDER_ID to response.id),
            filename = response.filename,
            byteSize = response.bytes,
            createdAt = response.createdAt?.let { it * MILLIS_PER_SECOND },
            expiresAt = response.expiresAt?.let { it * MILLIS_PER_SECOND },
            providerMetadata = mapOf(XAI_PROVIDER_ID to response.toFileMetadata()),
        )
    }

    /** Cold and chunked — see `OpenAIFiles.downloadFile` for why the media type is not reported. */
    override suspend fun downloadFile(options: FileOperationOptions): FileDownloadResult = FileDownloadResult(
        content = http.getByteStream(
            url = "${fileUrl(options)}/content",
            headers = combineHeaders(headers, options.headers),
            trustedOrigin = baseUrl,
        ),
    )

    override suspend fun deleteFile(options: FileOperationOptions): FileDeleteResult {
        val fileId = options.file.fileIdFor(XAI_PROVIDER_ID)
        val result = http.delete(fileUrl(fileId), combineHeaders(headers, options.headers))
        val body = result.value as? JsonObject
        return FileDeleteResult(
            providerReference = mapOf(XAI_PROVIDER_ID to (body?.optString("id") ?: fileId)),
            deleted = body?.optBoolean("deleted") ?: true,
        )
    }

    private fun fileUrl(options: FileOperationOptions): String = fileUrl(options.file.fileIdFor(XAI_PROVIDER_ID))

    private fun fileUrl(fileId: String): String = "$baseUrl/files/${encodeFileId(fileId)}"
}

private const val MILLIS_PER_SECOND = 1000L

/**
 * The bytes an upload sends, or a refusal for the two forms that are not bytes.
 *
 * A URL is the vendor's fetch and a reference already IS an id; guessing either into an upload would
 * silently store the wrong thing under a fresh id.
 */
internal fun FileData.uploadBytes(): ByteArray = when (this) {
    is FileData.Bytes -> bytes
    is FileData.Text -> text.encodeToByteArray()
    is FileData.Url, is FileData.Reference -> throw InvalidArgumentError(
        message = "uploadFile takes file content (Bytes or Text); got ${this::class.simpleName}",
        argument = "data",
    )
}

/**
 * `POST /v1/files` and `GET /v1/files/{id}` — the same object; the id is the whole point, the rest is
 * metadata worth not losing. `expires_at` is present only on a file uploaded with a TTL.
 */
@Serializable
internal data class XaiFileResponse(
    val id: String,
    @SerialName("object") val kind: String? = null,
    val bytes: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val filename: String? = null,
    val purpose: String? = null,
    val status: String? = null,
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
