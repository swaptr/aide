package com.sabreware.aide.aisdk.providers.google.interactions

import com.sabreware.aide.aisdk.Content
import com.sabreware.aide.aisdk.providers.google.stringOrNull
import com.sabreware.aide.aisdk.util.IdGenerator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Where the Interactions API says what it grounded an answer on.
 *
 * Two channels, and both are read: a text block's `annotations` cite spans of the text, and a built-in
 * tool's `*_result` step lists what the tool fetched. The same URL routinely appears in both — the search
 * result and the citation into it — so every emitter de-duplicates by [sourceKey] and the stream keeps
 * one set across the whole response.
 */

/** One annotation as a source, or null where it lacks the minimum to be one — a citation with no URL. */
internal fun InteractionsAnnotation.toSource(ids: IdGenerator): Content.Source? = when (type) {
    "url_citation" -> url?.takeIf { it.isNotEmpty() }?.let { Content.Source.Url(id = ids.next(), url = it, title = title) }

    "file_citation" -> {
        val uri = (url ?: documentUri ?: fileName)?.takeIf { it.isNotEmpty() }
        when {
            uri == null -> null
            uri.isHttp() -> Content.Source.Url(id = ids.next(), url = uri, title = fileName)
            else -> {
                val filename = fileName ?: basename(uri)
                Content.Source.Document(
                    id = ids.next(),
                    mediaType = documentMediaType(uri),
                    title = fileName ?: filename ?: uri,
                    filename = filename,
                )
            }
        }
    }

    "place_citation" -> url?.takeIf { it.isNotEmpty() }?.let { Content.Source.Url(id = ids.next(), url = it, title = name) }

    else -> null
}

/** The sources of one text block's annotations, each URL or document once. */
internal fun List<InteractionsAnnotation>?.toSources(ids: IdGenerator): List<Content.Source> {
    if (this == null) return emptyList()
    val seen = mutableSetOf<String>()
    return mapNotNull { it.toSource(ids) }.filter { seen.add(it.sourceKey()) }
}

/**
 * The sources a built-in tool result yields.
 *
 * URL context: each fetched URL that succeeded. Google Search: each entry with a URL — the
 * `search_suggestions` entries are the HTML widget Google requires publishers to display, not citations,
 * and are skipped. Maps: each place with a URL. File search: best effort over a loosely typed list, a
 * document unless the URI is an http(s) one. Code execution yields nothing citable.
 */
internal fun builtinToolResultToSources(stepType: String, result: JsonElement?, ids: IdGenerator): List<Content.Source> {
    val entries = (result as? JsonArray)?.filterIsInstance<JsonObject>() ?: return emptyList()
    return when (stepType) {
        "url_context_result" -> entries.mapNotNull { entry ->
            val url = entry.stringOrNull("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val status = entry.stringOrNull("status")
            if (status != null && status != "success") return@mapNotNull null
            Content.Source.Url(id = ids.next(), url = url)
        }

        "google_search_result" -> entries.mapNotNull { entry ->
            val url = entry.stringOrNull("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Content.Source.Url(id = ids.next(), url = url, title = entry.stringOrNull("title"))
        }

        "google_maps_result" -> entries.flatMap { entry ->
            (entry["places"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { place ->
                val url = place.stringOrNull("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Content.Source.Url(id = ids.next(), url = url, title = place.stringOrNull("name"))
            }
        }

        "file_search_result" -> entries.mapNotNull { entry ->
            val fileName = entry.stringOrNull("file_name")
            val uri = (entry.stringOrNull("url") ?: entry.stringOrNull("document_uri") ?: fileName)
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = entry.stringOrNull("title")
            if (uri.isHttp()) {
                Content.Source.Url(id = ids.next(), url = uri, title = title)
            } else {
                val filename = fileName ?: basename(uri)
                Content.Source.Document(
                    id = ids.next(),
                    mediaType = documentMediaType(uri),
                    title = title ?: fileName ?: filename ?: uri,
                    filename = filename,
                )
            }
        }

        else -> emptyList()
    }
}

/** The identity a citation is de-duplicated on: its URL, or its file name. */
internal fun Content.Source.sourceKey(): String = when (this) {
    is Content.Source.Url -> "url:$url"
    is Content.Source.Document -> "doc:${filename ?: title}"
}

private fun String.isHttp(): Boolean = startsWith("http://") || startsWith("https://")

private fun basename(uri: String): String? = uri.substringAfterLast('/').takeIf { it.isNotEmpty() }

/** The media type a document citation is labelled with, off its extension; unknown stays binary. */
private fun documentMediaType(uri: String): String {
    val lower = uri.lowercase()
    return when {
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".txt") -> "text/plain"
        lower.endsWith(".md") || lower.endsWith(".markdown") -> "text/markdown"
        lower.endsWith(".doc") -> "application/msword"
        lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/octet-stream"
    }
}
