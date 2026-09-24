package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.AuthorizationServerMetadata
import com.sabreware.aide.core.domain.connector.oauth.OAuthDiscovery
import com.sabreware.aide.core.domain.connector.oauth.ProtectedResourceMetadata
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess

/**
 * MCP authorization-server discovery (spec 2025-11-25): Protected Resource Metadata (RFC 9728) via the
 * 401 `WWW-Authenticate` `resource_metadata` hint, else the well-known URIs (sub-path then root); then
 * Authorization Server Metadata (RFC 8414 / OpenID Connect) trying the spec's priority order.
 */
class OAuthDiscoveryImpl(
    private val http: HttpClient,
) : OAuthDiscovery {

    override suspend fun protectedResourceMetadata(serverUrl: String, wwwAuthenticate: String?): ProtectedResourceMetadata? {
        parseResourceMetadataUrl(wwwAuthenticate)?.let { hinted -> fetchPrm(hinted)?.let { return it } }
        val origin = originOf(serverUrl) ?: return null
        val path = pathOf(serverUrl)
        val candidates = buildList {
            if (path.isNotBlank() && path != "/") add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }
        for (url in candidates) fetchPrm(url)?.let { return it }
        return null
    }

    override suspend fun resourceMetadataChallenge(serverUrl: String): String? = runCatching {
        // A bearer-protected MCP server answers an unauthenticated request with 401 + WWW-Authenticate
        // (MCP auth spec / RFC 9728 §5.1). Best-effort: a non-401 (or unreachable) server just yields null,
        // leaving the well-known probing path unchanged. The client is expectSuccess=false,
        // so the 401 comes back as a response rather than throwing.
        val resp = http.get(serverUrl)
        if (resp.status == HttpStatusCode.Unauthorized) resp.headers[HttpHeaders.WWWAuthenticate] else null
    }.getOrNull()

    override suspend fun authServerMetadata(issuer: String): AuthorizationServerMetadata? {
        val parsed = runCatching { Url(issuer) }.getOrNull()?.takeIf { it.host.isNotBlank() } ?: return null
        val base = "${parsed.protocol.name}://${authority(parsed)}"
        val path = parsed.encodedPath.trim('/')
        val candidates = if (path.isBlank()) {
            listOf(
                "$base/.well-known/oauth-authorization-server",
                "$base/.well-known/openid-configuration",
            )
        } else {
            listOf(
                "$base/.well-known/oauth-authorization-server/$path",
                "$base/.well-known/openid-configuration/$path",
                "$base/$path/.well-known/openid-configuration",
            )
        }
        for (url in candidates) fetchAs(url)?.let { return it }
        return null
    }

    private suspend fun fetchPrm(url: String): ProtectedResourceMetadata? = runCatching {
        val r = http.get(url)
        if (r.status.isSuccess()) r.body<ProtectedResourceMetadata>() else null
    }.getOrNull()

    private suspend fun fetchAs(url: String): AuthorizationServerMetadata? = runCatching {
        val r = http.get(url)
        if (r.status.isSuccess()) r.body<AuthorizationServerMetadata>() else null
    }.getOrNull()

    private fun parseResourceMetadataUrl(header: String?): String? =
        header?.let { Regex("""resource_metadata="([^"]+)"""").find(it)?.groupValues?.getOrNull(1) }

    private fun originOf(url: String): String? = runCatching { Url(url) }.getOrNull()
        ?.takeIf { it.host.isNotBlank() }
        ?.let { "${it.protocol.name}://${authority(it)}" }

    private fun pathOf(url: String): String = runCatching { Url(url).encodedPath }.getOrNull().orEmpty()

    // host, plus ":port" only when the URL specified one (matches java.net.URI.authority: no default :443/:80).
    private fun authority(url: Url): String =
        if (url.specifiedPort == DEFAULT_PORT) url.host else "${url.host}:${url.specifiedPort}"
}
