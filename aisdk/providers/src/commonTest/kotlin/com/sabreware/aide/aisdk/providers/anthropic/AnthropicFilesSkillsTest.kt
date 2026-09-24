package com.sabreware.aide.aisdk.providers.anthropic

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileOperationOptions
import com.sabreware.aide.aisdk.FileUploadContent
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.NoSuchProviderReferenceError
import com.sabreware.aide.aisdk.SkillFile
import com.sabreware.aide.aisdk.SkillUploadOptions
import com.sabreware.aide.aisdk.providers.testing.TestServer
import com.sabreware.aide.aisdk.providers.testing.string
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The Files and Skills APIs — the mint for [FileData.Reference], ported from the reference's own two
 * test files (`anthropic-files.test.ts`, `anthropic-skills.test.ts` with its recorded fixtures).
 */
class AnthropicFilesSkillsTest {

    private fun provider(server: TestServer) = AnthropicProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
    )

    // --- Files ---------------------------------------------------------------------------------------

    /** `anthropic-files.test.ts` `successfulResponse`, copied byte for byte. */
    private val fileResponse = """{"id":"file-abc123","type":"file","filename":"test.pdf",""" +
        """"mime_type":"application/pdf","size_bytes":12345,""" +
        """"created_at":"2025-04-14T12:00:00Z","downloadable":true}"""

    @Test
    fun `an upload is multipart to files, behind the files beta`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))

        provider(server).files().uploadFile(
            FileUploadOptions(
                data = FileData.Bytes(byteArrayOf(1, 2, 3)),
                mediaType = "application/octet-stream",
            ),
        )

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/files", call.path)
        // The beta header is the whole difference between this endpoint existing and a 404.
        call.assertHeader("anthropic-beta", "files-api-2025-04-14")
        call.assertHeader("x-api-key", "test-api-key")
        // The reference's Blob default: an unnamed upload goes out as `blob`.
        call.assertMultipartFile("file", fileName = "blob", contentType = "application/octet-stream")
    }

    @Test
    fun `a caller's filename names the part`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))

        provider(server).files().uploadFile(
            FileUploadOptions(
                data = FileData.Bytes(byteArrayOf(1, 2, 3)),
                mediaType = "application/pdf",
                filename = "custom-name.pdf",
            ),
        )

        server.request().assertMultipartFile("file", fileName = "custom-name.pdf")
    }

    @Test
    fun `the result is the reference a prompt sends from now on`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))

        val result = provider(server).files().uploadFile(
            FileUploadOptions(
                data = FileData.Text("hello"),
                mediaType = "text/plain",
            ),
        )

        // The id lands under the provider key — exactly the map FileData.Reference carries.
        assertEquals(mapOf(ANTHROPIC_PROVIDER_ID to "file-abc123"), result.providerReference)
        // The vendor's recorded identity wins over what was declared.
        assertEquals("application/pdf", result.mediaType)
        assertEquals("test.pdf", result.filename)
        val metadata = result.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)
        assertEquals("12345", metadata?.get("sizeBytes")?.toString())
        assertEquals("2025-04-14T12:00:00Z", metadata?.get("createdAt").string())
    }

    @Test
    fun `a URL or a reference is not an upload`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))
        val files = provider(server).files()

        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(
                FileUploadOptions(data = FileData.Url("https://example.com/a.pdf"), mediaType = "application/pdf"),
            )
        }
        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(
                FileUploadOptions(
                    data = FileData.Reference(mapOf("openai" to "file-1")),
                    mediaType = "application/pdf",
                ),
            )
        }
        assertEquals(0, server.callCount)
    }

    // --- Skills --------------------------------------------------------------------------------------

    /** `skills/__fixtures__/anthropic-skill-create.json`, copied byte for byte. */
    private val skillCreated = """{"id":"skill_01Xud7kLMsjLfc7Aa6RvigZf",""" +
        """"display_title":"Test Capture Skill","latest_version":"1772078378207930",""" +
        """"source":"custom","created_at":"2026-02-26T03:59:39.314772Z",""" +
        """"updated_at":"2026-02-26T03:59:39.314772Z"}"""

    /** `skills/__fixtures__/anthropic-skill-version-create.json`, copied byte for byte. */
    private val skillVersion = """{"type":"skill_version","skill_id":"skill_01Xud7kLMsjLfc7Aa6RvigZf",""" +
        """"id":"skill_version_01GD1pqKAjok68MJxg1f9bTA","version":"1772078380123708",""" +
        """"directory":"test-capture-skill","name":"test-capture-skill",""" +
        """"description":"An updated test skill for fixture capture",""" +
        """"created_at":"2026-02-26T03:59:41.132754Z"}"""

    @Test
    fun `one upload is two requests, because the name lives on the minted version`() = runTest {
        val server = TestServer(TestServer.json(skillCreated), TestServer.json(skillVersion))

        val result = provider(server).skills().uploadSkill(
            SkillUploadOptions(
                files = listOf(
                    SkillFile("SKILL.md", FileData.Text("# capture")),
                    SkillFile("scripts/run.py", FileData.Bytes(byteArrayOf(35, 33))),
                ),
                displayTitle = "Test Capture Skill",
            ),
        )

        val upload = server.request(0)
        assertEquals("v1/skills", upload.path)
        upload.assertHeader("anthropic-beta", "skills-2025-10-02")
        upload.assertMultipartField("display_title", "Test Capture Skill")
        // Each file is a `files[]` part whose FILENAME is its path inside the skill — the repeated
        // field name the single-file multipart verb could not say.
        assertTrue("filename=\"SKILL.md\"" in upload.bodyText)
        assertTrue("filename=\"scripts/run.py\"" in upload.bodyText)

        // The version fetch, and the URL says which version the upload minted.
        val versionFetch = server.request(1)
        assertEquals(
            "v1/skills/skill_01Xud7kLMsjLfc7Aa6RvigZf/versions/1772078378207930",
            versionFetch.path,
        )
        versionFetch.assertHeader("anthropic-beta", "skills-2025-10-02")

        assertEquals(mapOf(ANTHROPIC_PROVIDER_ID to "skill_01Xud7kLMsjLfc7Aa6RvigZf"), result.providerReference)
        assertEquals("Test Capture Skill", result.displayTitle)
        // Name and description come from the VERSION response — the upload response has neither.
        assertEquals("test-capture-skill", result.name)
        assertEquals("An updated test skill for fixture capture", result.description)
        assertEquals("1772078378207930", result.latestVersion)
        assertEquals(
            "custom",
            result.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("source").string(),
        )
    }

    @Test
    fun `a response with no latest version skips the version fetch`() = runTest {
        val bare = """{"id":"skill_x","source":"custom",""" +
            """"created_at":"2026-02-26T00:00:00Z","updated_at":"2026-02-26T00:00:00Z"}"""
        val server = TestServer(TestServer.json(bare))

        val result = provider(server).skills().uploadSkill(
            SkillUploadOptions(files = listOf(SkillFile("SKILL.md", FileData.Text("x")))),
        )

        assertEquals(1, server.callCount)
        assertEquals(null, result.name)
        assertEquals(null, result.latestVersion)
    }

    // --- The 2026-09 upstream delta (reference `5190b67`) -----------------------------------------

    private val fileRef = FileOperationOptions(file = mapOf(ANTHROPIC_PROVIDER_ID to "file-abc123"))

    @Test
    fun `per-call headers reach the upload`() = runTest {
        // "threads per-call headers and abortSignal" — the signal half is coroutine cancellation here.
        val server = TestServer(TestServer.json(fileResponse))

        provider(server).files().uploadFile(
            FileUploadOptions(
                data = FileData.Bytes(byteArrayOf(1, 2, 3)),
                mediaType = "application/octet-stream",
                headers = mapOf("x-request-id" to "req-1"),
            ),
        )

        server.request().assertHeader("x-request-id", "req-1")
        server.request().assertHeader("anthropic-beta", "files-api-2025-04-14")
    }

    @Test
    fun `the upload result carries size and creation time as numbers`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))

        val result = provider(server).files().uploadFile(
            FileUploadOptions(data = FileData.Text("hello"), mediaType = "text/plain"),
        )

        assertEquals(12345L, result.byteSize)
        // 2025-04-14T12:00:00Z; the vendor sets no expiry on an upload.
        assertEquals(1_744_632_000_000L, result.createdAt)
        assertNull(result.expiresAt)
    }

    @Test
    fun `a streamed upload goes out as the same multipart part`() = runTest {
        // The reference's Anthropic store refuses a stream; the wire is identical, so this one sends it.
        val server = TestServer(TestServer.json(fileResponse))

        val result = provider(server).files().uploadFile(
            FileUploadOptions(
                content = FileUploadContent.Stream(flowOf(byteArrayOf(1, 2), byteArrayOf(3)), byteSize = 3),
                mediaType = "application/octet-stream",
                filename = "streamed.bin",
            ),
        )

        val call = server.request()
        assertEquals("v1/files", call.path)
        call.assertHeader("anthropic-beta", "files-api-2025-04-14")
        call.assertMultipartFile("file", fileName = "streamed.bin", contentType = "application/octet-stream")
        assertEquals(mapOf(ANTHROPIC_PROVIDER_ID to "file-abc123"), result.providerReference)
    }

    @Test
    fun `metadata is read behind the files beta`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))

        val result = assertNotNull(provider(server).files().getFileMetadata(fileRef))

        val call = server.request()
        assertEquals("GET", call.method)
        assertEquals("v1/files/file-abc123", call.path)
        call.assertHeader("anthropic-beta", "files-api-2025-04-14")
        assertEquals(mapOf(ANTHROPIC_PROVIDER_ID to "file-abc123"), result.providerReference)
        assertEquals("test.pdf", result.filename)
        assertEquals("application/pdf", result.mediaType)
        assertEquals(12345L, result.byteSize)
        assertEquals(1_744_632_000_000L, result.createdAt)
        assertEquals("true", result.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("downloadable")?.toString())
    }

    @Test
    fun `a download streams the content endpoint`() = runTest {
        val server = TestServer(TestServer.bytes(byteArrayOf(0x25, 0x50, 0x44, 0x46), "application/pdf"))

        val download = assertNotNull(provider(server).files().downloadFile(fileRef))
        val bytes = download.content.toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }

        val call = server.request()
        assertEquals("GET", call.method)
        assertEquals("v1/files/file-abc123/content", call.path)
        call.assertHeader("anthropic-beta", "files-api-2025-04-14")
        call.assertHeader("x-api-key", "test-api-key")
        assertTrue(bytes.contentEquals(byteArrayOf(0x25, 0x50, 0x44, 0x46)))
    }

    @Test
    fun `a delete is confirmed`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"file-abc123","type":"file_deleted"}"""))

        val result = assertNotNull(provider(server).files().deleteFile(fileRef))

        val call = server.request()
        assertEquals("DELETE", call.method)
        assertEquals("v1/files/file-abc123", call.path)
        call.assertHeader("anthropic-beta", "files-api-2025-04-14")
        assertTrue(result.deleted)
        assertEquals(mapOf(ANTHROPIC_PROVIDER_ID to "file-abc123"), result.providerReference)
        assertEquals("file_deleted", result.providerMetadata?.get(ANTHROPIC_PROVIDER_ID)?.get("type").string())
    }

    @Test
    fun `a reference this provider is not in is refused, typed`() = runTest {
        val server = TestServer(TestServer.json(fileResponse))

        assertFailsWith<NoSuchProviderReferenceError> {
            provider(server).files().getFileMetadata(FileOperationOptions(file = mapOf("openai" to "file-1")))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the timestamp parser reads RFC 3339 and nothing else`() {
        assertEquals(1_744_632_000_000L, isoToEpochMillis("2025-04-14T12:00:00Z"))
        assertEquals(1_744_632_000_500L, isoToEpochMillis("2025-04-14T12:00:00.5Z"))
        assertEquals(1_744_632_000_000L, isoToEpochMillis("2025-04-14T14:00:00+02:00"))
        assertEquals(0L, isoToEpochMillis("1970-01-01T00:00:00Z"))
        assertNull(isoToEpochMillis("1744632000"))
    }
}
