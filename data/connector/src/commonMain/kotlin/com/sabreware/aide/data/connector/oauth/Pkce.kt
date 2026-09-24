package com.sabreware.aide.data.connector.oauth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okio.ByteString.Companion.toByteString

/**
 * PKCE (RFC 7636, S256) + CSRF `state`. Pure Kotlin (okio SHA-256 + `kotlin.io.encoding.Base64`, no `java.*`)
 * so it lives in commonMain and unit-tests without Android/JVM APIs. The MCP spec requires S256 and a verifier
 * of 43–128 chars. Randomness comes from the platform CSPRNG via [secureRandomBytes].
 */
@OptIn(ExperimentalEncodingApi::class)
object Pkce {

    data class Challenge(val verifier: String, val challenge: String, val method: String = "S256")

    // URL-safe alphabet, no padding — the RFC 7636 code_verifier / code_challenge form.
    private val urlEncoder: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun generate(): Challenge {
        val verifier = randomToken(64) // 64 bytes → 86 base64url chars (within 43–128)
        // verifier is pure base64url ASCII, so encodeToByteArray() (UTF-8) == the US-ASCII bytes hashed before.
        val digest = verifier.encodeToByteArray().toByteString().sha256().toByteArray()
        return Challenge(verifier = verifier, challenge = urlEncoder.encode(digest))
    }

    fun state(): String = randomToken(16)

    private fun randomToken(bytes: Int): String = urlEncoder.encode(secureRandomBytes(bytes))
}
