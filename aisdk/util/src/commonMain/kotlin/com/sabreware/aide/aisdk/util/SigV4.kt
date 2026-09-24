package com.sabreware.aide.aisdk.util

import io.ktor.http.Url
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** AWS credentials. [sessionToken] is present only for temporary STS credentials. */
public data class AwsCredentials(
    /** The public half, named in the `Authorization` header's credential scope. */
    val accessKeyId: String,
    /** The secret half, used only to derive the signing key — never sent. */
    val secretAccessKey: String,
    /** The STS token, signed and sent as `x-amz-security-token` when present. */
    val sessionToken: String? = null,
)

/**
 * AWS Signature Version 4, in commonMain.
 *
 * The portability problem this solves: SigV4 needs SHA-256 and HMAC-SHA256, and the obvious source is
 * `java.security`, which `portabilityCheck` rejects and Kotlin/Native does not have. okio provides both
 * in its common API, so the whole algorithm is portable with no platform code and no `expect`/`actual`.
 *
 * A wrong signature fails as an opaque 403 with no hint about which of the six steps was wrong, so every
 * step is exposed separately and tested against vectors computed by an independent implementation.
 */
public object SigV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val TERMINATOR = "aws4_request"

    /**
     * Headers to ADD to a request so AWS will accept it.
     *
     * [timestampMillis] is a parameter rather than a clock read because a signature is only valid within
     * a few minutes of its timestamp, so the caller owns the clock — and a test needs to pin it.
     */
    public fun signedHeaders(
        method: String,
        url: String,
        headers: Map<String, String>,
        payload: ByteArray,
        credentials: AwsCredentials,
        region: String,
        service: String,
        timestampMillis: Long,
    ): Map<String, String> {
        val parsed = Url(url)
        val amzDate = amzDate(timestampMillis)
        val dateStamp = amzDate.substringBefore('T')
        val payloadHash = payload.toByteString().sha256().hex()

        // host and x-amz-date are always signed; a session token must be signed too or STS credentials
        // are rejected.
        val toSign = buildMap {
            putAll(headers)
            put("host", parsed.hostWithPortIfRequired())
            put("x-amz-date", amzDate)
            credentials.sessionToken?.let { put("x-amz-security-token", it) }
        }

        val canonicalHeaders = toSign.entries
            .map { it.key.lowercase() to it.value.trim().replace(SPACES, " ") }
            .sortedBy { it.first }
        val signedHeaderNames = canonicalHeaders.joinToString(";") { it.first }

        val canonicalRequest = buildString {
            append(method.uppercase()).append('\n')
            append(canonicalUri(parsed.encodedPath)).append('\n')
            append(canonicalQuery(parsed)).append('\n')
            canonicalHeaders.forEach { (name, value) -> append(name).append(':').append(value).append('\n') }
            append('\n')
            append(signedHeaderNames).append('\n')
            append(payloadHash)
        }

        val scope = "$dateStamp/$region/$service/$TERMINATOR"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(canonicalRequest.encodeUtf8().sha256().hex())
        }

        val signature = hmac(signingKey(credentials.secretAccessKey, dateStamp, region, service), stringToSign).hex()

        return buildMap {
            put("x-amz-date", amzDate)
            put("x-amz-content-sha256", payloadHash)
            credentials.sessionToken?.let { put("x-amz-security-token", it) }
            put(
                "Authorization",
                "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, " +
                    "SignedHeaders=$signedHeaderNames, Signature=$signature",
            )
        }
    }

    /**
     * The four-step key derivation, each step keyed by the result of the last.
     *
     * Exposed because it is the step most often wrong — the `AWS4` prefix on the secret is easy to miss,
     * and getting it wrong produces the same opaque 403 as every other mistake.
     */
    public fun signingKey(secretAccessKey: String, dateStamp: String, region: String, service: String): ByteString {
        val dateKey = hmac("AWS4$secretAccessKey".encodeUtf8(), dateStamp)
        val regionKey = hmac(dateKey, region)
        val serviceKey = hmac(regionKey, service)
        return hmac(serviceKey, TERMINATOR)
    }

    /** `yyyyMMdd'T'HHmmss'Z'`, computed rather than formatted so this needs no datetime dependency. */
    public fun amzDate(epochMillis: Long): String {
        val totalSeconds = epochMillis.floorDiv(MILLIS_PER_SECOND)
        val days = totalSeconds.floorDiv(SECONDS_PER_DAY)
        val secondOfDay = totalSeconds - days * SECONDS_PER_DAY
        val (year, month, day) = civilFromDays(days)
        return buildString {
            append(year.toString().padStart(4, '0'))
            append(month.toString().padStart(2, '0'))
            append(day.toString().padStart(2, '0'))
            append('T')
            append((secondOfDay / SECONDS_PER_HOUR).toString().padStart(2, '0'))
            append(((secondOfDay % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString().padStart(2, '0'))
            append((secondOfDay % SECONDS_PER_MINUTE).toString().padStart(2, '0'))
            append('Z')
        }
    }

    private fun hmac(key: ByteString, data: String): ByteString = data.encodeUtf8().hmacSha256(key)

    /**
     * The path, URI-encoded per AWS's rules.
     *
     * Slashes stay; everything outside the unreserved set is percent-encoded with UPPERCASE hex. The docs
     * warn against a platform's own URI encoder because the RFCs are ambiguous enough that
     * implementations disagree — and a disagreement here is a 403.
     */
    private fun canonicalUri(path: String): String =
        if (path.isEmpty()) "/" else path.split("/").joinToString("/") { uriEncode(it) }

    private fun canonicalQuery(url: Url): String = url.parameters.entries()
        .flatMap { (name, values) -> values.map { uriEncode(name) to uriEncode(it) } }
        // Sorted AFTER encoding, which the docs are explicit about — sorting first gives a different order
        // for any key whose encoding changes its byte sequence.
        .sortedWith(compareBy({ it.first }, { it.second }))
        .joinToString("&") { "${it.first}=${it.second}" }

    private fun uriEncode(value: String): String = buildString {
        value.encodeUtf8().toByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < ASCII_MAX || c in UNRESERVED) {
                append(c)
            } else {
                append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    /** Days-since-epoch to (year, month, day). Howard Hinnant's civil_from_days. */
    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + DAYS_EPOCH_SHIFT
        val era = (if (z >= 0) z else z - (DAYS_PER_ERA - 1)).floorDiv(DAYS_PER_ERA)
        val doe = z - era * DAYS_PER_ERA
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    private val SPACES = Regex(" +")
    private const val UNRESERVED = "-._~"
    private const val ASCII_MAX = 128
    private const val HEX = "0123456789ABCDEF"
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val DAYS_EPOCH_SHIFT = 719_468L
    private const val DAYS_PER_ERA = 146_097L
}

/** Host, plus the port when it is not the scheme's default — AWS signs what the wire actually carries. */
private fun Url.hostWithPortIfRequired(): String =
    if (port == protocol.defaultPort) host else "$host:$port"
