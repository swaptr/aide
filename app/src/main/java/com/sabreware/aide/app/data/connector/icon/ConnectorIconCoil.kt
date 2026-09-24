package com.sabreware.aide.app.data.connector.icon

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.sabreware.aide.core.domain.connector.ConnectorIconRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import java.io.IOException
import kotlinx.io.readByteArray
import okio.Buffer
import okio.FileSystem

/**
 * Stable cache key **per connector** (not per candidate URL) so the fallback cascade caches its winning
 * outcome once — a connector that falls through to the monogram doesn't re-hit 3 network 404s on every scroll.
 */
class ConnectorIconKeyer : Keyer<ConnectorIconRequest> {
    override fun key(data: ConnectorIconRequest, options: Options): String = "connector-icon:${data.connectorId}"
}

/**
 * Tries each candidate URL in order; the first image-looking 2xx wins. SVG bytes (Simple Icons) carry
 * `image/svg+xml` so Coil's SvgDecoder picks them up; PNG/ICO go to the default bitmap decoder. If every
 * candidate fails it throws, and the row composable shows the monogram fallback.
 */
class ConnectorIconFetcher(
    private val request: ConnectorIconRequest,
    private val options: Options,
    private val httpClient: HttpClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        for (candidate in request.candidates) {
            val response = runCatching { httpClient.get(candidate) }.getOrNull() ?: continue
            if (!response.status.isSuccess()) continue
            val contentType = response.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
            if (!looksLikeImage(candidate, contentType)) continue
            val bytes = runCatching { response.bodyAsChannel().readRemaining().readByteArray() }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            return SourceFetchResult(
                source = ImageSource(Buffer().apply { write(bytes) }, FileSystem.SYSTEM),
                mimeType = contentType,
                dataSource = DataSource.NETWORK,
            )
        }
        throw IOException("connector icon: all candidates failed for ${request.connectorId}")
    }

    // Guard against SPA hosts returning HTML 200 for /favicon.ico — only treat image/* (or known
    // extensions when the server omits Content-Type) as a real icon.
    private fun looksLikeImage(url: String, contentType: String?): Boolean {
        if (contentType != null) return contentType.startsWith("image/")
        val path = url.substringBefore('?').lowercase()
        return IMAGE_EXTENSIONS.any(path::endsWith)
    }

    class Factory(private val httpClient: HttpClient) : Fetcher.Factory<ConnectorIconRequest> {
        override fun create(data: ConnectorIconRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ConnectorIconFetcher(data, options, httpClient)
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf(".png", ".ico", ".svg", ".jpg", ".jpeg", ".webp")
    }
}
