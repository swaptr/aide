package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileDeleteResult
import com.sabreware.aide.aisdk.FileDownloadResult
import com.sabreware.aide.aisdk.FileMetadataResult
import com.sabreware.aide.aisdk.FileOperationOptions
import com.sabreware.aide.aisdk.FileUploadContent
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.FileUploadResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoSuchProviderReferenceError
import com.sabreware.aide.aisdk.ProviderFiles
import com.sabreware.aide.aisdk.ProviderMetadata
import com.sabreware.aide.aisdk.util.MultipartPart
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.combineHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic's Files API, behind the `files-api-2025-04-14` beta.
 *
 * `POST /v1/files` is the mint for [FileData.Reference]: the returned id is what a prompt sends as
 * `{"type":"file","file_id":…}` on every later turn instead of re-uploading the bytes. Until this
 * existed the prompt side could CONSUME a file id while nothing could create one.
 *
 * The three reads — `GET /v1/files/{id}`, `GET /v1/files/{id}/content`, `DELETE /v1/files/{id}` —
 * come from the vendor's Files API reference, not from the vendored port, whose Anthropic store still
 * uploads only. Their shapes are the documented ones: metadata is the same file object an upload
 * returns, content is the raw bytes under the file's own media type (only a file the code-execution
 * tool produced is downloadable; a user upload answers 400), and a deletion answers
 * `{"id": …, "type": "file_deleted"}`.
 *
 * A streamed upload goes out through the same multipart form as an inline one — the wire does not
 * change, only where the bytes come from — where the reference's Anthropic store refuses a stream.
 */
internal class AnthropicFiles(
    private val http: ProviderHttp,
    private val baseUrl: String,
    private val headers: Map<String, String>,
) : ProviderFiles {

    override val provider: String = ANTHROPIC_PROVIDER_ID

    override suspend fun uploadFile(options: FileUploadOptions): FileUploadResult {
        // The reference's Blob default: an upload must be named, and `blob` is what a browser calls an
        // unnamed one — kept so recorded fixtures stay comparable.
        val fileName = options.filename ?: "blob"
        val result = when (val content = options.content) {
            is FileUploadContent.Inline -> http.postMultipartParts(
                url = "$baseUrl/files",
                parts = listOf(
                    MultipartPart.File(
                        field = "file",
                        fileName = fileName,
                        bytes = content.data.inlineBytes(),
                        contentType = options.mediaType,
                    ),
                ),
                headers = filesHeaders(options.headers),
            )
            is FileUploadContent.Stream -> http.postMultipartStream(
                url = "$baseUrl/files",
                parts = listOf(
                    MultipartPart.Stream(
                        field = "file",
                        fileName = fileName,
                        content = content.bytes,
                        byteSize = content.byteSize,
                        contentType = options.mediaType,
                    ),
                ),
                headers = filesHeaders(options.headers),
            )
        }

        val response = ProviderJson.decodeFromJsonElement(AnthropicFileResponse.serializer(), result.value)
        return FileUploadResult(
            providerReference = mapOf(ANTHROPIC_PROVIDER_ID to response.id),
            mediaType = response.mimeType ?: options.mediaType,
            filename = response.filename ?: options.filename,
            byteSize = response.sizeBytes,
            createdAt = response.createdAt?.let(::isoToEpochMillis),
            providerMetadata = response.toProviderMetadata(),
        )
    }

    override suspend fun getFileMetadata(options: FileOperationOptions): FileMetadataResult {
        val id = options.fileId()
        val result = http.getJson("$baseUrl/files/${id.encodeURLPathPart()}", filesHeaders(options.headers))
        val response = ProviderJson.decodeFromJsonElement(AnthropicFileResponse.serializer(), result.value)
        return FileMetadataResult(
            providerReference = mapOf(ANTHROPIC_PROVIDER_ID to response.id),
            filename = response.filename,
            mediaType = response.mimeType,
            byteSize = response.sizeBytes,
            createdAt = response.createdAt?.let(::isoToEpochMillis),
            providerMetadata = response.toProviderMetadata(),
        )
    }

    /**
     * The content endpoint, streamed under the same origin guard as every other vendor-named URL.
     *
     * [FileDownloadResult.mediaType] is null: the media type is a response header, and a cold flow has
     * not opened the response when the result is built. A caller that needs it reads
     * [getFileMetadata] — the vendor serves the content under exactly the `mime_type` recorded there.
     */
    override suspend fun downloadFile(options: FileOperationOptions): FileDownloadResult {
        val id = options.fileId()
        return FileDownloadResult(
            content = http.getByteStream(
                url = "$baseUrl/files/${id.encodeURLPathPart()}/content",
                headers = filesHeaders(options.headers),
                trustedOrigin = baseUrl,
            ),
        )
    }

    /** A 2xx is the confirmation; the documented `file_deleted` body rides along as metadata. */
    override suspend fun deleteFile(options: FileOperationOptions): FileDeleteResult {
        val id = options.fileId()
        val result = http.delete("$baseUrl/files/${id.encodeURLPathPart()}", filesHeaders(options.headers))
        val type = (result.value as? JsonObject)?.get("type")?.stringOrNull()
        return FileDeleteResult(
            providerReference = mapOf(ANTHROPIC_PROVIDER_ID to id),
            deleted = true,
            providerMetadata = type?.let { mapOf(ANTHROPIC_PROVIDER_ID to buildJsonObject { put("type", it) }) },
        )
    }

    private fun filesHeaders(callHeaders: Map<String, String>?): Map<String, String> =
        combineHeaders(headers, mapOf("anthropic-beta" to FILES_BETA), callHeaders)

    /** The id under this provider's key — typed, so a caller can upload and retry rather than read a log. */
    private fun FileOperationOptions.fileId(): String =
        file[ANTHROPIC_PROVIDER_ID] ?: throw NoSuchProviderReferenceError(ANTHROPIC_PROVIDER_ID, file)

    private fun FileData.inlineBytes(): ByteArray = when (this) {
        is FileData.Bytes -> bytes
        is FileData.Text -> text.encodeToByteArray()
        // A URL is the vendor's fetch and a reference already IS an id; guessing either into an
        // upload would silently store the wrong thing under a fresh id.
        is FileData.Url, is FileData.Reference -> throw InvalidArgumentError(
            message = "uploadFile takes file content (Bytes or Text); got ${this::class.simpleName}",
            argument = "data",
        )
    }

    private fun AnthropicFileResponse.toProviderMetadata(): ProviderMetadata = mapOf(
        ANTHROPIC_PROVIDER_ID to buildJsonObject {
            filename?.let { put("filename", it) }
            mimeType?.let { put("mimeType", it) }
            sizeBytes?.let { put("sizeBytes", it) }
            createdAt?.let { put("createdAt", it) }
            downloadable?.let { put("downloadable", it) }
        },
    )

    private companion object {
        const val FILES_BETA = "files-api-2025-04-14"
    }
}

/** The file object `POST /v1/files` and `GET /v1/files/{id}` answer with — the id is the whole point. */
@Serializable
internal data class AnthropicFileResponse(
    val id: String,
    val filename: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val downloadable: Boolean? = null,
)

/**
 * An RFC 3339 timestamp — `2025-04-14T12:00:00Z`, with an optional fraction and a `Z` or `±hh:mm`
 * offset — as epoch milliseconds, or null for anything else.
 *
 * Hand-rolled because the port carries no datetime dependency (see `DESIGN.md`: a `Date` is a `Long`).
 * The civil-to-days step is the proleptic-Gregorian arithmetic every such library uses.
 */
internal fun isoToEpochMillis(value: String): Long? {
    val match = RFC_3339.matchEntire(value.trim()) ?: return null
    val groups = match.groupValues
    val days = daysFromCivil(groups[1].toInt(), groups[2].toInt(), groups[3].toInt())
    val seconds = days * SECONDS_PER_DAY +
        groups[4].toLong() * SECONDS_PER_HOUR + groups[5].toLong() * SECONDS_PER_MINUTE + groups[6].toLong()
    val millis = groups[7].takeIf { it.isNotEmpty() }?.padEnd(MILLIS_DIGITS, '0')?.take(MILLIS_DIGITS)?.toLong() ?: 0L
    val offsetMinutes = when (val offset = groups[8]) {
        "Z", "z" -> 0L
        else -> {
            val sign = if (offset.startsWith("-")) -1L else 1L
            sign * (offset.substring(1, 3).toLong() * MINUTES_PER_HOUR + offset.substring(4, 6).toLong())
        }
    }
    return (seconds - offsetMinutes * SECONDS_PER_MINUTE) * MILLIS_PER_SECOND + millis
}

/** Days since 1970-01-01 for a proleptic-Gregorian civil date (Howard Hinnant's `days_from_civil`). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - (DAYS_ERA_YEARS - 1)) / DAYS_ERA_YEARS
    val yearOfEra = y - era * DAYS_ERA_YEARS
    val monthFromMarch = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * DAYS_PER_ERA + dayOfEra - DAYS_TO_EPOCH
}

private val RFC_3339 = Regex("(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|z|[+-]\\d{2}:\\d{2})")
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_DIGITS = 3
private const val DAYS_ERA_YEARS = 400
private const val DAYS_PER_ERA = 146_097L
private const val DAYS_TO_EPOCH = 719_468L
