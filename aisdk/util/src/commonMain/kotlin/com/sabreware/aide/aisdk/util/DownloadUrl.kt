package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.DownloadError

/**
 * Whether a URL a PROVIDER named is safe to fetch, and safe to fetch with credentials attached.
 *
 * This exists because several of the async vendors answer a submit with a URL to poll, and the polling
 * code then sends the API key to whatever host that URL names. A compromised or merely misconfigured
 * vendor response is therefore a credential exfiltration primitive, and a URL pointing at `169.254.169.254`
 * or `localhost` is an SSRF primitive against whatever machine is running the app — on a phone that is
 * the user's own network, and on a server it is the metadata service.
 *
 * The reference has the same guard and a test named after it; the port skipped it as "Node-specific",
 * which is wrong twice over: this is pure string and integer logic with no platform dependency, and Ktor
 * follows redirects blindly, so the check has to happen before the request rather than after.
 */
public object DownloadUrl {

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Throws [DownloadError] if [url] must not be fetched at all.
     *
     * The refusal reasons are all "this address is not on the public internet": a loopback, a
     * link-local, a private range, or a scheme that is not HTTP.
     */
    public fun validate(url: String) {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme !in ALLOWED_SCHEMES) {
            throw DownloadError(url, message = "Refusing to fetch $url: only http and https are allowed")
        }
        val host = hostOf(url)
            ?: throw DownloadError(url, message = "Refusing to fetch $url: no host")
        if (isPrivate(host)) {
            throw DownloadError(url, message = "Refusing to fetch $url: $host is not a public address")
        }
    }

    /**
     * Whether credentials may ride along to [url].
     *
     * A key belongs only to the origin it was issued for. A vendor that answers with a result URL on its
     * own CDN gets no key; a vendor that answers with a URL on its API host does. Comparing the whole
     * origin rather than a suffix is deliberate — `api.example.com.attacker.net` ends with the right
     * string and is a different site.
     */
    public fun sameOrigin(url: String, trustedOrigin: String): Boolean {
        val a = originOf(url) ?: return false
        val b = originOf(trustedOrigin) ?: return false
        return a == b
    }

    /** [headers] if the URL is on [trustedOrigin]; an empty map otherwise. */
    public fun headersFor(
        url: String,
        trustedOrigin: String?,
        headers: Map<String, String>,
    ): Map<String, String> =
        if (trustedOrigin != null && sameOrigin(url, trustedOrigin)) headers else emptyMap()

    private fun originOf(url: String): String? {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty()) return null
        val host = hostOf(url) ?: return null
        val hostPort = url.substringAfter("://").substringBefore('/').substringAfter('@')
        val explicitPort = hostPort.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
        // The default port is filled in so `https://x` and `https://x:443` compare equal — they are one
        // origin, and treating them as two drops the API key off a URL that is on the API host.
        val port = explicitPort ?: if (scheme == "https") HTTPS_PORT else HTTP_PORT
        return "$scheme://$host:$port"
    }

    /**
     * The host [url] names, lower-cased, with userinfo, port and any trailing dot removed — or null if
     * it names none.
     *
     * Public because a same-origin comparison is not the only trust rule a provider needs: BFL answers a
     * submit with a poll URL on whichever cluster took the job, so its guard trusts the `bfl.ai` DOMAIN
     * rather than one origin. That guard has to parse a host, and a second parser is how the two drift.
     * The order below is the whole reason this is worth sharing: userinfo comes off BEFORE the port, so
     * `https://bfl.ai:x@evil.com/` reads as `evil.com` and not as `bfl.ai`. A local copy of this that
     * stripped the port first read that URL as trusted and sent it the API key.
     */
    public fun hostOf(url: String): String? {
        val hostPort = url.substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringAfter('@')
            .ifEmpty { return null }
        return if (hostPort.startsWith('[')) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBeforeLast(':', missingDelimiterValue = hostPort)
        }.lowercase()
            // `localhost.` and `127.0.0.1.` are fully-qualified spellings that resolve identically and
            // match neither the name blocklist nor the four-octet split. The trailing dot is the whole
            // bypass, so it comes off before either check sees the host.
            .trimEnd('.')
    }

    /**
     * Whether [host] resolves to something that is not the public internet, judged from the literal.
     *
     * A hostname is accepted: refusing everything that is not a literal would refuse every real vendor,
     * and the DNS-rebinding case this cannot see is not solvable at this layer anyway — it needs the
     * socket's peer address, which no multiplatform HTTP client exposes. Blocking the literal forms is
     * what stops the common mistakes: a vendor echoing back an internal address, and a config pointing at
     * a metadata service.
     */
    private fun isPrivate(host: String): Boolean {
        // `.local` is mDNS: a name that resolves only on the machine's own LAN, which is exactly the
        // network a fetched URL must not be able to reach.
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host.contains(':')) return isPrivateIpv6(host)
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != IPV4_OCTETS || host.split('.').size != IPV4_OCTETS) return false
        if (octets.any { it !in 0..MAX_OCTET }) return false
        val (a, b, c) = octets
        return when {
            a == 0 -> true // 0.0.0.0/8, which several stacks route to the local host
            a == 10 -> true
            a == 100 && b in 64..127 -> true // CGNAT, internal traffic at more than one cloud
            a == 127 -> true
            a == 169 && b == 254 -> true // link-local, and with it every metadata service
            a == 172 && b in 16..31 -> true
            a == 192 && b == 0 && (c == 0 || c == 2) -> true // IETF assignments and TEST-NET-1
            a == 192 && b == 168 -> true
            a == 198 && (b == 18 || b == 19) -> true // benchmarking
            a == 198 && b == 51 && c == 100 -> true // TEST-NET-2
            a == 203 && b == 0 && c == 113 -> true // TEST-NET-3
            a >= 224 -> true // multicast, reserved, and the broadcast address
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
        if (normalized == "::1" || normalized == "::") return true
        // fc00::/7 unique-local and fe80::/10 link-local.
        return normalized.startsWith("fc") || normalized.startsWith("fd") ||
            normalized.startsWith("fe8") || normalized.startsWith("fe9") ||
            normalized.startsWith("fea") || normalized.startsWith("feb") ||
            normalized.startsWith("::ffff:")
    }

    private const val IPV4_OCTETS = 4
    private const val MAX_OCTET = 255
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
