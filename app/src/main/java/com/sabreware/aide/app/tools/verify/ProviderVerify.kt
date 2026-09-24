package com.sabreware.aide.app.tools.verify

import com.sabreware.aide.core.domain.llm.verify.VerificationResult
import kotlinx.coroutines.CancellationException

/**
 * Wraps a content-provider read done for **verification**.
 *
 * A verification query observes work that has already happened, so a provider that refuses to answer means
 * "could not confirm", never "the operation failed". `runWithVerify` guarantees that much on its own; this
 * exists so the reason string stays meaningful — the model branches on it, and `permission_denied` tells it
 * to send the user to Settings while `provider_error` tells it to say so and stop, neither of which it can
 * infer from a generic error.
 */
internal inline fun <R> verifyingQuery(block: () -> VerificationResult<R>): VerificationResult<R> =
    try {
        block()
    } catch (ce: CancellationException) {
        throw ce
    } catch (se: SecurityException) {
        // Revoked between the write and the poll. The write still happened.
        VerificationResult.VerificationImpossible("permission_denied")
    } catch (t: Throwable) {
        // An OEM provider rejecting a projection column, a cursor window failure, a null resolver.
        VerificationResult.VerificationImpossible("provider_error")
    }
