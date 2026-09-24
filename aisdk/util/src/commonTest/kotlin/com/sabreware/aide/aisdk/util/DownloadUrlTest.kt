package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.DownloadError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard on a URL a PROVIDER named.
 *
 * Both halves are security properties rather than conveniences: the vendors that answer a submit with a
 * URL to poll are, without this, telling us which host to send the API key to and which address on the
 * running machine to open a connection against.
 */
class DownloadUrlTest {

    @Test
    fun `a public https url is allowed`() {
        DownloadUrl.validate("https://cdn.example.com/result/1.png")
    }

    @Test
    fun `loopback, link-local, private and CGNAT addresses are refused`() {
        val refused = listOf(
            "http://127.0.0.1/x",
            "http://localhost/x",
            "http://sub.localhost/x",
            "http://printer.local/x",
            "http://169.254.169.254/latest/meta-data/", // the address this guard exists for
            "http://10.0.0.5/x",
            "http://172.16.4.1/x",
            "http://192.168.1.1/x",
            "http://100.64.0.1/x",
            "http://0.0.0.0/x",
            "http://255.255.255.255/x",
            "http://[::1]/x",
            "http://[fe80::1]/x",
            "http://[fd00::1]/x",
        )

        refused.forEach { url ->
            assertFailsWith<DownloadError>(url) { DownloadUrl.validate(url) }
        }
    }

    @Test
    fun `a trailing dot does not smuggle a blocked host past the check`() {
        // `localhost.` and `127.0.0.1.` resolve identically to the forms above; without normalising the
        // dot off, the first matches no name in the blocklist and the second splits into five parts and
        // is not read as an address at all.
        assertFailsWith<DownloadError> { DownloadUrl.validate("http://localhost./x") }
        assertFailsWith<DownloadError> { DownloadUrl.validate("http://127.0.0.1./x") }
    }

    @Test
    fun `an address just outside a blocked range is allowed`() {
        // The ranges are bounds, not prefixes: 172.15 and 172.32 are public, and refusing them would
        // refuse real vendor CDNs.
        DownloadUrl.validate("http://172.15.0.1/x")
        DownloadUrl.validate("http://172.32.0.1/x")
        DownloadUrl.validate("http://100.63.0.1/x")
        DownloadUrl.validate("http://11.0.0.1/x")
    }

    @Test
    fun `a non-http scheme is refused`() {
        assertFailsWith<DownloadError> { DownloadUrl.validate("file:///etc/passwd") }
        assertFailsWith<DownloadError> { DownloadUrl.validate("ftp://example.com/x") }
        assertFailsWith<DownloadError> { DownloadUrl.validate("/relative/path") }
    }

    @Test
    fun `a host that merely ends with the trusted origin is a different site`() {
        // The whole point of comparing origins rather than suffixes. `api.example.com.attacker.net` ends
        // with nothing useful, but a `endsWith("api.example.com")` check would hand it the API key.
        assertFalse(DownloadUrl.sameOrigin("https://api.example.com.attacker.net/x", "https://api.example.com"))
        assertFalse(DownloadUrl.sameOrigin("https://notapi.example.com/x", "https://api.example.com"))
    }

    @Test
    fun `origin comparison ignores path, case and an explicit default port`() {
        assertTrue(DownloadUrl.sameOrigin("https://API.Example.com:443/files/1", "https://api.example.com"))
        assertTrue(DownloadUrl.sameOrigin("https://api.example.com/files/1", "https://api.example.com:443"))
    }

    @Test
    fun `scheme and port are part of the origin`() {
        assertFalse(DownloadUrl.sameOrigin("http://api.example.com/x", "https://api.example.com"))
        assertFalse(DownloadUrl.sameOrigin("https://api.example.com:8443/x", "https://api.example.com"))
    }

    @Test
    fun `credentials in the url do not change the origin`() {
        // A userinfo prefix is the classic way to make a URL read as one host and resolve to another.
        assertFalse(DownloadUrl.sameOrigin("https://api.example.com@attacker.net/x", "https://api.example.com"))
    }

    @Test
    fun `headers ride along only on the trusted origin`() {
        val auth = mapOf("authorization" to "Bearer secret")

        assertEquals(auth, DownloadUrl.headersFor("https://api.example.com/f/1", "https://api.example.com", auth))
        assertEquals(emptyMap(), DownloadUrl.headersFor("https://cdn.example.com/f/1", "https://api.example.com", auth))
        // No trusted origin means nothing is trusted, rather than everything.
        assertEquals(emptyMap(), DownloadUrl.headersFor("https://api.example.com/f/1", null, auth))
    }
}
