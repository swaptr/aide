package com.sabreware.aide.data.model

import com.sabreware.aide.core.domain.fakes.FakePreferenceStore
import com.sabreware.aide.core.domain.model.around
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The breadcrumb that survives a process death.
 *
 * A native crash in Sherpa or LiteRT runs no `finally`, throws nothing, and logs nothing — the process is
 * simply gone. Everything below is about the one asymmetry that makes this work: a normal return always
 * clears the marker, and only a death can leave one behind.
 */
class PreferenceNativeLoadJournalTest {

    private fun journal() = PreferenceNativeLoadJournal(FakePreferenceStore())

    @Test
    fun `a completed load leaves nothing behind`() = runTest {
        val j = journal()
        j.around("whisper-tiny") { }
        assertNull(j.crashedKey(), "a load that returned is not a crash")
    }

    /**
     * The case that matters most for a false positive: an ordinary failure (a missing file, a bad config)
     * means the process SURVIVED, so it must not be reported as a native crash on the next launch.
     */
    @Test
    fun `a load that throws is still not a crash`() = runTest {
        val j = journal()
        runCatching { j.around("gemma-3n") { error("bad model file") } }
        assertNull(j.crashedKey(), "an exception means we lived — only a death leaves a marker")
    }

    /** A death is `begin` with no `finish`, which is exactly what a segfault looks like from here. */
    @Test
    fun `a load that never returned is reported at the next launch`() = runTest {
        val prefs = FakePreferenceStore()
        PreferenceNativeLoadJournal(prefs).begin("gemma-3n-e4b")

        // A new instance over the same store is the next process.
        assertEquals("gemma-3n-e4b", PreferenceNativeLoadJournal(prefs).crashedKey())
    }

    /**
     * Reading must not consume. If the app dies again before acting on the answer — entirely plausible when
     * the cause is a crash loop — the next launch still has to know.
     */
    @Test
    fun `an unacknowledged crash survives further launches`() = runTest {
        val prefs = FakePreferenceStore()
        PreferenceNativeLoadJournal(prefs).begin("sherpa-vad")

        assertEquals("sherpa-vad", PreferenceNativeLoadJournal(prefs).crashedKey())
        assertEquals("sherpa-vad", PreferenceNativeLoadJournal(prefs).crashedKey(), "reading does not clear")

        PreferenceNativeLoadJournal(prefs).acknowledge()
        assertNull(PreferenceNativeLoadJournal(prefs).crashedKey())
    }

    /**
     * After a crash is recorded, a SUCCESSFUL load must not silently erase it before anyone read it —
     * `finish` clears the in-flight marker, which is a different key from the crashed one on purpose.
     */
    @Test
    fun `a later successful load does not erase an unacknowledged crash`() = runTest {
        val prefs = FakePreferenceStore()
        PreferenceNativeLoadJournal(prefs).begin("gemma-3n-e4b")

        val next = PreferenceNativeLoadJournal(prefs)
        assertEquals("gemma-3n-e4b", next.crashedKey())
        next.around("whisper-tiny") { }

        assertEquals("gemma-3n-e4b", next.crashedKey(), "the crash outlives an unrelated success")
    }

    @Test
    fun `the bootstrap reports once and then acknowledges`() = runTest {
        val prefs = FakePreferenceStore()
        PreferenceNativeLoadJournal(prefs).begin("litert-gemma")
        val j = PreferenceNativeLoadJournal(prefs)

        val seen = mutableListOf<String>()
        val bootstrap = NativeCrashReportBootstrap(j) { seen += it }
        bootstrap.start()
        bootstrap.start()

        assertEquals(listOf("litert-gemma"), seen, "a crash is reported once, not on every launch")
    }

    @Test
    fun `the bootstrap says nothing when the last run was clean`() = runTest {
        val j = journal()
        j.around("whisper-tiny") { }

        val seen = mutableListOf<String>()
        NativeCrashReportBootstrap(j) { seen += it }.start()

        assertEquals(emptyList(), seen)
    }
}
