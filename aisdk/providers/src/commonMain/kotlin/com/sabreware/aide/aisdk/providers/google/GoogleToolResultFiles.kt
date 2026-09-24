package com.sabreware.aide.aisdk.providers.google

import com.sabreware.aide.aisdk.AssistantPart
import com.sabreware.aide.aisdk.FileData
import com.sabreware.aide.aisdk.ModelMessage
import com.sabreware.aide.aisdk.Prompt
import com.sabreware.aide.aisdk.ToolOutput
import com.sabreware.aide.aisdk.ToolPart
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.MediaType
import com.sabreware.aide.aisdk.util.ProviderHttp

/**
 * How far a tool result's remote files are fetched before the request goes out.
 *
 * Vertex function responses accept inline data and nothing else, so a tool that returned a URL to an
 * image would otherwise reach the model as a placeholder naming the media type. The reference
 * downloads each such file before conversion, capped per file at 7 MiB unless the host says otherwise;
 * the cap is the whole configuration, because the download has no other knob worth a caller's time.
 */
public data class ToolResultDownloads(
    /** The most one downloaded file may weigh; a larger one fails the request rather than the model. */
    val maxBytes: Long = DEFAULT_TOOL_RESULT_DOWNLOAD_BYTES,
)

/** The reference's per-file default, 7 MiB. */
public const val DEFAULT_TOOL_RESULT_DOWNLOAD_BYTES: Long = 7L * 1024 * 1024

/**
 * The prompt with every URL file in a tool result fetched and inlined.
 *
 * Only tool results are touched — a user's URL attachment is the model's own to fetch, and the language
 * model declares which hosts it will — and the downloads run one at a time, as the reference's do,
 * because a tool turn can name a dozen files and a burst of parallel fetches is how a caller's process
 * runs out of sockets. No credential rides the request: the URL is a tool's, not the API's, so
 * [ProviderHttp.getBytes] is called with no headers and no trusted origin, which is the guard that
 * keeps the API key off a host the tool named.
 *
 * The media type is resolved in the reference's order: the bytes are sniffed first, then the server's
 * `Content-Type` if the caller's was a bare top-level type, then the caller's own.
 */
internal suspend fun Prompt.downloadToolResultFiles(http: ProviderHttp, maxBytes: Long): Prompt = map { message ->
    when (message) {
        is ModelMessage.Assistant -> message.copy(
            content = message.content.map { part ->
                if (part is AssistantPart.ToolResult) part.copy(output = part.output.downloaded(http, maxBytes)) else part
            },
        )
        is ModelMessage.Tool -> message.copy(
            content = message.content.map { part ->
                if (part is ToolPart.Result) part.copy(output = part.output.downloaded(http, maxBytes)) else part
            },
        )
        else -> message
    }
}

private suspend fun ToolOutput.downloaded(http: ProviderHttp, maxBytes: Long): ToolOutput {
    if (this !is ToolOutput.Multipart) return this
    return copy(
        value = value.map { item ->
            val url = ((item as? ToolOutput.Multipart.Item.File)?.data as? FileData.Url)?.url
            if (item !is ToolOutput.Multipart.Item.File || url == null) return@map item
            val result = http.getBytes(url, maxBytes = maxBytes)
            item.copy(
                data = FileData.Bytes(result.value),
                mediaType = MediaType.detect(result.value, topLevelType = "image")
                    ?: result.contentType()?.takeIf { !item.mediaType.isFullMediaType() }
                    ?: item.mediaType,
            )
        },
    )
}

private fun HttpResult<*>.contentType(): String? =
    headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

/** `type/subtype` with a real subtype — not `image`, not `image/`, not a wildcard. */
private fun String.isFullMediaType(): Boolean {
    val subtype = substringAfter('/', missingDelimiterValue = "")
    return subtype.isNotEmpty() && subtype != "*"
}
