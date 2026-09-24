package com.sabreware.aide.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.boolKey
import com.sabreware.aide.core.common.prefs.enumKey
import com.sabreware.aide.core.common.prefs.peek
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path

/**
 * The prefs store every surface paints its first frame from — app, keyboard, assistant, desktop window.
 *
 * What is pinned is the startup contract, over a REAL DataStore file: a value written by one process is
 * available to the next through [PreferenceStore.peek] as soon as [PreferenceStore.awaitLoaded] returns, with
 * no collector — which is what lets a host hold its first draw on the store and paint the user's theme
 * instead of the default one.
 */
class DataStorePreferenceStoreTest {

    private enum class Mode { Light, Dark }

    private val theme = enumKey("theme", default = Mode.Light)
    private val flag = boolKey("flag", default = true)

    // DataStore allows one live store per path per process: a file of its own per test instance.
    private val file: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "prefs-${Random.nextLong()}.preferences_pb"
    private var process: Job? = null

    @AfterTest fun cleanUp() = FileSystem.SYSTEM.delete(file, mustExist = false)

    /** A store as a NEW PROCESS sees it: the previous one is shut down first and this one reads the disk. */
    private suspend fun TestScope.launchProcess(): PreferenceStore {
        process?.cancelAndJoin()
        val job = Job(backgroundScope.coroutineContext[Job])
        process = job
        val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { file }
        return DataStorePreferenceStore(dataStore, scope)
    }

    @Test
    fun `peek is the default until the first read lands, then the stored value`() = runTest {
        launchProcess().set(theme, Mode.Dark)

        val next = launchProcess()
        next.awaitLoaded()

        assertTrue(next.isLoaded)
        assertEquals(Mode.Dark, next.peek(theme), "the next launch paints Dark on its first frame")
        assertEquals(true, next.peek(flag), "an unset key peeks its default")
    }

    @Test
    fun `peek follows a write without anyone collecting`() = runTest {
        val store = launchProcess()
        store.awaitLoaded()

        store.set(flag, false)
        store.flow(flag).first { !it } // the write has been observed by the store's own snapshot

        assertFalse(store.peek(flag))
    }

    @Test
    fun `get reads the store, so a read-modify-write never folds a stale snapshot`() = runTest {
        val store = launchProcess()
        store.update(flag) { !it }
        store.update(flag) { !it }

        assertEquals(true, store.get(flag))
    }
}
