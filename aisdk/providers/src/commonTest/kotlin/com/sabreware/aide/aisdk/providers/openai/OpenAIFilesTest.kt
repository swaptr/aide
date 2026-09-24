package com.sabreware.aide.aisdk.providers.openai

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileOperationOptions
import com.sabreware.aide.aisdk.FileUploadContent
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.SPECIFICATION_VERSION
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.sabreware.aide.aisdk.util.parseJsonObject

/**
 * The Files API — the mint for a [FileData.Reference] on OpenAI — against the wire shapes of
 * `files/openai-files.test.ts`.
 *
 * The two things worth pinning are both form-encoding facts a JSON-minded reader gets wrong: the
 * required `purpose` field that the caller usually never sets, and the bracketed `expires_after[…]`
 * pair that is the only way a form spells a nested object.
 */
class OpenAIFilesTest {

    private fun files(server: TestServer, extraHeaders: Map<String, String> = emptyMap()) =
        OpenAIProvider(
            client = HttpClient(server.engine()),
            apiKey = "test-api-key",
            extraHeaders = extraHeaders,
        ).files()

    private fun upload(
        purpose: String? = "assistants",
        expiresAfter: Int? = null,
        filename: String? = null,
        data: FileData = FileData.Bytes(byteArrayOf(1, 2, 3)),
    ) = FileUploadOptions(
        data = data,
        mediaType = "application/octet-stream",
        filename = filename,
        providerOptions = if (purpose == null && expiresAfter == null) {
            null
        } else {
            mapOf(
                OPENAI_PROVIDER_ID to buildJsonObject {
                    purpose?.let { put("purpose", it) }
                    expiresAfter?.let { put("expiresAfter", it) }
                },
            )
        },
    )

    @Test
    fun `an upload is multipart to files, with the purpose beside the bytes`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(server).uploadFile(upload())

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("v1/files", call.path)
        call.assertMultipartField("purpose", "assistants")
        // The reference's Blob default: an unnamed upload goes out as `blob`, typed as declared.
        call.assertMultipartFile("file", fileName = "blob", contentType = "application/octet-stream")
    }

    @Test
    fun `purpose defaults to assistants when the caller says nothing`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(server).uploadFile(upload(purpose = null))

        // The API requires the field; a caller who only wants a file id should not have to know that.
        server.request().assertMultipartField("purpose", "assistants")
    }

    @Test
    fun `a caller's purpose and filename are sent as given`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(server).uploadFile(upload(purpose = "batch", filename = "custom-name.csv"))

        val call = server.request()
        call.assertMultipartField("purpose", "batch")
        call.assertMultipartFile("file", fileName = "custom-name.csv")
    }

    @Test
    fun `the result is the reference a prompt sends from now on`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse(id = "file-xyz789")))

        val result = files(server).uploadFile(upload())

        assertEquals(mapOf(OPENAI_PROVIDER_ID to "file-xyz789"), result.providerReference)
        // The vendor's recorded filename wins; the media type is what was declared, as the reference does.
        assertEquals("test.csv", result.filename)
        assertEquals("application/octet-stream", result.mediaType)
    }

    @Test
    fun `the vendor's record of the upload rides providerMetadata, nulls omitted`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        val result = files(server).uploadFile(upload())

        // `expires_at` was null in the response and is therefore absent, not `"expiresAt":null`.
        assertEquals(
            """{"filename":"test.csv","purpose":"assistants","bytes":1024,"createdAt":1700000000,""" +
                """"status":"processed"}""",
            result.providerMetadata?.get(OPENAI_PROVIDER_ID).toString(),
        )
    }

    @Test
    fun `expiresAfter goes out as the bracketed pair a form needs`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(server).uploadFile(upload(expiresAfter = 3600))

        val call = server.request()
        call.assertMultipartField("expires_after[anchor]", "created_at")
        call.assertMultipartField("expires_after[seconds]", "3600")
        assertTrue("expires_after" !in call.multipart, "no flat expires_after field")
    }

    @Test
    fun `no expiry requested means no expiry fields at all`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(server).uploadFile(upload())

        val fields = server.request().multipart
        assertTrue("expires_after[anchor]" !in fields, "anchor must be absent: $fields")
        assertTrue("expires_after[seconds]" !in fields, "seconds must be absent: $fields")
    }

    @Test
    fun `the bearer token and the configured headers ride the upload`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(
            server,
            extraHeaders = mapOf(
                "OpenAI-Organization" to "test-org",
                "OpenAI-Project" to "test-project",
                "Custom-Header" to "custom-value",
            ),
        ).uploadFile(upload())

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("OpenAI-Organization", "test-org")
        call.assertHeader("OpenAI-Project", "test-project")
        call.assertHeader("Custom-Header", "custom-value")
    }

    @Test
    fun `text content is uploaded as its bytes`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        val result = files(server).uploadFile(upload(data = FileData.Text("hello world")))

        assertEquals(mapOf(OPENAI_PROVIDER_ID to "file-abc123"), result.providerReference)
        assertTrue("hello world" in server.request().bodyText)
    }

    @Test
    fun `a URL or a reference is not an upload`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))
        val files = files(server)

        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(upload(data = FileData.Url("https://example.com/a.csv")))
        }
        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(upload(data = FileData.Reference(mapOf("anthropic" to "file-1"))))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the store names its provider and specification`() {
        val files = files(TestServer(TestServer.json("{}")))

        assertEquals(OPENAI_PROVIDER_ID, files.provider)
        assertEquals(SPECIFICATION_VERSION, files.specificationVersion)
    }

    // --- 3fc40db: streaming uploads, result fields, metadata, download, delete ------------------------

    private fun streamUpload(
        vararg chunks: String,
        filename: String? = "batch.jsonl",
        expiresAfter: Int? = null,
    ) = FileUploadOptions(
        content = FileUploadContent.Stream(flowOf(*chunks.map { it.encodeToByteArray() }.toTypedArray())),
        mediaType = "application/jsonl",
        filename = filename,
        providerOptions = mapOf(
            OPENAI_PROVIDER_ID to buildJsonObject {
                put("purpose", "batch")
                expiresAfter?.let { put("expiresAfter", it) }
            },
        ),
    )

    private fun reference(id: String = "file-abc123") = FileOperationOptions(file = mapOf(OPENAI_PROVIDER_ID to id))

    /** `openai-files.test.ts` `prepareRetrieveResponse`. */
    private fun retrieveResponse(expiresAt: String = "null") =
        """{"id":"file-abc123","object":"file","bytes":1024,"created_at":1700000000,"filename":"test.jsonl",""" +
            """"purpose":"batch","status":"processed","expires_at":$expiresAt}"""

    @Test
    fun `a streamed upload is multipart with the fields preceding the file part`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse(id = "file-stream1")))

        val result = files(server).uploadFile(streamUpload("""{"a":1}""" + "\n", """{"b":2}""" + "\n", expiresAfter = 172_800))

        assertEquals(mapOf(OPENAI_PROVIDER_ID to "file-stream1"), result.providerReference)
        val call = server.request()
        call.assertMultipartField("purpose", "batch")
        call.assertMultipartField("expires_after[anchor]", "created_at")
        call.assertMultipartField("expires_after[seconds]", "172800")
        call.assertMultipartFile("file", fileName = "batch.jsonl", contentType = "application/jsonl")
        assertTrue("""{"a":1}""" + "\n" + """{"b":2}""" + "\n" in call.bodyText, "the chunks arrive whole and in order")
        // Fields first, the file last: what the reference's stream path sends.
        assertTrue(call.bodyText.indexOf("name=\"purpose\"") < call.bodyText.indexOf("name=\"file\""))
    }

    @Test
    fun `a filename-less stream upload is named blob, and no expiry means no expiry fields`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))

        files(server).uploadFile(streamUpload("x", filename = null))

        val call = server.request()
        call.assertMultipartFile("file", fileName = "blob")
        assertTrue("expires_after[anchor]" !in call.multipart)
        assertTrue("expires_after[seconds]" !in call.multipart)
    }

    @Test
    fun `an upload result exposes the vendor's size and timestamps in millis`() = runTest {
        val server = TestServer(
            TestServer.json(
                """{"id":"file-exp1","object":"file","bytes":2048,"created_at":1700000000,"filename":"batch.jsonl",""" +
                    """"purpose":"batch","status":"processed","expires_at":1700172800}""",
            ),
        )

        val result = files(server).uploadFile(upload(purpose = "batch", expiresAfter = 172_800))

        assertEquals(2048L, result.byteSize)
        assertEquals(1_700_000_000_000L, result.createdAt)
        assertEquals(1_700_172_800_000L, result.expiresAt)
    }

    @Test
    fun `an invalid expiry is refused before any request, stream or not`() = runTest {
        val server = TestServer(TestServer.json(OpenAIFixtures.fileResponse()))
        val files = files(server)

        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(
                streamUpload("x").copy(
                    providerOptions = mapOf(OPENAI_PROVIDER_ID to buildJsonObject { put("expiresAfter", "not-a-number") }),
                ),
            )
        }
        assertFailsWith<InvalidArgumentError> { files.uploadFile(upload(expiresAfter = 100)) }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `metadata is a GET on the file id, every recorded field carried`() = runTest {
        val server = TestServer(TestServer.json(retrieveResponse(expiresAt = "1700172800")))

        val result = files(server).getFileMetadata(reference())!!

        val call = server.request()
        assertEquals("GET", call.method)
        assertEquals("v1/files/file-abc123", call.path)
        assertEquals(mapOf(OPENAI_PROVIDER_ID to "file-abc123"), result.providerReference)
        assertEquals("test.jsonl", result.filename)
        assertEquals(1024L, result.byteSize)
        assertEquals(1_700_000_000_000L, result.createdAt)
        assertEquals(1_700_172_800_000L, result.expiresAt)
        assertEquals(
            parseJsonObject(
                """{"filename":"test.jsonl","purpose":"batch","bytes":1024,"createdAt":1700000000,""" +
                    """"status":"processed","expiresAt":1700172800}""",
            ),
            result.providerMetadata?.get(OPENAI_PROVIDER_ID),
        )
    }

    @Test
    fun `a file with no expiry reports none`() = runTest {
        val server = TestServer(TestServer.json(retrieveResponse()))

        assertNull(files(server).getFileMetadata(reference())!!.expiresAt)
    }

    @Test
    fun `a blank, missing or foreign file id is refused before any request`() = runTest {
        val server = TestServer(TestServer.json(retrieveResponse()))
        val files = files(server)

        listOf("", "   ").forEach { id ->
            val error = assertFailsWith<InvalidArgumentError> { files.getFileMetadata(reference(id)) }
            assertEquals("file reference is missing an 'openai' file id.", error.message)
        }
        assertFailsWith<InvalidArgumentError> {
            files.getFileMetadata(FileOperationOptions(file = mapOf("other" to "file-abc123")))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `a dot-segment file id cannot climb out of the file store`() = runTest {
        listOf("." to "%252E", ".." to "%252E%252E").forEach { (id, encoded) ->
            val server = TestServer(TestServer.json("""{"id":"$id"}"""))

            files(server).getFileMetadata(reference(id))

            // Encoded twice: a URL parser turns a single %2E back into the dot segment it stands for.
            assertEquals("https://api.openai.com/v1/files/$encoded", server.request().url)
        }
    }

    @Test
    fun `a download is a GET on the content path, streamed`() = runTest {
        val server = TestServer(
            TestServer.bytes(("""{"result":"ok"}""" + "\n").encodeToByteArray(), "application/jsonl; charset=utf-8"),
        )

        val result = files(server).downloadFile(reference())!!
        val content = result.content.toList().fold(ByteArray(0)) { acc, chunk -> acc + chunk }.decodeToString()

        val call = server.request()
        assertEquals("GET", call.method)
        assertEquals("v1/files/file-abc123/content", call.path)
        assertEquals("""{"result":"ok"}""" + "\n", content)
    }

    @Test
    fun `a delete is a DELETE on the file id`() = runTest {
        val server = TestServer(TestServer.json("""{"id":"file-abc123","object":"file","deleted":true}"""))

        val result = files(server).deleteFile(reference())!!

        val call = server.request()
        assertEquals("DELETE", call.method)
        assertEquals("v1/files/file-abc123", call.path)
        assertTrue(result.deleted)
        assertEquals(mapOf(OPENAI_PROVIDER_ID to "file-abc123"), result.providerReference)
    }
}
