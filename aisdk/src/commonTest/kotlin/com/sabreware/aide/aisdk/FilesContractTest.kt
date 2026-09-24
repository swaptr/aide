package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * The files contract past `uploadFile`: three optional operations that read "absent" as null, a
 * streaming upload variant, and the funnel that turns it into one clear error at a store that cannot
 * stream.
 *
 * The reference marks the operations as optional methods whose presence signals support; the port says
 * the same with a null default, and a store that implements one never answers null. Pinned here rather
 * than in a provider test because the guarantee is the default's, and a provider test only ever sees
 * its own override.
 */
class FilesContractTest {

    /** A store that uploads and nothing else — the shape every provider had before the extension. */
    private class UploadOnlyFiles : ProviderFiles {
        override val provider: String = "vendor"
        override suspend fun uploadFile(options: FileUploadOptions): FileUploadResult =
            FileUploadResult(providerReference = mapOf(provider to "file-1"))
    }

    private val reference = FileOperationOptions(file = mapOf("vendor" to "file-1"))

    @Test
    fun `the optional operations answer null on a store that only uploads`() = runTest {
        val files = UploadOnlyFiles()

        assertNull(files.getFileMetadata(reference))
        assertNull(files.downloadFile(reference))
        assertNull(files.deleteFile(reference))
    }

    @Test
    fun `an inline upload is spelled as it always was`() {
        val options = FileUploadOptions(data = FileData.Text("hello"), mediaType = "text/plain", filename = "a.txt")

        assertEquals(FileUploadContent.Inline(FileData.Text("hello")), options.content)
        assertEquals(FileData.Text("hello"), options.data)
        assertNull(options.headers)
    }

    @Test
    fun `a stream surfaces as the reference's unsupported-streaming error at a store that reads inline bytes`() {
        val options = FileUploadOptions(
            content = FileUploadContent.Stream(flowOf("abc".encodeToByteArray()), byteSize = 3),
            mediaType = "application/octet-stream",
        )

        // A text-only store reads `data`, as every existing provider does; the stream is refused there,
        // before any request goes out, with the functionality the reference names.
        val error = assertFailsWith<UnsupportedFunctionalityError> { options.data }
        assertEquals("streaming file upload", error.functionality)
        assertIs<FileUploadContent.Stream>(options.content)
    }

    @Test
    fun `an upload result carries the vendor's size and timestamps`() {
        val result = FileUploadResult(
            providerReference = mapOf("vendor" to "file-1"),
            mediaType = "application/pdf",
            filename = "a.pdf",
            byteSize = 1_024,
            createdAt = 1_700_000_000_000,
            expiresAt = 1_700_086_400_000,
        )

        assertEquals(1_024, result.byteSize)
        assertEquals(1_700_000_000_000, result.createdAt)
        assertEquals(1_700_086_400_000, result.expiresAt)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `metadata, download and delete results carry what the reference's do`() = runTest {
        val metadata = FileMetadataResult(
            providerReference = mapOf("vendor" to "file-1"),
            filename = "a.pdf",
            mediaType = "application/pdf",
            byteSize = 1_024,
            createdAt = 1_700_000_000_000,
            expiresAt = 1_700_086_400_000,
        )
        val download = FileDownloadResult(content = flowOf("abc".encodeToByteArray()), mediaType = "text/plain")
        val delete = FileDeleteResult(providerReference = mapOf("vendor" to "file-1"), deleted = true)

        assertEquals("a.pdf", metadata.filename)
        assertEquals("text/plain", download.mediaType)
        assertEquals(true, delete.deleted)
    }
}
