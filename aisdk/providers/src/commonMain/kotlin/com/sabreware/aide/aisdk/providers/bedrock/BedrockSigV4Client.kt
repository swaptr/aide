package com.sabreware.aide.aisdk.providers.bedrock

import com.sabreware.aide.aisdk.util.AwsCredentials
import com.sabreware.aide.aisdk.util.SigV4
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.content.OutgoingContent

/**
 * [this] with every request SigV4-signed in [service]'s scope — the reference's
 * `createSigV4FetchFunction`, as a Ktor send interceptor.
 *
 * A derived client rather than a header map because a signature covers the body: it cannot be computed
 * until the request is fully built, which is after every model in this module has handed its headers
 * over. Send time is the one point that sees the final URL and the exact bytes, and it is also why the
 * OpenAI-compatible engine can be reused unchanged for Mantle — it never learns the request is signed.
 * Derived from the caller's client the way `ProviderHttp` derives its redirect-free one, so the engine,
 * the pool and every plugin stay as the host configured them; and derived per provider, so a client
 * shared with an unsigned vendor is not signing that vendor's traffic.
 *
 * Only the headers SigV4 itself requires — `host`, `x-amz-date`, the session token — are in the signed
 * set. AWS verifies exactly the headers a signature names, and a header an engine rewrites after us
 * (`Accept-Encoding`, `Content-Length`, a normalized `User-Agent`) would invalidate a signature that
 * named it while adding nothing to the one that did not.
 *
 * A body that is not bytes — a streaming upload — goes out unsigned rather than buffered; nothing on
 * this wire sends one, and buffering an unbounded body to hash it is the failure the transport's own
 * read cap exists to prevent.
 */
internal fun HttpClient.signingRequestsAs(
    service: String,
    region: String,
    credentials: () -> AwsCredentials,
    now: () -> Long,
): HttpClient {
    val signing = config { }
    signing.plugin(HttpSend).intercept { request ->
        val payload = when (val body = request.body) {
            is OutgoingContent.ByteArrayContent -> body.bytes()
            is OutgoingContent.NoContent -> ByteArray(0)
            else -> null
        }
        if (payload != null) {
            SigV4.signedHeaders(
                method = request.method.value,
                url = request.url.buildString(),
                headers = emptyMap(),
                payload = payload,
                credentials = credentials(),
                region = region,
                service = service,
                timestampMillis = now(),
            ).forEach { (name, value) -> request.headers[name] = value }
        }
        execute(request)
    }
    return signing
}
