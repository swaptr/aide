package com.sabreware.aide.app.data.model

import com.sabreware.aide.core.domain.chat.Chat
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.presence.HiddenWorkPolicy
import com.sabreware.aide.core.domain.presence.SurfacePresence
import com.sabreware.aide.core.domain.device.DeviceInfo
import com.sabreware.aide.core.domain.model.NativeLoadJournal
import com.sabreware.aide.core.domain.model.InsufficientMemoryException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import com.sabreware.aide.core.domain.model.Residency
import com.sabreware.aide.core.domain.model.ResidentModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Shared probe so two models can observe whether their loads overlap. */
private class LoadProbe {
    val inFlight = AtomicInteger(0)
    @Volatile var maxInFlight = 0
}

private class FakeModel(
    override val key: String,
    override val modality: Modality = Modality.Chat,
    override val residency: Residency = Residency.LOADED,
    private val estimate: Long = 0L,
    private val loadDelayMs: Long = 0L,
    private val probe: LoadProbe? = null,
    private val failLoads: Int = 0,
) : ResidentModel {
    var loadCount = 0
        private set
    var closeCount = 0
        private set
    @Volatile var loaded = false
        private set
    private var failsLeft = failLoads

    override fun memoryEstimateBytes(): Long = estimate

    override suspend fun load() {
        if (failsLeft > 0) {
            failsLeft -= 1
            throw IllegalStateException("boom")
        }
        val n = probe?.inFlight?.incrementAndGet() ?: 0
        try {
            if (probe != null && n > probe.maxInFlight) probe.maxInFlight = n
            if (loadDelayMs > 0) delay(loadDelayMs)
            loadCount += 1
            loaded = true
        } finally {
            probe?.inFlight?.decrementAndGet()
        }
    }

    override suspend fun close() {
        closeCount += 1
        loaded = false
    }
}

private const val TRIM_CRITICAL = 15
private const val TRIM_MODERATE = 5

class ResidencyManagerImplTest {

    // A scope backed by the test's virtual-time scheduler so advanceTimeBy/advanceUntilIdle drive the
    // manager's idle/trim timers. Its own root Job (detached from the test coroutine) so leftover
    // timers don't trip runTest's uncompleted-coroutine check.
    private fun TestScope.newManager(
        availableBytes: Long = Long.MAX_VALUE / 4,
        totalBytes: Long = Long.MAX_VALUE / 4,
        headroomFraction: Double = 0.0,
        presence: SurfacePresence = SurfacePresence(HiddenWorkPolicy.KeepRunning),
    ) = ResidencyManagerImpl(
        CoroutineScope(StandardTestDispatcher(testScheduler)),
        TRIM_CRITICAL,
        deviceInfo = FakeDeviceInfo(availableBytes, totalBytes),
        headroomFraction = headroomFraction,
        journal = RecordingJournal(),
        presence = presence,
    )

    /** Memory the test dictates. Effectively unlimited by default so existing cases are unaffected. */
    private class FakeDeviceInfo(
        override val availableRamBytes: Long,
        override val totalRamBytes: Long,
    ) : DeviceInfo {
        override val totalRamGb: Int = (totalRamBytes / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
    }

    /** In-memory journal — the durable one is covered by PreferenceNativeLoadJournalTest. */
    private class RecordingJournal : NativeLoadJournal {
        val began = mutableListOf<String>()
        var inFlight: String? = null
        override suspend fun begin(key: String) { began += key; inFlight = key }
        override suspend fun finish() { inFlight = null }
        override suspend fun crashedKey(): String? = inFlight
        override suspend fun acknowledge() { inFlight = null }
    }

    @Test
    fun acquire_loads_once_then_keepAlive_closes() = runTest {
        val mgr = newManager()
        val m = FakeModel("local:gemma")

        val h = mgr.acquire(m)
        assertEquals(1, m.loadCount)
        assertTrue(m.loaded)
        assertEquals(1, mgr.residents().single().refCount)

        h.release(keepAliveMs = 1_000)
        advanceTimeBy(500)
        assertEquals("must not close while keepAlive pending", 0, m.closeCount)

        advanceUntilIdle()
        assertEquals(1, m.closeCount)
        assertTrue("evicted slot dropped from table", mgr.residents().isEmpty())
    }

    @Test
    fun same_key_shares_one_refcount_and_one_load() = runTest {
        val mgr = newManager()
        val m = FakeModel("local:gemma")

        val h1 = mgr.acquire(m)
        val h2 = mgr.acquire(m)
        assertEquals("loaded once for two holds", 1, m.loadCount)
        assertEquals(2, mgr.residents().single().refCount)

        h1.release(keepAliveMs = 1_000)
        advanceUntilIdle()
        assertEquals("still held by h2 — not closed", 0, m.closeCount)

        h2.release(keepAliveMs = 1_000)
        advanceUntilIdle()
        assertEquals(1, m.closeCount)
    }

    @Test
    fun none_residency_returns_noop_handle() = runTest {
        val mgr = newManager()
        val m = FakeModel("ollama:llama", residency = Residency.NONE)

        val h = mgr.acquire(m)
        assertEquals("NONE still loads (e.g. remote wire-marker), idempotently", 1, m.loadCount)
        assertTrue("NONE is never tracked as a resident", mgr.residents().isEmpty())

        h.release()
        advanceUntilIdle()
        assertEquals("NONE is never closed/evicted by the manager", 0, m.closeCount)
    }

    @Test
    fun reacquire_within_keepAlive_cancels_pending_close() = runTest {
        val mgr = newManager()
        val m = FakeModel("local:gemma")

        mgr.acquire(m).release(keepAliveMs = 1_000)
        advanceTimeBy(500)                 // partway through keepAlive
        val h2 = mgr.acquire(m)            // re-summon

        advanceUntilIdle()
        assertEquals("re-acquire kept it warm — no reload", 1, m.loadCount)
        assertEquals("re-acquire cancelled the pending close", 0, m.closeCount)

        h2.release(keepAliveMs = 1_000)
        advanceUntilIdle()
        assertEquals(1, m.closeCount)
    }

    @Test
    fun trim_evicts_unheld_but_spares_held() = runTest {
        val mgr = newManager()
        val held = FakeModel("local:gemma", modality = Modality.Chat)
        val unheld = FakeModel("sherpa:whisper", modality = Modality.Asr)

        mgr.acquire(held)                              // refCount stays 1
        mgr.acquire(unheld).release(keepAliveMs = 60_000)  // unheld, long keepAlive

        mgr.onTrimMemory(TRIM_CRITICAL)
        advanceUntilIdle()

        assertEquals("unheld resident evicted under pressure", 1, unheld.closeCount)
        assertEquals("held resident spared", 0, held.closeCount)
        assertEquals("only the held model remains", "local:gemma", mgr.residents().single().key)
    }

    @Test
    fun trim_below_threshold_is_noop() = runTest {
        val mgr = newManager()
        val m = FakeModel("sherpa:whisper")
        mgr.acquire(m).release(keepAliveMs = 60_000)

        mgr.onTrimMemory(TRIM_MODERATE)
        runCurrent()   // drain any work at t=0 WITHOUT advancing past the 60s keepAlive
        assertEquals("below threshold keeps weights hot", 0, m.closeCount)
    }

    @Test
    fun failed_load_rolls_back_and_is_retryable() = runTest {
        val mgr = newManager()
        val m = FakeModel("local:gemma", failLoads = 1)

        try {
            mgr.acquire(m)
            fail("acquire should rethrow the load failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertTrue("failed load pins no phantom resident", mgr.residents().isEmpty())

        val h = mgr.acquire(m)   // second load succeeds
        assertEquals(1, m.loadCount)
        assertEquals(1, mgr.residents().single().refCount)
        h.release()
    }

    @Test
    fun loads_are_serialized_across_keys() = runTest {
        val mgr = newManager()
        val probe = LoadProbe()
        val a = FakeModel("local:a", loadDelayMs = 100, probe = probe)
        val b = FakeModel("sherpa:b", modality = Modality.Asr, loadDelayMs = 100, probe = probe)

        launch { mgr.acquire(a) }
        launch { mgr.acquire(b) }
        advanceUntilIdle()

        assertEquals(1, a.loadCount)
        assertEquals(1, b.loadCount)
        assertEquals("native loads must not overlap", 1, probe.maxInFlight)
    }

    @Test
    fun nested_acquire_does_not_deadlock() = runTest {
        val mgr = newManager()
        // The voice loop holds chat, then acquires asr→vad→tts while still holding chat.
        val chat = FakeModel("local:gemma", modality = Modality.Chat)
        val asr = FakeModel("sherpa:whisper", modality = Modality.Asr)
        val vad = FakeModel("sherpa:silero", modality = Modality.Vad)

        val hChat = mgr.acquire(chat)
        val hAsr = mgr.acquire(asr)      // would hang if the load queue were held across handles
        val hVad = mgr.acquire(vad)

        assertEquals(3, mgr.residents().size)
        assertTrue(chat.loaded && asr.loaded && vad.loaded)

        hVad.release(); hAsr.release(); hChat.release()
        advanceUntilIdle()
        assertNull(mgr.residents().firstOrNull { it.refCount > 0 })
    }

    // --- Admission: deciding BEFORE the load, which is the half onTrimMemory cannot do -------------------

    private fun mb(n: Long) = n * 1024L * 1024L

    @Test
    fun admission_refuses_a_model_larger_than_the_device_can_hold() = runTest {
        val mgr = newManager(availableBytes = mb(800), totalBytes = mb(4000))
        val huge = FakeModel("local:gemma-27b", estimate = mb(6000))

        val e = assertThrows(InsufficientMemoryException::class.java) { runBlocking { mgr.acquire(huge) } }

        assertEquals("the load must never be attempted — that is the whole point", 0, huge.loadCount)
        assertEquals("local:gemma-27b", e.modelKey)
        assertTrue("the shortfall is reported", e.shortfallBytes > 0)
    }

    @Test
    fun admission_evicts_an_unheld_resident_to_make_room() = runTest {
        val mgr = newManager(availableBytes = mb(1000), totalBytes = mb(4000))
        val small = FakeModel("local:whisper", estimate = mb(900))
        val next = FakeModel("local:gemma", estimate = mb(1500))

        mgr.acquire(small).release(keepAliveMs = 0)
        advanceUntilIdle()
        // Still resident: keepAlive 0 closes it, so re-acquire and hold it unreleased-but-unheld instead.
        mgr.acquire(small).release(keepAliveMs = 60_000)

        mgr.acquire(next)
        advanceUntilIdle()

        assertTrue("the incoming model loaded", next.loaded)
        assertTrue("the idle resident was evicted to pay for it", small.closeCount > 0)
    }

    @Test
    fun admission_never_evicts_a_model_someone_is_using() = runTest {
        // 2000 total − 900 held = 1100 of budget: the 1500 MB model cannot fit while whisper is in use.
        val mgr = newManager(availableBytes = mb(1000), totalBytes = mb(2000))
        val held = FakeModel("local:whisper", estimate = mb(900))
        val next = FakeModel("local:gemma", estimate = mb(1500))

        val hold = mgr.acquire(held)   // never released

        assertThrows(InsufficientMemoryException::class.java) { runBlocking { mgr.acquire(next) } }
        assertEquals("a held model is not memory we have", 0, held.closeCount)
        hold.release(keepAliveMs = 0)
    }

    @Test
    fun admission_ignores_a_model_that_reports_no_size() = runTest {
        val mgr = newManager(availableBytes = mb(1), totalBytes = mb(4000))
        val unknown = FakeModel("remote:sonnet", estimate = 0L)

        mgr.acquire(unknown)

        assertTrue("an unknown size is admitted rather than blocking every unsized model", unknown.loaded)
    }

    // ── Hidden surfaces free their models ────────────────────────────────────────────────────────────────

    @Test
    fun a_release_from_a_hidden_surface_frees_the_model_at_once() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree)
        val mgr = newManager(presence = presence)
        val m = FakeModel("local:gemma")

        val hold = mgr.acquire(m, Surface.CHAT)   // the chat was never shown: its turn was cancelled on hide
        hold.release(keepAliveMs = 5 * 60_000L)
        advanceTimeBy(1)

        assertEquals("the chat keepAlive does not apply to a surface nobody can see", 1, m.closeCount)
    }

    @Test
    fun hiding_a_surface_frees_the_idle_models_it_used() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree).apply { shown(Surface.CHAT) }
        val mgr = newManager(presence = presence)
        val m = FakeModel("local:gemma")

        mgr.acquire(m, Surface.CHAT).release(keepAliveMs = 5 * 60_000L)
        advanceTimeBy(60_000L)
        assertEquals("visible: the model stays warm for the next message", 0, m.closeCount)

        presence.hidden(Surface.CHAT)
        advanceTimeBy(1)
        assertEquals("the app went to the background: the weights leave memory now", 1, m.closeCount)
    }

    @Test
    fun a_model_in_use_is_never_freed_by_a_hide() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree).apply { shown(Surface.CHAT) }
        val mgr = newManager(presence = presence)
        val m = FakeModel("local:gemma")

        val hold = mgr.acquire(m, Surface.CHAT)
        presence.hidden(Surface.CHAT)
        advanceUntilIdle()
        assertEquals("the hold's owner stops its own work; the manager never yanks a held model", 0, m.closeCount)

        hold.release()
        advanceTimeBy(1)
        assertEquals("and the release that follows frees it", 1, m.closeCount)
    }

    @Test
    fun another_visible_surface_does_not_keep_a_hidden_surfaces_model() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree).apply {
            shown(Surface.CHAT)
            shown(Surface.IME)
        }
        val mgr = newManager(presence = presence)
        val m = FakeModel("local:gemma")
        mgr.acquire(m, Surface.CHAT).release(keepAliveMs = 5 * 60_000L)

        presence.hidden(Surface.CHAT)
        advanceTimeBy(1)

        assertEquals("typing in another app's field does not pin the chat's model", 1, m.closeCount)
    }

    @Test
    fun a_shared_model_stays_while_any_surface_is_visible() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.StopAndFree).apply {
            shown(Surface.CHAT)
            shown(Surface.IME)
        }
        val mgr = newManager(presence = presence)
        val stt = FakeModel("sherpa:asr", modality = Modality.Asr)
        mgr.acquire(stt).release(keepAliveMs = 60_000L)   // speech: no single owner

        presence.hidden(Surface.CHAT)
        advanceTimeBy(1_000L)
        assertEquals("the keyboard can still dictate", 0, stt.closeCount)

        presence.hidden(Surface.IME)
        advanceTimeBy(1)
        assertEquals("nothing visible: freed", 1, stt.closeCount)
    }

    @Test
    fun a_host_that_keeps_running_honours_the_callers_keepAlive() = runTest {
        val presence = SurfacePresence(HiddenWorkPolicy.KeepRunning)
        val mgr = newManager(presence = presence)
        val m = FakeModel("local:gemma")

        mgr.acquire(m, Surface.CHAT).release(keepAliveMs = 1_000L)
        advanceTimeBy(500L)
        assertEquals(0, m.closeCount)
        advanceUntilIdle()
        assertEquals(1, m.closeCount)
    }

    @Test
    fun a_release_from_a_cancelled_turn_still_drops_its_hold() = runTest {
        val mgr = newManager()
        val m = FakeModel("local:gemma")
        val hold = mgr.acquire(m)

        // The turn is cancelled and releases from its onCompletion while the state lock is contended.
        val blocker = mgr.acquire(FakeModel("local:other"))
        val releasing = launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                hold.release(keepAliveMs = 0)
            }
        }
        runCurrent()
        releasing.cancel()
        advanceUntilIdle()

        assertEquals("a cancelled caller's release is not lost", 1, m.closeCount)
        blocker.release(keepAliveMs = 0)
    }

    // ── One engine, one model at a time: the engine's word beats the slot's flag ─────────────────────────

    /** A single-model engine, like LiteRT: loading one model drops whichever was there. */
    private class OneModelEngine {
        var loaded: String? = null
        val closed = mutableListOf<String>()
    }

    private class EngineBackedModel(override val key: String, private val engine: OneModelEngine) : ResidentModel {
        override val modality = Modality.Chat
        override val residency = Residency.LOADED
        var loads = 0
        override fun isResident() = engine.loaded == key
        override suspend fun load() {
            if (engine.loaded != key) {
                engine.loaded = key
                loads++
            }
        }
        // Targeted, as LlmResidentModel's is: frees this model only if the engine still holds it.
        override suspend fun close() {
            if (engine.loaded == key) {
                engine.loaded = null
                engine.closed += key
            }
        }
    }

    @Test
    fun an_idle_model_the_engine_replaced_never_frees_its_replacement() = runTest {
        val mgr = newManager()
        val engine = OneModelEngine()
        val a = EngineBackedModel("local:a", engine)
        val b = EngineBackedModel("local:b", engine)

        mgr.acquire(a).release(keepAliveMs = 60_000L)   // chatted on A
        val holdB = mgr.acquire(b)                      // switched to B: the engine dropped A on its own

        advanceTimeBy(60_001L)                          // A's idle timer fires

        assertEquals("B is still loaded under its holder", "local:b", engine.loaded)
        assertTrue("nothing freed B", "local:b" !in engine.closed)
        holdB.release(keepAliveMs = 0)
    }

    @Test
    fun a_slot_the_engine_no_longer_backs_is_reloaded_on_acquire() = runTest {
        val mgr = newManager()
        val engine = OneModelEngine()
        val a = EngineBackedModel("local:a", engine)
        val b = EngineBackedModel("local:b", engine)

        mgr.acquire(a).release(keepAliveMs = 60_000L)
        mgr.acquire(b).release(keepAliveMs = 60_000L)   // A dropped by the engine, A's slot still says loaded

        mgr.acquire(a)

        assertEquals("A is loaded again rather than handed out as if it were resident", "local:a", engine.loaded)
        assertEquals(2, a.loads)
    }

    @Test
    fun a_cancel_during_a_native_load_leaves_the_model_tracked_and_freed_later() = runTest {
        val mgr = newManager()
        val m = FakeModel("local:gemma", loadDelayMs = 1_000L)

        val acquiring = launch { mgr.acquire(m, Surface.CHAT) }
        advanceTimeBy(500L)
        acquiring.cancel()                  // the user pressed Stop mid-load
        advanceTimeBy(600L)
        runCurrent()

        assertTrue("the load it could not interrupt finished", m.loaded)
        assertEquals("and is still tracked, not orphaned", 1, mgr.residents().size)
        assertEquals(0, mgr.residents().single().refCount)

        advanceUntilIdle()
        assertEquals("the idle timer frees it like any released model", 1, m.closeCount)
    }
}
