package com.sabreware.aide.data.connector.oauth

import io.ktor.http.Url

/**
 * RFC 8707 canonical resource URI for an MCP server URL — the OAuth `resource` indicator that binds a token to
 * that specific server. Lowercases the scheme + host, omits the default port, and drops the query/fragment +
 * any trailing slash, so the value the AS sees on authorize / code-exchange / refresh is stable + identical.
 * Must be used on ALL of those requests (a rotated refresh token must re-bind to the same resource).
 */
fun canonicalResource(url: String): String {
    val u = runCatching { Url(url) }.getOrNull() ?: return url.substringBefore('#').trimEnd('/')
    val scheme = u.protocol.name.lowercase()
    val host = u.host.lowercase()
    val port = if (u.port == u.protocol.defaultPort) "" else ":${u.port}"
    val path = u.encodedPath.trimEnd('/')
    return "$scheme://$host$port$path"
}
