package com.sabreware.aide.core.domain.presence

import com.sabreware.aide.core.domain.llm.Surface
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * What a target does with work and memory that belong to a surface nobody can see. Data, not a class per
 * platform: a phone stops and frees, a desktop window keeps going.
 *
 * @param stopWorkOnHide a surface's in-flight generation is cancelled the moment it hides. The partial
 *   answer is kept; the user regenerates it if they still want it.
 * @param hiddenKeepAliveMs once the surface that last used a model is hidden, the model's idle timer is cut
 *   to this (0 = freed as soon as nothing holds it). `null` keeps each caller's own keepAlive.
 */
data class HiddenWorkPolicy(
    val stopWorkOnHide: Boolean,
    val hiddenKeepAliveMs: Long?,
) {
    companion object {
        /** Phones: memory is scarce, and someone who left the surface does not want the answer. */
        val StopAndFree = HiddenWorkPolicy(stopWorkOnHide = true, hiddenKeepAliveMs = 0L)

        /** Hosts where a hidden window is still a running app (desktop). */
        val KeepRunning = HiddenWorkPolicy(stopWorkOnHide = false, hiddenKeepAliveMs = null)
    }
}

/**
 * Which surfaces the user can see right now: the app's own window, the keyboard, the assistant overlay.
 *
 * The app's lifecycle alone cannot answer this, because the keyboard and the assistant run while the app's
 * activity is stopped. So each surface reports itself from its own OS callbacks, and everything that holds
 * heavy state (the residency manager, a chat turn, a keyboard transform) reacts to what it is told here.
 * Nothing below the surfaces knows which surfaces exist; they only see [Surface] values.
 *
 * Starts empty: a process has shown nothing until a surface says so.
 */
class SurfacePresence(val policy: HiddenWorkPolicy) {

    private data class State(
        val shown: Set<Surface> = emptySet(),
        val away: Set<Surface> = emptySet(),
        /** How many times each surface has gone from visible to hidden. */
        val hides: Map<Surface, Int> = emptyMap(),
    ) {
        val visible: Set<Surface> get() = shown + away
    }

    private val lock = SynchronizedObject()
    private var state = State()
    private val _visible = MutableStateFlow<Set<Surface>>(emptySet())
    val visible: StateFlow<Set<Surface>> = _visible.asStateFlow()
    private val hides = MutableStateFlow<Map<Surface, Int>>(emptyMap())

    fun shown(surface: Surface) = mutate { it.copy(shown = it.shown + surface, away = it.away - surface) }

    fun hidden(surface: Surface) = mutate { it.copy(shown = it.shown - surface) }

    /**
     * [surface] is handing the screen to something it launched and expects back: a photo or contact picker,
     * the camera, a settings page for a permission. Hiding meanwhile is not leaving, so a turn waiting on a
     * picked contact is not cancelled by the picker covering it. Cleared by the surface's next [shown].
     *
     * Known edge: someone who goes Home from inside the picker never returns, so the surface stays counted
     * as visible until it is next shown and hidden. Being wrong that way costs one finished reply.
     */
    fun awayForResult(surface: Surface) = mutate { it.copy(away = it.away + surface) }

    // One lock over the read-change-publish, so two surfaces reporting at once cannot publish out of order.
    private fun mutate(change: (State) -> State) = synchronized(lock) {
        val before = state
        val after = change(before).let { next ->
            val left = before.visible - next.visible
            if (left.isEmpty()) next else next.copy(hides = next.hides + left.associateWith { (next.hides[it] ?: 0) + 1 })
        }
        state = after
        _visible.value = after.visible
        hides.value = after.hides
    }

    /**
     * A mark to take when work for [surface] starts. [leftSince] with it answers "has the user left since",
     * with no window between reading visibility and starting to watch it.
     */
    fun mark(surface: Surface): Int = hides.value[surface] ?: 0

    /**
     * Emits once when [surface] has hidden at least once after [mark] was taken (at once if it already has),
     * and never when the policy keeps hidden work running.
     */
    fun leftSince(surface: Surface, mark: Int): Flow<Unit> =
        if (!policy.stopWorkOnHide) {
            emptyFlow()
        } else {
            hides.map { (it[surface] ?: 0) != mark }.filter { it }.take(1).map { }
        }

    /**
     * Emits each time [surface] goes from visible to hidden, and only when the policy stops work on hide.
     * The owner of that surface's work collects it and cancels.
     */
    fun stopSignals(surface: Surface): Flow<Unit> =
        if (!policy.stopWorkOnHide) {
            emptyFlow()
        } else {
            hides.map { it[surface] ?: 0 }.distinctUntilChanged().drop(1).map { }
        }
}

/**
 * This flow, ended early the first time [signal] emits. Ending cancels the upstream collection, which for a
 * native engine means its cancel is called and awaited ([com.sabreware.aide.core.domain.engine.nativeStream]),
 * and then completes NORMALLY: the collector sees a short stream, not a failure, and finalises whatever it
 * already has.
 *
 * No channel sits in between: the upstream is collected in the collector's own coroutine, so every item is
 * delivered before an upstream failure propagates (a buffer would drop the tail of a reply that then failed).
 * The signal cancels only this operator's local scope; a cancellation of the collector itself still wins.
 */
fun <T> Flow<T>.endWhen(signal: Flow<Unit>): Flow<T> = flow {
    var ended = false
    try {
        coroutineScope {
            val local = this
            // take(1).collect, not first(): a signal that never fires (a host that keeps hidden work running
            // hands out emptyFlow) must end the watcher quietly, not throw NoSuchElementException.
            val watcher = launch {
                signal.take(1).collect {
                    ended = true
                    local.cancel()
                }
            }
            this@endWhen.collect { emit(it) }
            watcher.cancel()
        }
    } catch (e: CancellationException) {
        if (!ended) throw e
        currentCoroutineContext().ensureActive()
    }
}
