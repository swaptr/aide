package com.sabreware.aide.app.speech.dictation

import com.sabreware.aide.core.common.speech.DictationController
import com.sabreware.aide.core.common.speech.DictationSink
import com.sabreware.aide.core.common.speech.DictationState
import com.sabreware.aide.core.common.speech.DictationSurfaceId
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.io.InputEvent
import com.sabreware.aide.core.domain.io.InputOptions
import com.sabreware.aide.core.domain.io.VoiceInputChannel
import com.sabreware.aide.core.domain.permission.AppPermission
import com.sabreware.aide.core.domain.permission.PermissionResult
import com.sabreware.aide.core.domain.permission.RuntimePermissionGate
import com.sabreware.aide.core.domain.util.AideLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "DictationController"

/** A voice channel's Final carries content-IR parts; dictation only wants the recognized text. */
private fun List<AidePart>.textContent(): String =
    filterIsInstance<AidePart.Text>().joinToString(separator = "") { it.text }

// Single-active invariant: Android exposes one AudioRecord per process. The mutex
// serialises toggle/stop so cancel-then-start is atomic against concurrent taps.
class DictationControllerImpl(
    private val voiceInput: VoiceInputChannel,
    private val gate: RuntimePermissionGate,
    private val mainImmediateDispatcher: CoroutineDispatcher,
) : DictationController {
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + mainImmediateDispatcher)

    private data class Active(
        val surface: DictationSurfaceId,
        val sink: DictationSink,
        val job: Job,
    )

    private val mutex = Mutex()
    private var active: Active? = null

    /**
     * Bumped by every toggle and stop. A start that stepped outside the lock to ask for the microphone
     * compares it on return: if anything else has asked for a change meanwhile, that start is stale and
     * must not claim the mic. It is what replaces "hold the lock across the permission round-trip".
     */
    private var intentSeq: Long = 0L

    private val states: Map<DictationSurfaceId, MutableStateFlow<DictationState>> =
        DictationSurfaceId.entries.associateWith { MutableStateFlow(DictationState.Idle) }

    // Surfaces currently mounted. A surface unmounted while suspended on the permission
    // dialog will not have dictation started on resume — guards against dead InputConnections.
    private val liveSurfaces: MutableSet<DictationSurfaceId> = mutableSetOf()

    override fun stateFor(surface: DictationSurfaceId): StateFlow<DictationState> =
        states.getValue(surface).asStateFlow()

    override fun registerSurface(surface: DictationSurfaceId) {
        liveSurfaces += surface
    }

    override fun unregisterSurface(surface: DictationSurfaceId) {
        liveSurfaces -= surface
        // Sink (InputConnection) is about to disappear — stop now so recogniser doesn't write into nothing.
        if (active?.surface == surface) stop(surface)
    }

    override fun toggle(
        surface: DictationSurfaceId,
        sink: DictationSink,
        micAllowed: Boolean,
    ) {
        scope.launch {
            // Toggling the live surface OFF needs no permission, so it happens under the lock immediately.
            // Anything else is a start, and a start has to leave the lock before asking for the mic.
            val generation = mutex.withLock {
                val current = active
                if (current?.surface == surface) {
                    intentSeq++
                    stopLocked(current)
                    null
                } else {
                    ++intentSeq
                }
            } ?: return@launch

            // OUTSIDE the lock, on purpose. The gate is a round-trip through another activity; awaiting it
            // while holding the single-active mutex meant one unanswered request froze every later toggle
            // and stop, on every dictation surface, for the life of the process. The gate now always
            // resolves, but a lock is still the wrong thing to hold across a user-facing dialog.
            if (!ensureMic(surface)) return@launch

            mutex.withLock {
                // Re-checked under the lock: the world moved while we were away. A newer toggle/stop wins,
                // and a surface torn down during the dialog must not have dictation started into its dead
                // sink.
                if (intentSeq != generation || surface !in liveSurfaces) {
                    states.getValue(surface).update { it.copy(isDictating = false) }
                    return@withLock
                }
                active?.let { stopLocked(it) }
                states.getValue(surface).value = DictationState(isDictating = true)
                active = Active(surface, sink, startInternal(surface, sink, micAllowed))
            }
        }
    }

    override fun stop(surface: DictationSurfaceId?) {
        scope.launch {
            mutex.withLock {
                intentSeq++
                val current = active ?: return@withLock
                if (surface != null && current.surface != surface) return@withLock
                stopLocked(current)
            }
        }
    }

    /** Cancel-join-finalize, the three steps that always go together. Caller holds [mutex]. */
    private suspend fun stopLocked(current: Active) {
        current.job.cancel()
        current.job.join()
        finalizeStop(current)
    }

    /**
     * Ask for the microphone and publish the refusal, if any. Returns true when dictation may proceed.
     * IME-hosted surfaces skip the in-app rationale (it would draw under the keyboard window); the main
     * chat composer is a normal activity and shows it like every other in-app permission.
     */
    private suspend fun ensureMic(surface: DictationSurfaceId): Boolean {
        states.getValue(surface).update { it.copy(errorMessage = null, permanentlyDenied = false) }
        val mic = gate.ensure(AppPermission.MICROPHONE, showRationale = !surface.imeHosted)
        if (mic.isGranted) return true
        states.getValue(surface).update {
            it.copy(
                isDictating = false,
                errorMessage = mic.deniedMessage,
                permanentlyDenied = mic is PermissionResult.PermanentlyDenied,
            )
        }
        return false
    }

    private fun finalizeStop(stopped: Active) {
        stopped.sink.reset()
        if (active === stopped) active = null
        // Preserve any error message set by a failed start so callers can see it.
        states.getValue(stopped.surface).update {
            it.copy(isDictating = false)
        }
    }

    private fun startInternal(
        surface: DictationSurfaceId,
        sink: DictationSink,
        micAllowed: Boolean,
    ): Job = scope.launch {
        try {
            voiceInput.capture(
                InputOptions(micAllowed = micAllowed),
            ).collect { event ->
                when (event) {
                    is InputEvent.Partial ->
                        sink.applyDelta(event.parts.textContent(), isFinal = false)
                    is InputEvent.Final ->
                        sink.applyDelta(event.parts.textContent(), isFinal = true)
                    is InputEvent.Error ->
                        states.getValue(surface).update { it.copy(errorMessage = event.message) }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AideLog.w(TAG, "dictation failed on $surface", t)
            states.getValue(surface).update {
                it.copy(errorMessage = t.message ?: "Dictation failed")
            }
        } finally {
            sink.reset()
            states.getValue(surface).update { it.copy(isDictating = false) }
            // Safe without mutex: controller runs on Main.immediate. Re-taking the lock here
            // would deadlock because toggle's cancel path already holds it while joining us.
            if (active?.surface == surface) active = null
        }
    }
}
