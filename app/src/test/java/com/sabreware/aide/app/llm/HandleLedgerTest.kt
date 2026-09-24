package com.sabreware.aide.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleLedgerTest {

    private class Handle(val name: String, private val log: MutableList<String>) : AutoCloseable {
        var closes = 0
        override fun close() {
            closes++
            log += "close $name"
        }
    }

    @Test
    fun closeAll_frees_every_live_handle_before_the_engine() {
        val log = mutableListOf<String>()
        val ledger = HandleLedger<Handle>()
        ledger.open { Handle("a", log) }
        ledger.open { Handle("b", log) }

        ledger.closeAll { log += "free engine" }

        assertEquals(listOf("close a", "close b", "free engine"), log)
    }

    @Test
    fun a_free_queued_behind_the_engines_close_is_a_no_op() {
        val log = mutableListOf<String>()
        val ledger = HandleLedger<Handle>()
        val a = ledger.open { Handle("a", log) }

        ledger.closeAll { log += "free engine" }
        ledger.free(a)   // a session's reset/close drain that lost the race to the engine's close

        assertEquals("a conversation is never closed after its engine", 1, a.closes)
    }

    @Test
    fun nothing_opens_on_a_closed_engine() {
        val ledger = HandleLedger<Handle>()
        ledger.closeAll { }
        assertThrows(IllegalStateException::class.java) { ledger.open { Handle("late", mutableListOf()) } }
        assertTrue(ledger.isClosed)
    }

    @Test
    fun free_closes_once() {
        val ledger = HandleLedger<Handle>()
        val a = ledger.open { Handle("a", mutableListOf()) }
        ledger.free(a)
        ledger.free(a)
        assertEquals(1, a.closes)
    }
}
