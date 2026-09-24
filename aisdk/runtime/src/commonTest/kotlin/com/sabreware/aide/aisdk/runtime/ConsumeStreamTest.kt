package com.sabreware.aide.aisdk.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** The drain: everything is consumed, a failure goes to the callback, a cancel goes nowhere but up. */
class ConsumeStreamTest {

    @Test
    fun `drains the flow to the end`() = runTest {
        var seen = 0

        flow {
            repeat(3) { emit(it); seen++ }
        }.consumeStream()

        assertEquals(3, seen)
    }

    @Test
    fun `a failure is handed to the callback instead of thrown`() = runTest {
        val boom = IllegalStateException("upstream died")
        var handed: Throwable? = null

        flow<Int> { throw boom }.consumeStream(onError = { handed = it })

        assertEquals(boom, handed)
    }

    @Test
    fun `without a callback a failure is absorbed, as the reference absorbs it`() = runTest {
        // Reaching the assertion IS the assertion: a drain that threw would never get here.
        flow<Int> { throw IllegalStateException("upstream died") }.consumeStream()
        assertEquals(1, flowOf(1).count())
    }

    @Test
    fun `cancellation is never absorbed`() = runTest {
        var handed: Throwable? = null

        assertFailsWith<CancellationException> {
            flow<Int> { throw CancellationException("stop") }.consumeStream(onError = { handed = it })
        }
        assertNull(handed)
        flowOf(1).consumeStream()
    }
}
