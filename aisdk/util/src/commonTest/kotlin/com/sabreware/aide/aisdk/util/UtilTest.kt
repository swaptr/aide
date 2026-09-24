package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.APICallError
import com.sabreware.aide.aisdk.JsonParseError
import com.sabreware.aide.aisdk.RetryError
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class UtilTest {

    // --- headers -------------------------------------------------------------------------------

    @Test
    fun `later header maps win and a null removes a default`() {
        val merged = combineHeaders(
            mapOf("Accept-Encoding" to "identity", "X-Key" to "a"),
            mapOf("X-Key" to "b"),
            // Passing null is how a caller drops a header the provider set by default.
            mapOf("Accept-Encoding" to null),
        )

        assertEquals(mapOf("X-Key" to "b"), merged)
    }

    // --- json ----------------------------------------------------------------------------------

    @Test
    fun `a bad body raises JsonParseError carrying the text`() {
        val error = assertFailsWith<JsonParseError> { parseJsonElement("{not json") }

        // Without the body there is no way to tell a truncated stream from a format change.
        assertEquals("{not json", error.text)
    }

    @Test
    fun `parseJsonElementOrNull skips a frame instead of killing the stream`() {
        assertNull(parseJsonElementOrNull("<html>proxy error</html>"))
    }

    @Test
    fun `unknown keys are ignored so a vendor addition is not an outage`() {
        val parsed = parseJsonObject("""{"known":1,"addedLastTuesday":2}""")

        assertEquals(2, parsed.size)
    }

    // --- ids -----------------------------------------------------------------------------------

    @Test
    fun `ids carry their prefix and are stable under a seeded random`() {
        val a = IdGenerator(prefix = "reason_", random = Random(7)).next()
        val b = IdGenerator(prefix = "reason_", random = Random(7)).next()

        assertEquals(a, b)
        assertTrue(a.startsWith("reason_"))
        assertEquals("reason_".length + 16, a.length)
    }

    @Test
    fun `ids avoid look-alike characters`() {
        val id = IdGenerator(length = 400).next()

        // 'l' and '1', 'O' and '0' are indistinguishable read off a bug report.
        assertTrue(id.none { it == 'l' }, "found 'l' in $id")
        assertTrue(id.none { it.isUpperCase() })
    }

    // --- retry ---------------------------------------------------------------------------------

    private fun retryable(status: Int) = APICallError(message = "x", url = "u", statusCode = status)

    @Test
    fun `a retryable failure is retried up to the limit`() = runTest {
        var attempts = 0

        val result = withRetry(RetryPolicy(maxRetries = 2, initialDelayMillis = 1)) {
            attempts++
            if (attempts < 3) throw retryable(503) else "ok"
        }

        // maxRetries counts RETRIES, so 2 means up to three calls.
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `a non-retryable failure propagates on the first attempt`() = runTest {
        var attempts = 0

        assertFailsWith<APICallError> {
            withRetry(RetryPolicy(maxRetries = 5, initialDelayMillis = 1)) {
                attempts++
                throw retryable(401)
            }
        }

        // Retrying a 401 only delays the error the user needs to see.
        assertEquals(1, attempts)
    }

    @Test
    fun `retries stop once the budget is spent, and every attempt is kept`() = runTest {
        var attempts = 0

        // RetryError rather than the last APICallError: a policy that surfaces only the final failure
        // hides the case where the FIRST one was the real cause and the rest are its consequences.
        assertFailsWith<RetryError> {
            withRetry(RetryPolicy(maxRetries = 1, initialDelayMillis = 1)) {
                attempts++
                throw retryable(500)
            }
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `a non-API failure is not retried`() = runTest {
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            withRetry(RetryPolicy(maxRetries = 3, initialDelayMillis = 1)) {
                attempts++
                error("bug in our own mapping code")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `RetryPolicy None never retries`() = runTest {
        var attempts = 0

        assertFailsWith<APICallError> {
            withRetry(RetryPolicy.None) {
                attempts++
                throw retryable(500)
            }
        }

        assertEquals(1, attempts)
    }
}
