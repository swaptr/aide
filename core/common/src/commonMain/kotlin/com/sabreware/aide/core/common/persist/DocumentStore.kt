package com.sabreware.aide.core.common.persist

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Clock

/**
 * A [PersistedDocument] held in memory for the life of the process and written through atomically.
 *
 * The engine is typed DataStore over okio (`datastore-core-okio`, already on every target through the
 * preferences artifact), not a new file format: it brings temp-file + rename writes, one writer per file per
 * process, serialized read-modify-write and a corruption hook. This class adds only what the documents need
 * on top — the "not yet" state, the durability policy, and a retry for hosts whose rename can be refused
 * transiently.
 *
 * [state] starts reading the moment the store is constructed. The file is small and local, so on any warm
 * disk it is [DocState.Ready] well before the first frame; a surface that must not paint a guess waits on
 * [awaitReady] instead of reading a seed.
 *
 * **One store per file per process.** DataStore throws if two instances open the same path; bind each
 * document once, as a singleton.
 */
class DocumentStore<T>(
    val document: PersistedDocument<T>,
    private val fileSystem: FileSystem,
    /** Absolute path of the document's file; its directory is created on first write. */
    val path: Path,
    /** App-lifetime scope on an I/O dispatcher: the read runs here, and DataStore's actor lives here. */
    scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onProblem: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val dataStore: DataStore<T> = DataStoreFactory.create(
        storage = OkioStorage(fileSystem, DocumentSerializer(document)) { path },
        corruptionHandler = ReplaceFileCorruptionHandler { e -> onCorrupt(e) },
        scope = scope,
    )

    /** [DocState.Loading] until the first read lands; every write after that re-emits. */
    val state: StateFlow<DocState<T>> = dataStore.data
        .map<T, DocState<T>> { DocState.Ready(it) }
        // A read that fails for a reason other than corruption (permissions, a volume not mounted yet) must not
        // END the flow — a completed flow would never show a later write — so it keeps retrying with backoff,
        // and the first read that succeeds makes the stream live again. Meanwhile the default stands in only
        // where it cannot be mistaken for an answer ([PersistedDocument.defaultWhileUnreadable]); otherwise the
        // state stays Loading. Nothing is written here, so a file the process could not read is left as it was.
        .retryWhen { cause, attempt ->
            if (cause !is IOException) return@retryWhen false
            if (attempt == 0L) {
                onProblem("$document: read failed, retrying", cause)
                if (document.defaultWhileUnreadable) emit(DocState.Ready(document.default))
            }
            delay(READ_RETRY_MS[attempt.coerceAtMost(READ_RETRY_MS.lastIndex.toLong()).toInt()])
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, DocState.Loading)

    /** The current value, or null while [state] is still [DocState.Loading]. Never blocks. */
    val valueOrNull: T? get() = (state.value as? DocState.Ready)?.value

    /** Suspends until the first read has landed (usually already true), then returns the value. */
    suspend fun awaitReady(): T = state.filterIsInstance<DocState.Ready<T>>().first().value

    /**
     * Atomic read-modify-write against the file's CURRENT content — never against [state]'s replay value,
     * which can be one write behind (the rule CLAUDE.md states for `PreferenceStore.update`).
     *
     * A transient `IOException` from the replace is retried: on Windows an indexer or antivirus can hold the
     * target for a moment and refuse the rename. After the retries the write is reported through `onProblem`
     * and null is returned — NOT thrown: callers run in UI scopes, where a throw is a crash. Nothing is lost
     * silently either: [state] only moves on a successful write, so a pick that could not be saved visibly
     * does not take, and the user can simply make it again.
     */
    suspend fun update(transform: suspend (T) -> T): T? {
        var attempt = 0
        while (true) {
            try {
                return dataStore.updateData(transform)
            } catch (e: CorruptionException) {
                throw e
            } catch (e: IOException) {
                if (attempt < RETRY_DELAYS_MS.size) {
                    delay(RETRY_DELAYS_MS[attempt++])
                    continue
                }
                onProblem("$document: write failed after ${attempt + 1} attempts", e)
                return null
            }
        }
    }

    private fun onCorrupt(e: CorruptionException): T {
        if (document.durability == Durability.Intent) {
            val backup = path.parent?.let { it / "${document.name}.corrupt-${now()}.json" }
            if (backup != null) {
                runCatching { fileSystem.copy(path, backup) }
                    .onFailure { onProblem("$document: could not keep corrupt copy", it) }
            }
            onProblem("$document: unreadable, reset to default (copy kept at $backup)", e)
        } else {
            onProblem("$document: unreadable cache, reset", e)
        }
        return document.default
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(50, 200)
        val READ_RETRY_MS = longArrayOf(500, 2_000, 10_000, 30_000)
    }
}

/** A document's load state. "Not read yet" is a member of the type, never a default that reads as data. */
sealed interface DocState<out T> {
    data object Loading : DocState<Nothing>
    data class Ready<T>(val value: T) : DocState<T>
}
