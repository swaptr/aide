package com.sabreware.aide.desktop.storage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * One desktop instance per data directory.
 *
 * DataStore enforces a single writer only within ONE process: two DataStores over the same file in the same
 * JVM throw, but two JVMs each get their own and silently race. Everything under
 * [DesktopAppDirs.dataPath] — preferences, persisted documents, the database — assumes it is the only
 * writer, so a second launch would interleave whole-file rewrites and lose whichever commit landed first.
 *
 * The lock is a `FileChannel.tryLock()` on `.instance.lock` in that directory. On POSIX it is advisory, but
 * every instance takes it the same way, which is all this needs; on Windows it is mandatory. The OS drops it
 * when the process dies, so a crash never leaves a stale lock behind — unlike a PID file.
 *
 * Held for the life of the process: nothing releases it outside tests.
 */
class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) {
    /** Releases the lock and closes the channel. For tests; the app holds the lock until it exits. */
    fun release() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val LOCK_FILE = ".instance.lock"

        /**
         * Takes the lock on [dataDir], creating the directory if needed. Returns null when another instance
         * already holds it — including another holder in this same JVM, which surfaces as
         * [OverlappingFileLockException] rather than a null `tryLock()`.
         */
        fun acquire(dataDir: File): SingleInstanceLock? {
            dataDir.mkdirs()
            val channel = RandomAccessFile(File(dataDir, LOCK_FILE), "rw").channel
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                channel.close()
                throw e
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return SingleInstanceLock(channel, lock)
        }
    }
}
