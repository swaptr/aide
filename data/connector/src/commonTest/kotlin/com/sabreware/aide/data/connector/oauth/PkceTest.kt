package com.sabreware.aide.data.connector.oauth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okio.ByteString.Companion.toByteString

@OptIn(ExperimentalEncodingApi::class)
class PkceTest {

    // Independent recomputation of the S256 code_challenge (base64url-no-pad of SHA-256(verifier)).
    private fun challengeOf(verifier: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(verifier.encodeToByteArray().toByteString().sha256().toByteArray())

    @Test
    fun s256_challengeIsBase64UrlSha256OfVerifier() {
        val c = Pkce.generate()
        assertEquals(challengeOf(c.verifier), c.challenge)
        assertEquals("S256", c.method)
    }

    @Test
    fun s256_matchesRfc7636TestVector() {
        // RFC 7636 Appendix B: verifier → challenge. Locks the SHA-256 + base64url-no-pad pipeline byte-exact.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifier_isUrlSafe_andWithinRfcLength() {
        val verifier = Pkce.generate().verifier
        assertTrue(verifier.length in 43..128, "length=${verifier.length}")
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun state_isUnique() {
        assertNotEquals(Pkce.state(), Pkce.state())
    }
}
