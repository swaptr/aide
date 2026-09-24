package com.sabreware.aide.aisdk.util

/**
 * Merges header maps left to right, dropping null values.
 *
 * Nulls are dropped rather than sent empty because that is how a caller REMOVES a default header — a
 * provider sets `Accept-Encoding: identity`, and a caller that must not send it passes null rather than
 * being stuck with it.
 */
public fun combineHeaders(vararg headers: Map<String, String?>?): Map<String, String> {
    // Keyed by the lower-cased name, holding the ORIGINAL casing, so `Authorization` from a caller
    // replaces `authorization` from a provider instead of both going out. Ktor appends duplicate
    // headers rather than replacing them, so two spellings of one header is two headers on the wire —
    // and a vendor seeing two `Authorization` values rejects the request.
    val out = LinkedHashMap<String, Pair<String, String>>()
    headers.filterNotNull().forEach { map ->
        map.forEach { (key, value) ->
            val slot = key.lowercase()
            if (value == null) out.remove(slot) else out[slot] = key to value
        }
    }
    return out.values.associate { it }
}

/**
 * [headers] with the ones that must never ride to a URL a provider named.
 *
 * Three groups, each a different failure. Hop-by-hop and routing headers (RFC 7230 §6.1, plus `Host`)
 * belong to one connection and re-sending them on another confuses whatever is in the middle. Proxy and
 * origin-spoofing headers let a caller's own value decide how a downstream service reads the request.
 * And the cloud metadata headers are the second half of the SSRF the URL guard blocks the first half of:
 * every metadata service requires one of them, so stripping them fails the attack even where an address
 * check has been evaded.
 *
 * `Authorization` and its per-vendor equivalents are NOT here — the first hop of a poll against the API
 * host legitimately needs them. They are dropped by the caller when a redirect leaves that origin.
 */
public fun sanitizeRequestHeaders(headers: Map<String, String>): Map<String, String> =
    headers.filterKeys { it.lowercase() !in BLOCKED_REQUEST_HEADERS }

private val BLOCKED_REQUEST_HEADERS = setOf(
    // Hop-by-hop and transport
    "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade",
    // Host and virtual-host routing
    "host",
    // Proxy and origin spoofing
    "forwarded", "proxy-authorization", "via",
    "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip",
    // Cloud metadata: GCP, AWS IMDSv1 and v2, Azure, Alibaba, DigitalOcean
    "metadata", "metadata-flavor", "x-aws-ec2-metadata-token", "x-metadata-token",
    // Session
    "cookie", "set-cookie",
)
