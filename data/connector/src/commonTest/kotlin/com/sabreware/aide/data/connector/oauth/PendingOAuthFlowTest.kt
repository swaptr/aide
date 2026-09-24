package com.sabreware.aide.data.connector.oauth

import com.sabreware.aide.core.domain.connector.oauth.RedirectResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PendingOAuthFlowTest {

    @Test
    fun deliver_resolvesAwaitWithTheRedirect() = runTest {
        val armed = PendingOAuthFlow.arm(onCancel = {})
        PendingOAuthFlow.deliver(armed.token, RedirectResult("code", "state", null))
        assertEquals("code", armed.deferred.await().code)
    }

    @Test
    fun cancelIfPending_resolvesCancelled_andRunsOnCancelCleanup() = runTest {
        var cleaned = false
        val armed = PendingOAuthFlow.arm(onCancel = { cleaned = true })
        PendingOAuthFlow.cancelIfPending()
        assertEquals(PendingOAuthFlow.CANCELLED, armed.deferred.await().error)
        assertTrue(cleaned, "onCancel ran (e.g. socket closed)")
    }

    @Test
    fun cancelIfPending_afterMarkReceiving_isNoOp() = runTest {
        val armed = PendingOAuthFlow.arm(onCancel = {})
        PendingOAuthFlow.markReceiving(armed.token) // a redirect is arriving
        PendingOAuthFlow.cancelIfPending() // must NOT cancel a completing sign-in
        PendingOAuthFlow.deliver(armed.token, RedirectResult("c", "s", null))
        assertEquals("c", armed.deferred.await().code)
    }

    @Test
    fun deliver_withStaleToken_isIgnored_supersededFlowResolvesCancelled() = runTest {
        val first = PendingOAuthFlow.arm(onCancel = {})
        val second = PendingOAuthFlow.arm(onCancel = {}) // supersedes first
        assertEquals(PendingOAuthFlow.CANCELLED, first.deferred.await().error)
        PendingOAuthFlow.deliver(first.token, RedirectResult("late", "x", null)) // stale → dropped
        PendingOAuthFlow.deliver(second.token, RedirectResult("ok", "y", null))
        assertEquals("ok", second.deferred.await().code)
    }

    @Test
    fun deliverFromCallback_resolvesCurrentFlow() = runTest {
        val armed = PendingOAuthFlow.arm(onCancel = {})
        PendingOAuthFlow.deliverFromCallback(RedirectResult("cb", "s", null))
        assertEquals("cb", armed.deferred.await().code)
    }
}
