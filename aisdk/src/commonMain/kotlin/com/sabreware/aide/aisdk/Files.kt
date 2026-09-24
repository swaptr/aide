package com.sabreware.aide.aisdk

import kotlinx.coroutines.flow.Flow

/**
 * A provider's file store — the half of [FileData.Reference] that can CREATE one.
 *
 * Uploading once and referencing thereafter is what stops a 20 MB PDF being re-sent on every turn of a
 * long conversation. The result's [FileUploadResult.providerReference] plugs straight into
 * [FileData.Reference], keyed by provider id, because the same logical file has a different id on every
 * vendor that stores it.
 *
 * Only [uploadFile] is required. [getFileMetadata], [downloadFile] and [deleteFile] are optional
 * capabilities: the reference declares them as optional methods whose presence signals support — the
 * pattern of `VideoModelV4` — and here, as on [VideoModel], the default implementation answers null,
 * which reads "this store cannot", never "the file is gone" (a missing file is the vendor's
 * [APICallError]). An implementation that overrides one never returns null from it.
 *
 * Ref: `files/v4` (`FilesV4`), version prefix dropped per the porting rules.
 */
public interface ProviderFiles {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject an implementation built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id — the key its file ids land under in [FileUploadResult.providerReference]. */
    public val provider: String

    /**
     * Uploads one file and returns the reference that names it from now on.
     *
     * @throws InvalidArgumentError if [FileUploadOptions.data] is a form this store cannot upload — a
     *   [FileData.Url] the vendor would have to fetch, or a [FileData.Reference] that is already an id.
     * @throws UnsupportedFunctionalityError if the content is a [FileUploadContent.Stream] and this store
     *   only uploads what it can hold in memory — see [FileUploadOptions.data].
     */
    public suspend fun uploadFile(options: FileUploadOptions): FileUploadResult

    /**
     * Reads what the vendor recorded about a previously uploaded file.
     *
     * Optional: null means this store offers no metadata reads.
     */
    public suspend fun getFileMetadata(options: FileOperationOptions): FileMetadataResult? = null

    /**
     * Streams the content of a previously uploaded file back.
     *
     * Optional: null means this store offers no content download. Where it does, the returned
     * [FileDownloadResult.content] is cold and the caller drains or abandons it.
     */
    public suspend fun downloadFile(options: FileOperationOptions): FileDownloadResult? = null

    /**
     * Deletes a previously uploaded file.
     *
     * Optional: null means this store offers no deletion.
     */
    public suspend fun deleteFile(options: FileOperationOptions): FileDeleteResult? = null
}

/**
 * Everything one upload needs.
 *
 * [content] is what goes on the wire: bytes already in memory ([FileUploadContent.Inline] over a
 * [FileData.Bytes] or [FileData.Text]), or a [FileUploadContent.Stream] a provider that supports streaming
 * uploads sends without buffering the whole file. A URL is the vendor's fetch, not an upload, and a
 * reference already names an uploaded file; an implementation throws [InvalidArgumentError] for either
 * rather than guessing what the caller meant.
 *
 * Cancelling the calling coroutine is the abort signal: no separate handle is carried, here or on any
 * other call in this specification.
 */
public data class FileUploadOptions(
    /** The file's content — see the class doc. */
    val content: FileUploadContent,
    /** The IANA media type, e.g. `application/pdf`. */
    val mediaType: String,
    /**
     * The upload's name at the vendor. Null lets the vendor pick its own default; a multipart-based
     * provider sends `blob`, because a part with no filename is read as a scalar field.
     */
    val filename: String? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers for this upload. Only applicable for HTTP-based providers. */
    val headers: Map<String, String>? = null,
) {

    /** An inline upload — the shape every upload had before streaming existed. */
    public constructor(
        data: FileData,
        mediaType: String,
        filename: String? = null,
        providerOptions: ProviderOptions? = null,
        headers: Map<String, String>? = null,
    ) : this(FileUploadContent.Inline(data), mediaType, filename, providerOptions, headers)

    /**
     * The inline payload — [FileData.Bytes] or [FileData.Text] — for a store that uploads from memory.
     *
     * This is the reference's `convertInlineFileDataToUint8Array` funnel: a provider without streaming
     * support reads its bytes through here, and a [FileUploadContent.Stream] surfaces as one clear
     * [UnsupportedFunctionalityError] before any request goes out. A store that streams reads [content]
     * instead and never touches this.
     *
     * @throws UnsupportedFunctionalityError if [content] is a [FileUploadContent.Stream].
     */
    val data: FileData
        get() = when (val content = content) {
            is FileUploadContent.Inline -> content.data
            is FileUploadContent.Stream -> throw UnsupportedFunctionalityError("streaming file upload")
        }
}

/** What an upload sends — see [FileUploadOptions]. */
public sealed interface FileUploadContent {

    /** Bytes or text already in memory: a [FileData.Bytes] or a [FileData.Text]. */
    public data class Inline(val data: FileData) : FileUploadContent

    /**
     * A byte stream, for a file too large to hold whole — the reference's `{ type: 'stream' }` variant.
     *
     * [bytes] is cold: the provider collects it once, while the request body is being written, and a
     * failed upload simply stops collecting — there is nothing to release, which is the property the
     * reference's stream teardown exists to guarantee. A source that can be collected only once must
     * therefore not be retried at the transport; see `ProviderHttp.postMultipartStream`.
     *
     * [byteSize], when known, lets the transport declare a `Content-Length` and a vendor validate the
     * upload before it has read it all. Null sends the body chunked.
     */
    public class Stream(
        public val bytes: Flow<ByteArray>,
        public val byteSize: Long? = null,
    ) : FileUploadContent
}

/**
 * Names a file for a metadata, download or delete operation.
 *
 * One options type for the three reads rather than the reference's three identical ones — the same
 * instantiation [BatchOperationOptions] makes for status and results.
 */
public data class FileOperationOptions(
    /** The reference [ProviderFiles.uploadFile] returned — provider id → the vendor's file id. */
    val file: Map<String, String>,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers for this call. Only applicable for HTTP-based providers. */
    val headers: Map<String, String>? = null,
)

/** The result of [ProviderFiles.uploadFile]. */
public data class FileUploadResult(
    /**
     * Provider id → the file id that vendor knows this upload by. This is the value a
     * [FileData.Reference] carries on every later turn.
     *
     * The key is the canonical provider id (`openai`), which may differ from the store's own
     * [ProviderFiles.provider] where a vendor spells its file store as a sub-id.
     */
    val providerReference: Map<String, String>,
    /** The media type the vendor recorded, when it reports one — not always what was declared. */
    val mediaType: String? = null,
    /** The filename the vendor recorded, when it reports one. */
    val filename: String? = null,
    /** The stored size in bytes, when the vendor reports it. */
    val byteSize: Long? = null,
    /** Epoch milliseconds at which the vendor created the file, when it says. */
    val createdAt: Long? = null,
    /**
     * Epoch milliseconds at which the vendor will delete the file — its retention expiry, or the TTL the
     * upload asked for — when it says.
     */
    val expiresAt: Long? = null,
    /** Provider-namespaced output carried verbatim; see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
)

/** The result of [ProviderFiles.getFileMetadata]. */
public data class FileMetadataResult(
    /**
     * Provider id → file id, for the ONE provider that answered. When working with a merged
     * multi-provider reference, do not replace it with this — merge this entry into it.
     */
    val providerReference: Map<String, String>,
    /** The filename the vendor holds, when it reports one. */
    val filename: String? = null,
    /** The media type the vendor holds, when it reports one. */
    val mediaType: String? = null,
    /** The stored size in bytes, when the vendor reports it. */
    val byteSize: Long? = null,
    /** Epoch milliseconds at which the vendor created the file, when it says. */
    val createdAt: Long? = null,
    /** Epoch milliseconds at which the vendor will delete the file, when it says. */
    val expiresAt: Long? = null,
    /** Provider-namespaced output carried verbatim; see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
)

/** The result of [ProviderFiles.downloadFile]. */
public class FileDownloadResult(
    /**
     * The file's bytes, as they arrive. Cold: collecting it reads the body, abandoning it reads nothing
     * — the consumer drains or cancels, as the reference's stream contract says.
     */
    public val content: Flow<ByteArray>,
    /** The media type the vendor served the content under, when it says. */
    public val mediaType: String? = null,
    /** Provider-namespaced output carried verbatim; see [ProviderMetadata]. */
    public val providerMetadata: ProviderMetadata? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    public val warnings: List<Warning> = emptyList(),
)

/** The result of [ProviderFiles.deleteFile]. */
public data class FileDeleteResult(
    /** Provider id → file id, for the ONE provider that answered — see [FileMetadataResult.providerReference]. */
    val providerReference: Map<String, String>,
    /** Whether the vendor confirmed the deletion. */
    val deleted: Boolean,
    /** Provider-namespaced output carried verbatim; see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
)
