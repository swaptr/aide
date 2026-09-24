package com.sabreware.aide.core.common.prefs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Key/value store addressed by typed [PrefKey]s. One instance over one file; [Tier] separates settings from
 * view state as policy, not storage.
 *
 * Every call site goes through this interface, so moving a tier to its own file — or to a Room table if the
 * write rate ever demands per-row writes — stays a DI change rather than a refactor.
 */
interface PreferenceStore {
    /** Current value, then every change. Always emits (falls back to [PrefKey.default]); never throws. */
    fun <T> flow(key: PrefKey<T>): Flow<T>

    suspend fun <T> get(key: PrefKey<T>): T

    /**
     * Every committed snapshot, current first — for a state built from several keys at once ([select]).
     * One snapshot answers every key, so a multi-key state never mixes two commits.
     */
    val snapshots: Flow<PrefSnapshot>

    /**
     * The snapshot as of the store's last read this session, synchronously — every key's default until the
     * first read lands ([isLoaded]). For PAINTING only: seeding a first frame so a toggle or the theme does
     * not draw its default and then flip. Never the read half of a read-modify-write — that is [update].
     */
    val current: PrefSnapshot

    /** Whether the store's first read has landed, i.e. whether [current] is an answer rather than the defaults. */
    val isLoaded: Boolean

    /** Suspends until [isLoaded]. For a host that holds its first frame on the values it paints with. */
    suspend fun awaitLoaded()

    suspend fun <T> set(key: PrefKey<T>, value: T)

    /** Read-modify-write in one atomic edit — safe against a concurrent writer, unlike get-then-set. */
    suspend fun <T> update(key: PrefKey<T>, block: (T) -> T)

    suspend fun remove(key: PrefKey<*>)

    /** Forget every key in [tier] — "reset my layout" / "reset settings". */
    suspend fun clear(tier: Tier)
}

/** One consistent read of the store: every key answered from the same commit. Reads are total. */
interface PrefSnapshot {
    operator fun <T> get(key: PrefKey<T>): T

    /** Every key at its default — what an unreadable store reads as. */
    object Defaults : PrefSnapshot {
        override fun <T> get(key: PrefKey<T>): T = key.default
    }
}

/** [key] as of the last read — paint-only, see [PreferenceStore.current]. */
fun <T> PreferenceStore.peek(key: PrefKey<T>): T = current[key]

/**
 * A state derived from several prefs, re-derived per commit — [build] runs over one snapshot, so it is the
 * single definition of the state for the first frame ([PreferenceStore.current]) and every update alike.
 */
fun <S> PreferenceStore.select(build: (PrefSnapshot) -> S): Flow<S> =
    snapshots.map(build).distinctUntilChanged()

/**
 * THE shape for a prefs-backed state: [build] once, seeded from [PreferenceStore.current] so the first frame
 * paints the stored values, not the defaults, and an unreadable store degrades to the defaults instead of
 * cancelling the sharing coroutine. Combine with non-pref inputs through [select] instead.
 */
fun <S> PreferenceStore.selectState(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
    build: (PrefSnapshot) -> S,
): StateFlow<S> =
    select(build)
        .catch { emit(build(PrefSnapshot.Defaults)) }
        .stateIn(scope, started, build(current))
