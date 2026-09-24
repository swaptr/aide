package com.sabreware.aide.aisdk.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import okio.ByteString.Companion.encodeUtf8
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HS256 JSON Web Tokens, in commonMain.
 *
 * Some vendors — Kling among them — authenticate with a short-lived JWT signed by a shared secret rather
 * than with a static key. HS256 is HMAC-SHA256, which okio already provides, so this needs no more crypto
 * than SigV4 did.
 *
 * Only HS256. RS256 needs asymmetric signing, which okio does not do, and a vendor requiring it should
 * take a token from the host instead — the same call made for Vertex, and for the same reason: holding a
 * private key is not this library's job.
 */
@OptIn(ExperimentalEncodingApi::class)
public object Jwt {

    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * Signs a token valid from [issuedAtSeconds] until [expiresAtSeconds].
     *
     * Both are parameters rather than clock reads for the same reason SigV4 takes a timestamp: the token
     * is only valid in a window, so the caller owns the clock and a test can pin it.
     */
    public fun hs256(
        secret: String,
        claims: JsonObject,
        issuedAtSeconds: Long,
        expiresAtSeconds: Long,
    ): String {
        val header = buildJsonObject {
            put("alg", "HS256")
            put("typ", "JWT")
        }
        val payload = buildJsonObject {
            claims.forEach { (key, value) -> put(key, value) }
            put("iat", issuedAtSeconds)
            put("exp", expiresAtSeconds)
        }
        val signingInput = "${encode(header.toString())}.${encode(payload.toString())}"
        val signature = signingInput.encodeUtf8().hmacSha256(secret.encodeUtf8())
        return "$signingInput.${base64Url.encode(signature.toByteArray())}"
    }

    private fun encode(json: String): String = base64Url.encode(json.encodeToByteArray())
}
