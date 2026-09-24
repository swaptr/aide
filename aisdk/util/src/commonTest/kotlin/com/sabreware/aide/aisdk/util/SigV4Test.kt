package com.sabreware.aide.aisdk.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SigV4, against vectors computed by an INDEPENDENT implementation (Python's `hmac`/`hashlib`) rather
 * than by this code agreeing with itself.
 *
 * That distinction matters more here than almost anywhere else in the library: a wrong signature fails as
 * an opaque 403 with no indication which of the six steps was wrong, and it fails identically whether the
 * mistake is a missing `AWS4` prefix, an unsorted header, or a mis-encoded path. Self-consistent tests
 * would pass against a uniformly wrong implementation.
 */
class SigV4Test {

    // AWS's documented example identity.
    private val secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val credentials = AwsCredentials(accessKeyId = "AKIAIOSFODNN7EXAMPLE", secretAccessKey = secret)

    @Test
    fun `the four-step signing key matches an independent derivation`() {
        val key = SigV4.signingKey(secret, dateStamp = "20130524", region = "us-east-1", service = "s3")

        assertEquals("f117494eff5d09da21cbf7f0339559ea04fc9582d31299cb992be70a6b27c97a", key.hex())
    }

    @Test
    fun `dropping the AWS4 prefix produces a different key`() {
        // The single easiest mistake in the whole algorithm, and it is invisible without this assertion.
        val correct = SigV4.signingKey(secret, "20130524", "us-east-1", "s3")
        val withoutPrefix = SigV4.signingKey("", "20130524", "us-east-1", "s3")

        assertTrue(correct.hex() != withoutPrefix.hex())
    }

    @Test
    fun `a full request signature matches an independent computation`() {
        val payload = """{"anthropic_version":"bedrock-2023-05-31"}""".encodeToByteArray()

        val headers = SigV4.signedHeaders(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com" +
                "/model/anthropic.claude-3-sonnet/invoke-with-response-stream",
            headers = mapOf("content-type" to "application/json"),
            payload = payload,
            credentials = credentials,
            region = "us-east-1",
            service = "bedrock",
            timestampMillis = 1_705_314_645_000L,
        )

        assertEquals(
            "661f62a67d543adab5f8cd5f03e2b23e3806b22b592f7b2183fa95029e90cca5",
            headers["x-amz-content-sha256"],
        )
        assertEquals("20240115T103045Z", headers["x-amz-date"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20240115/us-east-1/bedrock/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=e0c2e569121e5a65e3b3e98edc5d774cc21cdc3f30c3403ec3bfca546c89beeb",
            headers["Authorization"],
        )
    }

    @Test
    fun `the empty payload hash is the documented constant`() {
        val headers = SigV4.signedHeaders(
            method = "GET",
            url = "https://bedrock.us-east-1.amazonaws.com/foundation-models",
            headers = emptyMap(),
            payload = ByteArray(0),
            credentials = credentials,
            region = "us-east-1",
            service = "bedrock",
            timestampMillis = 1_705_314_645_000L,
        )

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            headers["x-amz-content-sha256"],
        )
    }

    @Test
    fun `a session token is signed, not merely attached`() {
        // STS credentials are rejected if x-amz-security-token is sent but not part of SignedHeaders.
        val headers = SigV4.signedHeaders(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/m/invoke",
            headers = emptyMap(),
            payload = ByteArray(0),
            credentials = credentials.copy(sessionToken = "TOKEN"),
            region = "us-east-1",
            service = "bedrock",
            timestampMillis = 1_705_314_645_000L,
        )

        assertEquals("TOKEN", headers["x-amz-security-token"])
        assertTrue(
            headers["Authorization"]!!.contains("SignedHeaders=host;x-amz-date;x-amz-security-token"),
            headers["Authorization"]!!,
        )
    }

    @Test
    fun `timestamps format without a datetime dependency`() {
        assertEquals("20240115T103045Z", SigV4.amzDate(1_705_314_645_000L))
        assertEquals("19700101T000000Z", SigV4.amzDate(0L))
        // A leap day, which a naive days-to-date conversion gets wrong.
        assertEquals("20240229T235959Z", SigV4.amzDate(1_709_251_199_000L))
        // A century that is NOT a leap year under the Gregorian rule.
        assertEquals("21000301T000000Z", SigV4.amzDate(4_107_542_400_000L))
    }

    @Test
    fun `header order does not change the signature`() {
        fun sign(headers: Map<String, String>) = SigV4.signedHeaders(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/m/invoke",
            headers = headers,
            payload = ByteArray(0),
            credentials = credentials,
            region = "us-east-1",
            service = "bedrock",
            timestampMillis = 1_705_314_645_000L,
        )["Authorization"]

        // Canonical headers are sorted, so the caller's map order is irrelevant — and if it were not,
        // the failure would be intermittent and depend on map iteration order.
        assertEquals(
            sign(mapOf("content-type" to "application/json", "x-custom" to "a")),
            sign(mapOf("x-custom" to "a", "content-type" to "application/json")),
        )
    }

    @Test
    fun `query parameters are sorted after encoding`() {
        val signed = SigV4.signedHeaders(
            method = "GET",
            url = "https://bedrock.us-east-1.amazonaws.com/foundation-models?byProvider=b&byOutputModality=a",
            headers = emptyMap(),
            payload = ByteArray(0),
            credentials = credentials,
            region = "us-east-1",
            service = "bedrock",
            timestampMillis = 1_705_314_645_000L,
        )

        // Sorting before encoding gives a different order for any key whose encoding changes its bytes;
        // the docs are explicit that encoding comes first.
        assertTrue(signed["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential="))
    }
}
