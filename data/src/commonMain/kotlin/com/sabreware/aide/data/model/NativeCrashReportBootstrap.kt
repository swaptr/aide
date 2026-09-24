package com.sabreware.aide.data.model

import com.sabreware.aide.core.common.startup.DeferredBootstrap
import com.sabreware.aide.core.domain.model.NativeLoadJournal
import com.sabreware.aide.core.domain.util.AideLog

/**
 * Reports a model load that never came back.
 *
 * A [NativeLoadJournal] entry still present at launch means the previous process died inside JNI — a
 * segfault in Sherpa or LiteRT, which leaves no exception and no stack. This is the only place that
 * information exists, and without someone reading it the journal is just a write.
 *
 * Deferred rather than run in `Application.onCreate`: it reads DataStore, and nothing that touches disk
 * belongs ahead of the first frame.
 */
class NativeCrashReportBootstrap(
    private val journal: NativeLoadJournal,
    /** Notified once per crash, before acknowledgement, so a consumer can degrade that model's backend. */
    private val onCrashDetected: suspend (modelKey: String) -> Unit = {},
) : DeferredBootstrap {

    override suspend fun start() {
        val crashed = journal.crashedKey() ?: return
        AideLog.w(
            TAG,
            "previous process died loading '$crashed' — a native crash inside the engine, not an exception. " +
                "That model is the likely cause of a crash-on-open loop.",
        )
        onCrashDetected(crashed)
        journal.acknowledge()
    }

    private companion object {
        const val TAG = "NativeLoad"
    }
}
