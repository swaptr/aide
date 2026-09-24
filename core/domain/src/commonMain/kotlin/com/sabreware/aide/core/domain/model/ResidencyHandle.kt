package com.sabreware.aide.core.domain.model

/**
 * A live hold on a resident model. While any handle is held (refcount > 0) the model is never
 * idle-released or trim-evicted. [release] drops this hold; when the *last* hold drops, a
 * [keepAliveMs] expiry timer is armed that closes the model when it fires (cancelled if the model is
 * re-acquired first). Handles for [Residency.NONE] models are no-ops.
 *
 * Acquire/release pair up like a lock — always release in a `finally`. [release] is idempotent, so a
 * double release (e.g. cancellation racing a normal finish) is harmless.
 */
interface ResidencyHandle {
    suspend fun release(keepAliveMs: Long = ResidencyManager.DEFAULT_KEEP_ALIVE_MS)
}
