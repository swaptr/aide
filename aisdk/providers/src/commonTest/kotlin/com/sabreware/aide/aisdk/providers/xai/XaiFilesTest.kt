package com.sabreware.aide.aisdk.providers.xai

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileOperationOptions
import com.sabreware.aide.aisdk.FileUploadContent
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * xAI's Files API, against the wire shapes of `xai-files.test.ts`.
 *
 * The multipart part NAMES are the assertions that matter: a file under the wrong field, or a
 * `team_id` that never went out, is a 200 that stored the wrong thing.
 */
class XaiFilesTest {

    private fun files(server: TestServer) = XaiProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-key",
    ).files()

    private fun upload(
        data: FileData = FileData.Bytes(byteArrayOf(1, 2, 3)),
        mediaType: String = "application/octet-stream",
        filename: String? = null,
        vendor: JsonObject? = null,
    ) = FileUploadOptions(
        data = data,
        mediaType = mediaType,
        filename = filename,
        providerOptions = vendor?.let { mapOf(XAI_PROVIDER_ID to it) },
    )

    @Test
    fun `an upload is a multipart POST to files`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))

        files(server).uploadFile(upload())

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/files", call.path)
        call.assertHeader("Authorization", "Bearer test-key")
        // The reference's Blob default: an unnamed upload goes out as `blob`.
        call.assertMultipartFile("file", fileName = "blob", contentType = "application/octet-stream")
    }

    @Test
    fun `the result is the reference a prompt sends from now on`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.FILE_UPLOADED.replace("file-abc123", "file-xyz789")),
        )

        val result = files(server).uploadFile(upload())

        assertEquals(mapOf(XAI_PROVIDER_ID to "file-xyz789"), result.providerReference)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `the response's metadata rides under the xai key`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED_CSV))

        val result = files(server).uploadFile(upload())

        assertEquals(
            buildJsonObject {
                put("filename", "data.csv")
                put("bytes", 512)
                put("createdAt", 1700000000)
            },
            result.providerMetadata?.get(XAI_PROVIDER_ID),
        )
        // The vendor's recorded name wins over the (absent) declared one; the media type is ours.
        assertEquals("data.csv", result.filename)
        assertEquals("application/octet-stream", result.mediaType)
    }

    @Test
    fun `a caller's filename names the part`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))

        files(server).uploadFile(upload(filename = "custom-name.pdf"))

        server.request().assertMultipartFile("file", fileName = "custom-name.pdf")
    }

    @Test
    fun `teamId goes out as team_id, and only when given`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))
        val files = files(server)

        files.uploadFile(upload(vendor = buildJsonObject { put("teamId", "team-123") }))
        files.uploadFile(upload())

        server.request(0).assertMultipartField("team_id", "team-123")
        assertNull(server.request(1).multipart["team_id"])
    }

    @Test
    fun `text data is uploaded as its bytes`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))

        // The reference's base64 `dGVzdA==` is the bytes of "test".
        files(server).uploadFile(upload(data = FileData.Text("test")))

        val call = server.request()
        call.assertMultipartFile("file", fileName = "blob")
        assertTrue("\r\n\r\ntest\r\n" in call.bodyText, "the file part carries the text's bytes")
    }

    @Test
    fun `null response fields are omitted from the metadata`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED_NULLS))

        val result = files(server).uploadFile(upload())

        assertEquals(JsonObject(emptyMap()), result.providerMetadata?.get(XAI_PROVIDER_ID))
        assertNull(result.filename)
    }

    @Test
    fun `a URL or a reference is not an upload`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))
        val files = files(server)

        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(upload(data = FileData.Url("https://example.com/a.pdf")))
        }
        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(upload(data = FileData.Reference(mapOf("openai" to "file-1"))))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the store reports the provider id`() {
        val files = files(TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED)))

        assertEquals(XAI_PROVIDER_ID, files.provider)
    }

    // --- f1513f0: expiry TTLs, streaming uploads, metadata, download, delete --------------------------

    private fun reference(id: String = "file-abc123") = FileOperationOptions(file = mapOf(XAI_PROVIDER_ID to id))

    @Test
    fun `expires_after goes out flat, and BEFORE the file part`() = runTest {
        val server = TestServer(
            TestServer.json(XaiBatchFixtures.FILE_UPLOADED.replace("\"filename\":\"upload\"", "\"filename\":\"upload\",\"expires_at\":1234740690")),
        )

        val result = files(server).uploadFile(upload(vendor = buildJsonObject { put("expiresAfter", 172_800) }))

        val call = server.request()
        call.assertMultipartField("expires_after", "172800")
        // xAI reads the field only if it precedes the file; after it, the upload is stored without a TTL.
        assertTrue(call.bodyText.indexOf("name=\"expires_after\"") < call.bodyText.indexOf("name=\"file\""))
        assertEquals(1_234_740_690L, result.providerMetadata?.get(XAI_PROVIDER_ID)?.get("expiresAt")?.jsonPrimitive?.content?.toLong())
        assertEquals(1_234_740_690_000L, result.expiresAt)
    }

    @Test
    fun `no expiry requested means no expires_after field`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))

        files(server).uploadFile(upload())

        assertNull(server.request().multipart["expires_after"])
    }

    @Test
    fun `a streamed upload sends expiry, team and then the file`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED.replace("file-abc123", "file-stream1")))

        val result = files(server).uploadFile(
            FileUploadOptions(
                content = FileUploadContent.Stream(
                    flowOf(("""{"a":1}""" + "\n").encodeToByteArray(), ("""{"b":2}""" + "\n").encodeToByteArray()),
                ),
                mediaType = "application/jsonl",
                filename = "batch.jsonl",
                providerOptions = mapOf(
                    XAI_PROVIDER_ID to buildJsonObject {
                        put("expiresAfter", 172_800)
                        put("teamId", "team-1")
                    },
                ),
            ),
        )

        assertEquals(mapOf(XAI_PROVIDER_ID to "file-stream1"), result.providerReference)
        val call = server.request()
        assertEquals("https://api.x.ai/v1/files", call.url)
        call.assertMultipartField("expires_after", "172800")
        call.assertMultipartField("team_id", "team-1")
        call.assertMultipartFile("file", fileName = "batch.jsonl", contentType = "application/jsonl")
        val body = call.bodyText
        assertTrue(body.indexOf("name=\"expires_after\"") < body.indexOf("name=\"team_id\""))
        assertTrue(body.indexOf("name=\"team_id\"") < body.indexOf("name=\"file\""))
        assertTrue("""{"a":1}""" + "\n" + """{"b":2}""" + "\n" in body)
    }

    @Test
    fun `an out-of-range or fractional expiry is refused before any request`() = runTest {
        val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))
        val files = files(server)

        listOf<Number>(100, 0.5, 3599.5, 2_592_001).forEach { ttl ->
            assertFailsWith<InvalidArgumentError>("expiresAfter $ttl") {
                files.uploadFile(upload(vendor = buildJsonObject { put("expiresAfter", ttl) }))
            }
        }
        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(
                FileUploadOptions(
                    content = FileUploadContent.Stream(flowOf("x".encodeToByteArray())),
                    mediaType = "application/jsonl",
                    providerOptions = mapOf(XAI_PROVIDER_ID to buildJsonObject { put("expiresAfter", 100) }),
                ),
            )
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `metadata is a GET on the file id`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"file-abc123","object":"file","bytes":1024,"created_at":1700000000,"expires_at":1700172800,""" +
                    """"filename":"test.jsonl"}""",
            ),
        )

        val result = files(server).getFileMetadata(reference())!!

        val call = server.request()
        assertEquals("https://api.x.ai/v1/files/file-abc123", call.url)
        assertEquals("GET", call.method)
        assertEquals(mapOf(XAI_PROVIDER_ID to "file-abc123"), result.providerReference)
        assertEquals(1024L, result.byteSize)
        assertEquals(1_700_000_000_000L, result.createdAt)
        assertEquals(1_700_172_800_000L, result.expiresAt)
    }

    @Test
    fun `a blank, foreign or dot-segment file id never reaches the wrong path`() = runTest {
        val files = files(TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED)))
        listOf("", "   ").forEach { id ->
            val error = assertFailsWith<InvalidArgumentError> { files.getFileMetadata(reference(id)) }
            assertEquals("file reference is missing an 'xai' file id.", error.message)
        }
        assertFailsWith<InvalidArgumentError> {
            files.getFileMetadata(FileOperationOptions(file = mapOf("openai" to "file-abc123")))
        }

        listOf("." to "https://api.x.ai/v1/files/%252E", ".." to "https://api.x.ai/v1/files/%252E%252E").forEach { (id, url) ->
            val server = TestServer(TestServer.json(XaiBatchFixtures.FILE_UPLOADED))
            files(server).getFileMetadata(reference(id))
            assertEquals(url, server.request().url)
        }
    }

    @Test
    fun `a download is a GET on the content path, streamed`() = runTest {
        val server = TestServer(
            TestServer.bytes(("""{"result":"ok"}""" + "\n").encodeToByteArray(), "application/octet-stream"),
        )

        val result = files(server).downloadFile(reference())!!
        val content = result.content.toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }.decodeToString()

        val call = server.request()
        assertEquals("https://api.x.ai/v1/files/file-abc123/content", call.url)
        assertEquals("GET", call.method)
        assertEquals("""{"result":"ok"}""" + "\n", content)
    }

    @Test
    fun `a delete is a DELETE on the file id`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"file-abc123","object":"file","deleted":true}"""))

        val result = files(server).deleteFile(reference())!!

        val call = server.request()
        assertEquals("https://api.x.ai/v1/files/file-abc123", call.url)
        assertEquals("DELETE", call.method)
        assertTrue(result.deleted)
        assertEquals(mapOf(XAI_PROVIDER_ID to "file-abc123"), result.providerReference)
    }
}
