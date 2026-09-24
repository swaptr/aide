package com.sabreware.aide.desktop.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A second desktop instance must be refused, because DataStore only guards a single writer within one
 * process. A second holder in the same JVM exercises the same path the OS takes for a second process
 * (`OverlappingFileLockException` rather than a null `tryLock()`, but both mean "held").
 */
class SingleInstanceLockTest {

    @Test
    fun `a second acquire is refused until the first is released`() {
        val dir = Files.createTempDirectory("aide-lock").toFile()
        try {
            val first = assertNotNull(SingleInstanceLock.acquire(dir))
            assertNull(SingleInstanceLock.acquire(dir), "a second instance acquired a held lock")

            first.release()
            val again = assertNotNull(SingleInstanceLock.acquire(dir), "the lock was not freed by release()")
            again.release()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `acquire creates a missing data directory`() {
        val parent = Files.createTempDirectory("aide-lock").toFile()
        try {
            val lock = assertNotNull(SingleInstanceLock.acquire(parent.resolve("not/yet/there")))
            lock.release()
        } finally {
            parent.deleteRecursively()
        }
    }
}
