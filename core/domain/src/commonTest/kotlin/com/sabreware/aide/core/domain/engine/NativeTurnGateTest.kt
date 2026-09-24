package com.sabreware.aide.core.domain.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.test.Test

class NativeTurnGateTest {

    @Test
    fun a_drain_cancelled_while_waiting_returns_the_permits_it_took() = runTest {
        val gate = NativeTurnGate()
        val turnDone = CompletableDeferred<Unit>()
        val turn = launch { gate.turn { turnDone.await() } }
        runCurrent()

        // A model switch waits behind the live turn, then its caller gives up (Stop).
        val waitingDrain = launch { gate.drain { } }
        runCurrent()
        waitingDrain.cancel()
        runCurrent()

        turnDone.complete(Unit)
        runCurrent()
        assertTrue(turn.isCompleted)

        var freed = false
        val next = launch { gate.drain { freed = true } }
        runCurrent()
        assertTrue(freed && next.isCompleted, "a later free is not wedged by permits a cancelled drain kept")
    }
}
