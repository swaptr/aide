package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.DownloadError
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.UserPart
import com.sabreware.aide.aisdk.providers.testing.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * The reference's `download-tool-result-files.test.ts`: which files are fetched, how large one may be,
 * and that they are fetched one at a time.
 */
class GoogleToolResultFilesTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    private val sevenMiB = 7L * 1024 * 1024

    private fun toolResultPrompt(vararg urls: String): Prompt = listOf(
        ModelMessage.Tool(
            listOf(
                ToolPart.Result(
                    toolCallId = "tool-call-id",
                    toolName = "get-image",
                    output = ToolOutput.Multipart(
                        urls.map { ToolOutput.Multipart.Item.File(FileData.Url(it), mediaType = "image") },
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `downloads remote files in tool results without changing user files`() = runTest {
        val server = TestServer(TestServer.bytes(jpeg, contentType = "image/jpeg"))
        val prompt = listOf(
            ModelMessage.User(listOf(UserPart.File(FileData.Url("https://example.com/user-image.png"), "image/png"))),
        ) + toolResultPrompt("https://example.com/tool-image.jpg")

        val result = prompt.downloadToolResultFiles(server.http(), maxBytes = sevenMiB)

        // The user's URL is the model's own to fetch; only the tool's was downloaded.
        assertEquals(listOf("https://example.com/tool-image.jpg"), server.calls.map { it.url })
        assertEquals(prompt[0], result[0])
        val output = ((result[1] as ModelMessage.Tool).content.single() as ToolPart.Result).output
        assertEquals(
            ToolOutput.Multipart(listOf(ToolOutput.Multipart.Item.File(FileData.Bytes(jpeg), mediaType = "image/jpeg"))),
            output,
        )
    }

    @Test
    fun `limits the size of each download`() = runTest {
        val server = TestServer(TestServer.bytes(jpeg))

        assertFailsWith<DownloadError> {
            toolResultPrompt("https://example.com/tool-image.jpg").downloadToolResultFiles(server.http(), maxBytes = 2)
        }
    }

    @Test
    fun `downloads files sequentially, in prompt order`() = runTest {
        val server = TestServer(TestServer.bytes(jpeg), TestServer.bytes(jpeg))

        toolResultPrompt("https://example.com/tool-image-1.jpg", "https://example.com/tool-image-2.jpg")
            .downloadToolResultFiles(server.http(), maxBytes = sevenMiB)

        assertEquals(
            listOf("https://example.com/tool-image-1.jpg", "https://example.com/tool-image-2.jpg"),
            server.calls.map { it.url },
        )
    }
}
