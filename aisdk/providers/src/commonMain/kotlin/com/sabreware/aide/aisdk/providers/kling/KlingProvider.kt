package com.sabreware.aide.aisdk.providers.kling

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.LoadApiKeyError
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.VideoModel
import com.sabreware.aide.aisdk.util.Jwt
import com.sabreware.aide.aisdk.util.ProviderErrorStructure
import com.sabreware.aide.aisdk.util.ProviderHttp
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The provider id, and the namespace Kling payloads file under. */
public const val KLING_PROVIDER_ID: String = "klingai"

/**
 * Kling AI.
 *
 * Two authentication schemes, and a caller has exactly one of them. A plain API key is sent as a bearer;
 * the older access-key/secret-key pair signs a short-lived HS256 JWT instead — HMAC-SHA256, which okio
 * already provides, so it costs no more crypto than SigV4 did (see [Jwt]). The JWT is minted per request
 * because it expires in minutes: a provider that holds one works until it lapses and then fails as a 401
 * that reads like a bad secret.
 *
 * Video is what Kling is for, and the only modality served: the reference explicitly refuses an
 * image model here, and this port briefly carried one written from documentation alone — an
 * unverifiable surface with no reference to diff against, removed rather than shipped on faith
 * (tracked in TODO.md; it returns only against a verified live wire).
 */
public class KlingProvider(
    client: HttpClient,
    private val apiKey: String? = null,
    private val accessKey: String? = null,
    private val secretKey: String? = null,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** Wall-clock seconds; the JWT signing window is absolute. */
    private val nowSeconds: () -> Long = { 0L },
) : Provider {

    override val providerId: String = KLING_PROVIDER_ID

    private val http = ProviderHttp(client).withErrorStructure(KlingErrors)

    private val token: () -> String = {
        when {
            !apiKey.isNullOrBlank() -> apiKey
            accessKey != null && secretKey != null -> {
                val issued = nowSeconds()
                Jwt.hs256(
                    secret = secretKey,
                    claims = buildJsonObject { put("iss", accessKey) },
                    // Kling's own documented window: valid from slightly in the past for clock skew.
                    issuedAtSeconds = issued - CLOCK_SKEW_SECONDS,
                    expiresAtSeconds = issued + TOKEN_LIFETIME_SECONDS,
                )
            }
            else -> throw LoadApiKeyError(
                "Kling AI needs either an apiKey or an accessKey and secretKey pair.",
            )
        }
    }

    override fun videoModel(modelId: String): VideoModel = KlingVideoModel(
        modelId = modelId,
        http = http,
        baseUrl = baseUrl.trimEnd('/'),
        token = token,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api-singapore.klingai.com"
        private const val CLOCK_SKEW_SECONDS = 5L
        private const val TOKEN_LIFETIME_SECONDS = 1_800L
    }
}

/** Kling answers a rejected request with `{code, message}` and no `error` wrapper. */
internal val KlingErrors: ProviderErrorStructure = ProviderErrorStructure(
    extractMessage = { body -> (body as? JsonObject)?.get("message")?.jsonPrimitive?.content },
)

internal fun klingAuthHeaders(token: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $token")

/**
 * A file as Kling wants it: a URL, or BARE base64 with no `data:` prefix.
 *
 * Kling rejects a data URI. Sending one is a 400 naming the field rather than the encoding, which reads
 * as "this image is unacceptable" instead of "this envelope is".
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun klingImageString(data: BinaryData): String = when (data) {
    is BinaryData.Base64 -> data.value
    is BinaryData.Bytes -> Base64.encode(data.value)
}
