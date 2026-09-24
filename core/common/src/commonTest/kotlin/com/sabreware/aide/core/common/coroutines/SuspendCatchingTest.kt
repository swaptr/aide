package com.sabreware.aide.core.common.coroutines

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SuspendCatchingTest {

    @Test
    fun `a failure is captured`() {
        val result = runSuspendCatching { error("boom") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `a value passes through`() {
        assertEquals(3, runSuspendCatching { 3 }.getOrNull())
    }

    @Test
    fun `cancellation is never swallowed`() {
        assertFailsWith<CancellationException> { runSuspendCatching { throw CancellationException("stop") } }
    }
}
