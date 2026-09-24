package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.HttpResult
import com.sabreware.aide.aisdk.util.ProviderHttp
import com.sabreware.aide.aisdk.util.ProviderJson
import com.sabreware.aide.aisdk.util.SigV4
import com.sabreware.aide.aisdk.util.combineHeaders
import com.sabreware.aide.aisdk.util.parseJsonObject
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The signing scope every Bedrock endpoint shares — `bedrock-runtime` and `bedrock-agent-runtime` alike. */
internal const val BEDROCK_SIGNING_SERVICE: String = "bedrock"

/** The `InvokeModel` endpoint for [modelId] under [baseUrl]. The id is a path segment, so its `:` has to be escaped. */
internal fun bedrockInvokeUrl(baseUrl: String, modelId: String): String =
    "$baseUrl/model/${modelId.encodeURLParameter()}/invoke"

/**
 * A SigV4-signed single-shot POST whose JSON answer is buffered off the byte stream.
 *
 * Every non-chat Bedrock call is this shape — `InvokeModel` for embeddings and images, the agent
 * runtime's `rerank` — and the sequence has one trap worth keeping in a single place: the caller's
 * headers are signed ALONG WITH the request, because SigV4 covers the header set it names, so a header
 * added after signing is a 403 rather than an extra header.
 *
 * The body goes out through [ProviderHttp.postBytes] rather than `postJson` because the signature is
 * over the exact bytes that leave: the body is encoded once here, and that one array is both hashed
 * and sent. The chunks are joined BEFORE decoding — decoding each as it arrives would split a
 * multi-byte character that straddles two reads.
 */
internal suspend fun ProviderHttp.bedrockSignedPost(
    url: String,
    body: JsonObject,
    extraHeaders: Map<String, String>?,
    credentials: AwsCredentials,
    region: String,
    timestampMillis: Long,
): HttpResult<JsonObject> {
    val payload = ProviderJson.encodeToString(JsonElement.serializer(), body).encodeToByteArray()
    val callerHeaders = combineHeaders(mapOf("content-type" to "application/json"), extraHeaders)
    val signed = SigV4.signedHeaders(
        method = "POST",
        url = url,
        headers = callerHeaders,
        payload = payload,
        credentials = credentials,
        region = region,
        service = BEDROCK_SIGNING_SERVICE,
        timestampMillis = timestampMillis,
    )
    var meta: HttpResult<Unit>? = null
    val chunks = ArrayList<ByteArray>()
    postBytes(url, payload, callerHeaders + signed) { meta = it }.collect { chunks += it }
    val joined = ByteArray(chunks.sumOf { it.size })
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(joined, offset)
        offset += chunk.size
    }
    val received = meta
    return HttpResult(
        value = parseJsonObject(joined.decodeToString()),
        url = url,
        statusCode = received?.statusCode ?: HTTP_OK,
        headers = received?.headers.orEmpty(),
        requestBody = payload.decodeToString(),
        timestamp = received?.timestamp,
    )
}

private const val HTTP_OK = 200
