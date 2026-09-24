package com.sabreware.aide.core.domain.connection

/**
 * Where a model runs, as the user thinks about it — the one axis the Models page filters by.
 *
 * Derived, never stored: a connection's kind is a fact about its endpoint ([of]), so moving a connection from
 * a laptop's Ollama to Ollama Cloud re-files it without anyone remembering to.
 */
enum class ConnectionKind(val label: String) {
    /** Weights on this device (LiteRT, Sherpa, the system engines). Not a connection at all. */
    OnDevice("On-device"),

    /** A server the user runs: this machine, their LAN, a `.local` host. Private; usually keyless. */
    SelfHosted("Self-hosted"),

    /** A hosted API reached over the internet. */
    Cloud("Cloud");

    companion object {
        /** The kind of the endpoint at [baseUrl]: private addresses are self-hosted, everything else cloud. */
        fun of(baseUrl: String): ConnectionKind = if (isPrivateHost(hostOf(baseUrl))) SelfHosted else Cloud

        /** The host of [url], lowercase, without port or IPv6 brackets. */
        internal fun hostOf(url: String): String {
            val authority = url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@')
            val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
            else authority.substringBefore(':')
            return host.lowercase()
        }

        private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa")

        private fun isPrivateHost(host: String): Boolean = when {
            host.isEmpty() || host == "localhost" -> true
            PRIVATE_SUFFIXES.any { host.endsWith(it) } -> true
            ':' in host -> host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
            '.' !in host -> true // a bare machine name only resolves on a private network
            else -> isPrivateIpv4(host)
        }

        private fun isPrivateIpv4(host: String): Boolean {
            val octets = host.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.size != 4) return false
            val (a, b) = octets
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127) // CGNAT: Tailscale and similar overlays
        }
    }
}

/** Where this connection's models run. */
val Connection.kind: ConnectionKind get() = ConnectionKind.of(baseUrl)
