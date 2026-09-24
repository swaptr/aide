package com.sabreware.aide.aisdk.providers.deepseek

import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.FileUploadOptions
import com.sabreware.aide.aisdk.FileUploadResult
import com.sabreware.aide.aisdk.InvalidArgumentError
import com.sabreware.aide.aisdk.InvalidResponseDataError
import com.sabreware.aide.aisdk.providers.testing.TestServer
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DeepSeek's Files API, against `deepseek-files.test.ts`.
 *
 * Two families of assertion: the multipart the vendor reads (`purpose`, the bracketed expiry pair, the
 * file's name and type), and the validation that runs BEFORE any of it — every rejection below is
 * asserted to have made zero requests, because the point of validating locally is the transfer saved.
 */
class DeepSeekFilesTest {

    private fun files(server: TestServer, headers: Map<String, String> = emptyMap()) = DeepSeekProvider(
        client = HttpClient(server.engine()),
        apiKey = "test-api-key",
        extraHeaders = headers,
    ).files()

    private fun upload(
        data: FileData = FileData.Bytes(byteArrayOf(1, 2, 3)),
        mediaType: String = "image/png",
        filename: String? = null,
        vendor: JsonObject? = null,
    ) = FileUploadOptions(
        data = data,
        mediaType = mediaType,
        filename = filename,
        providerOptions = vendor?.let { mapOf(DEEPSEEK_PROVIDER_ID to it) },
    )

    // --- The wire ------------------------------------------------------------------------------------

    @Test
    fun `an image is uploaded under the user_data purpose`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))

        files(server).uploadFile(upload(filename = "comic-cat.png"))

        val call = server.request()
        assertEquals("POST", call.method)
        assertEquals("files", call.path)
        call.assertMultipartField("purpose", "user_data")
        call.assertMultipartFile("file", fileName = "comic-cat.png", contentType = "image/png")
    }

    @Test
    fun `the result is the reference plus everything the vendor recorded`() = runTest {
        val server = TestServer(
            TestServer.json(DeepSeekFilesFixtures.uploaded(id = "file-api-xyz789", expiresAt = 1700003600)),
        )

        val result = files(server).uploadFile(upload(filename = "comic-cat.png"))

        assertEquals(
            FileUploadResult(
                providerReference = mapOf(DEEPSEEK_PROVIDER_ID to "file-api-xyz789"),
                filename = "comic-cat.png",
                mediaType = "image/png",
                providerMetadata = mapOf(
                    DEEPSEEK_PROVIDER_ID to buildJsonObject {
                        put("object", "file")
                        put("filename", "comic-cat.png")
                        put("purpose", "user_data")
                        put("bytes", 1024)
                        put("createdAt", 1700000000)
                        put("expiresAt", 1700003600)
                    },
                ),
            ),
            result,
        )
    }

    @Test
    fun `omitted or null metadata is tolerated, and the request's filename stands in`() = runTest {
        for (body in listOf(DeepSeekFilesFixtures.UPLOADED_OMITTED, DeepSeekFilesFixtures.UPLOADED_NULLS)) {
            val server = TestServer(TestServer.json(body))

            val result = files(server).uploadFile(upload(filename = "request-filename.png"))

            assertEquals(
                FileUploadResult(
                    providerReference = mapOf(DEEPSEEK_PROVIDER_ID to "file-api-incomplete"),
                    filename = "request-filename.png",
                    mediaType = "image/png",
                    providerMetadata = mapOf(DEEPSEEK_PROVIDER_ID to JsonObject(emptyMap())),
                ),
                result,
                body,
            )
        }
    }

    @Test
    fun `a field the vendor documents as fixed is refused when it is not`() = runTest {
        val cases = listOf(
            "object" to "\"document\"",
            "purpose" to "\"assistants\"",
            "bytes" to "-1",
            "bytes" to "1.5",
            "created_at" to "-1",
            "created_at" to "1.5",
            "expires_at" to "-1",
            "expires_at" to "1.5",
            "filename" to "123",
        )
        for ((field, value) in cases) {
            val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploadedWith(field, value)))

            val error = assertFailsWith<InvalidResponseDataError>("$field = $value") {
                files(server).uploadFile(upload())
            }

            // The message names the field, which is what the reference's TypeValidationError carries.
            assertTrue("\"$field\"" in error.message.orEmpty(), "$field = $value: ${error.message}")
        }
    }

    @Test
    fun `a response without a file id is no upload at all`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.UPLOADED_NO_ID))

        val error = assertFailsWith<InvalidResponseDataError> { files(server).uploadFile(upload()) }

        assertTrue("\"id\"" in error.message.orEmpty())
    }

    @Test
    fun `expiresAfter goes out as the bracketed pair, and only when given`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))
        val files = files(server)

        files.uploadFile(upload(vendor = buildJsonObject { put("expiresAfter", 3600) }))
        files.uploadFile(upload())

        val withExpiry = server.request(0)
        withExpiry.assertMultipartField("expires_after[anchor]", "created_at")
        withExpiry.assertMultipartField("expires_after[seconds]", "3600")
        assertNull(withExpiry.multipart["expires_after"])
        val without = server.request(1)
        assertNull(without.multipart["expires_after[anchor]"])
        assertNull(without.multipart["expires_after[seconds]"])
    }

    @Test
    fun `an expiry outside one hour to thirty days is refused before the upload`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))

        val error = assertFailsWith<InvalidArgumentError> {
            files(server).uploadFile(upload(vendor = buildJsonObject { put("expiresAfter", 3599) }))
        }

        assertEquals("expiresAfter", error.argument)
        assertEquals(0, server.callCount)
    }

    @Test
    fun `authentication and custom headers ride every upload`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))

        files(server, headers = mapOf("Custom-Header" to "custom-value")).uploadFile(upload())

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Custom-Header", "custom-value")
    }

    @Test
    fun `text data is uploaded as its bytes`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))

        // The reference's `btoa('image bytes')`.
        val result = files(server).uploadFile(upload(data = FileData.Text("image bytes")))

        assertEquals(mapOf(DEEPSEEK_PROVIDER_ID to "file-api-abc123"), result.providerReference)
        assertTrue("\r\n\r\nimage bytes\r\n" in server.request().bodyText)
    }

    // --- What is accepted ----------------------------------------------------------------------------

    @Test
    fun `each of the four formats is accepted on its bytes`() = runTest {
        val cases = listOf(
            Triple(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg", "image.jpeg"),
            Triple(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png", "image.png"),
            Triple(byteArrayOf(0x47, 0x49, 0x46), "image/gif", "image.gif"),
            Triple("RIFF    WEBP".encodeToByteArray(), "image/webp", "image.webp"),
        )
        for ((bytes, mediaType, filename) in cases) {
            val server = TestServer(TestServer.json(DeepSeekFilesFixtures.UPLOADED_IMAGE))

            files(server).uploadFile(upload(data = FileData.Bytes(bytes), mediaType = mediaType, filename = filename))

            assertEquals(1, server.callCount, mediaType)
        }
    }

    @Test
    fun `the image jpg alias is accepted and sent as declared`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.UPLOADED_IMAGE))

        val result = files(server).uploadFile(
            upload(
                data = FileData.Bytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())),
                mediaType = "image/jpg",
                filename = "image.jpg",
            ),
        )

        server.request().assertMultipartFile("file", fileName = "image.jpg", contentType = "image/jpg")
        assertEquals("image/jpg", result.mediaType)
    }

    @Test
    fun `a generic media type is accepted on the strength of the filename`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.UPLOADED_IMAGE))

        files(server).uploadFile(upload(mediaType = "application/octet-stream", filename = "image.PNG"))

        assertEquals(1, server.callCount)
    }

    @Test
    fun `a generic media type is accepted on the strength of the bytes`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.UPLOADED_IMAGE))

        // The reference's `btoa('GIF89a')`: a GIF header and nothing else.
        files(server).uploadFile(upload(data = FileData.Text("GIF89a"), mediaType = "application/octet-stream"))

        assertEquals(1, server.callCount)
    }

    @Test
    fun `the size and filename ceilings are inclusive`() {
        // At the limit, in both dimensions — validated directly, because a 64 MiB multipart body
        // through the recording harness proves nothing the validator does not.
        val atSizeLimit = ByteArray(64 * 1024 * 1024).also { byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).copyInto(it) }
        validateDeepSeekUpload(atSizeLimit, "image/png", "image.png")
        validateDeepSeekUpload(byteArrayOf(1, 2, 3), "image/png", "a".repeat(508) + ".png")
    }

    // --- What is refused, and refused BEFORE a request -----------------------------------------------

    @Test
    fun `an unsupported declaration, content, size or filename is refused without a request`() = runTest {
        data class Case(val name: String, val options: FileUploadOptions, val argument: String, val message: String)

        val cases = listOf(
            Case(
                "unsupported media type",
                upload(mediaType = "text/plain", filename = "notes.txt"),
                "mediaType",
                "Received unsupported media type \"text/plain\".",
            ),
            Case(
                "unsupported detected file content",
                // The reference sniffs `%PDF` on four bytes; this port's detector wants the fifth `-`
                // that every real PDF carries, so the fixture is the full signature.
                upload(data = FileData.Bytes("%PDF-".encodeToByteArray()), filename = "image.png"),
                "data",
                "Detected unsupported file content type \"application/pdf\".",
            ),
            Case(
                "file larger than 64 MiB",
                upload(data = FileData.Bytes(ByteArray(64 * 1024 * 1024 + 1)), filename = "image.png"),
                "data",
                "Received 67,108,865 bytes.",
            ),
            Case(
                "filename longer than 512 characters",
                upload(filename = "a".repeat(509) + ".png"),
                "filename",
                "Received 513 characters.",
            ),
            Case(
                "undetectable content without a supported filename",
                upload(mediaType = "application/octet-stream"),
                "mediaType",
                "Provide a supported media type or a filename ending in .jpg, .jpeg, .png, .gif, or .webp.",
            ),
        )
        for (case in cases) {
            val server = TestServer(TestServer.json(DeepSeekFilesFixtures.UPLOADED_IMAGE))

            val error = assertFailsWith<InvalidArgumentError>(case.name) { files(server).uploadFile(case.options) }

            assertEquals(case.argument, error.argument, case.name)
            assertTrue(case.message in error.message.orEmpty(), "${case.name}: ${error.message}")
            assertEquals(0, server.callCount, case.name)
        }
    }

    @Test
    fun `a URL or a reference is not an upload`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))
        val files = files(server)

        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(upload(data = FileData.Url("https://example.com/cat.png")))
        }
        assertFailsWith<InvalidArgumentError> {
            files.uploadFile(upload(data = FileData.Reference(mapOf("openai" to "file-1"))))
        }
        assertEquals(0, server.callCount)
    }

    @Test
    fun `the store reports the provider id`() {
        val files = files(TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded())))

        assertEquals(DEEPSEEK_PROVIDER_ID, files.provider)
    }

    @Test
    fun `the call's own headers ride the upload beside the provider's`() = runTest {
        val server = TestServer(TestServer.json(DeepSeekFilesFixtures.uploaded()))

        files(server, headers = mapOf("Provider-Header" to "provider"))
            .uploadFile(upload().copy(headers = mapOf("Operation-Header" to "operation")))

        val call = server.request()
        call.assertHeader("Authorization", "Bearer test-api-key")
        call.assertHeader("Provider-Header", "provider")
        call.assertHeader("Operation-Header", "operation")
    }
}
