package com.sabreware.aide.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The arithmetic that decides whether a model load is attempted at all.
 *
 * It exists because the eviction path answers the wrong question: `onTrimMemory` fires after the system is
 * already short, which means a model too large for the device gets loaded first and the process is killed
 * second. The user sees a crash rather than a reason. Every case below is one of those reasons.
 *
 * Numbers are in MB for legibility; the unit is bytes.
 */
class MemoryAdmissionTest {

    private fun mb(n: Long) = n * 1024L * 1024L

    @Test
    fun `a model well inside free memory is admitted`() {
        val verdict = MemoryAdmission.check(
            requiredBytes = mb(500),
            availableBytes = mb(4000),
            totalBytes = mb(8000),
        )
        assertIs<MemoryAdmission.Verdict.Fits>(verdict)
    }

    /**
     * Low free memory is not a refusal. A phone keeps cached apps resident until something needs the room,
     * and the host's "available" does not count them: a 1.7 GB model on a 6 GB phone showing 1.7 GB free
     * was refused as "1419 MB short" with nothing else loaded.
     */
    @Test
    fun `low free memory on a device that can hold the model is admitted`() {
        val verdict = MemoryAdmission.check(
            requiredBytes = mb(1748),
            availableBytes = mb(1726),
            totalBytes = mb(5600),
            headroomFraction = 0.25,
        )
        assertIs<MemoryAdmission.Verdict.Fits>(verdict)
    }

    /** Headroom is withheld from TOTAL: 8000 total at 25% leaves a 6000 budget for weights. */
    @Test
    fun `headroom is withheld from the device budget`() {
        val verdict = MemoryAdmission.check(
            requiredBytes = mb(6500),
            availableBytes = mb(7000),
            totalBytes = mb(8000),
            headroomFraction = 0.25,
        )
        assertIs<MemoryAdmission.Verdict.TooLarge>(verdict)
        assertEquals(mb(6000), verdict.availableBytes)
        assertEquals(mb(500), verdict.shortfallBytes)
    }

    @Test
    fun `a free-memory shortfall asks our idle residents to pay the debt first`() {
        val verdict = MemoryAdmission.check(
            requiredBytes = mb(2000),
            availableBytes = mb(400),
            reclaimableBytes = mb(2000),
            totalBytes = mb(8000),
            headroomFraction = 0.25,
        )
        assertIs<MemoryAdmission.Verdict.Evict>(verdict)
        assertEquals(mb(1600), verdict.bytesToReclaim)
    }

    @Test
    fun `residents in use shrink the budget`() {
        val verdict = MemoryAdmission.check(
            requiredBytes = mb(2500),
            availableBytes = mb(3000),
            heldBytes = mb(2000),
            totalBytes = mb(5600),
            headroomFraction = 0.25,
        )
        assertIs<MemoryAdmission.Verdict.TooLarge>(verdict)
        // 5600 − 1400 headroom − 2000 held = 2200 budget → 300 short.
        assertEquals(mb(300), verdict.shortfallBytes)
    }

    @Test
    fun `a model larger than the device is refused`() {
        val verdict = MemoryAdmission.check(
            requiredBytes = mb(6000),
            availableBytes = mb(1200),
            reclaimableBytes = mb(800),
            totalBytes = mb(4000),
            headroomFraction = 0.25,
        )
        assertIs<MemoryAdmission.Verdict.TooLarge>(verdict)
        // budget = 4000 − 1000 = 3000 → 3000 short.
        assertEquals(mb(3000), verdict.shortfallBytes)
        assertEquals(mb(6000), verdict.requiredBytes)
    }

    /**
     * An unknown size must never block a load. Plenty of models report no size, and refusing those would
     * make the guard worse than not having it — the failure it prevents is rarer than the one it would cause.
     */
    @Test
    fun `an unknown or zero size is always admitted`() {
        assertIs<MemoryAdmission.Verdict.Fits>(
            MemoryAdmission.check(requiredBytes = 0L, availableBytes = 0L, totalBytes = mb(4000)),
        )
        assertIs<MemoryAdmission.Verdict.Fits>(
            MemoryAdmission.check(requiredBytes = -1L, availableBytes = 0L, totalBytes = mb(4000)),
        )
    }

    /** A host that cannot report a total gets headroom as a fraction of what is free — never a crash. */
    @Test
    fun `a host with no total reading still decides`() {
        val verdict = MemoryAdmission.check(requiredBytes = mb(100), availableBytes = mb(1000))
        assertIs<MemoryAdmission.Verdict.Fits>(verdict)
    }

    @Test
    fun `the exact budget boundary is admitted, one byte over is not`() {
        val exact = MemoryAdmission.check(
            requiredBytes = mb(6000),
            availableBytes = mb(8000),
            totalBytes = mb(8000),
            headroomFraction = 0.25,
        )
        assertIs<MemoryAdmission.Verdict.Fits>(exact)

        val overBy1 = MemoryAdmission.check(
            requiredBytes = mb(6000) + 1,
            availableBytes = mb(8000),
            totalBytes = mb(8000),
            headroomFraction = 0.25,
        )
        assertTrue(overBy1 !is MemoryAdmission.Verdict.Fits, "one byte past the budget is not a fit")
    }

    @Test
    fun `the exception carries the numbers a user-facing message needs`() {
        val e = InsufficientMemoryException(
            modelKey = "gemma-3n-e4b",
            requiredBytes = mb(4100),
            availableBytes = mb(1300),
            shortfallBytes = mb(2800),
        )
        val message = e.message.orEmpty()
        assertTrue("gemma-3n-e4b" in message, "the model is named: $message")
        assertTrue("4100" in message && "1300" in message, "the sizes are in MB, not bytes: $message")
    }
}
