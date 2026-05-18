package com.swaptr.aide.domain.speech.dictation

import android.Manifest
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.swaptr.aide.domain.usecase.StartDictationUseCase
import com.swaptr.aide.permission.RuntimePermissionGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

// Single-active invariant: Android exposes one AudioRecord per process. The mutex
// serialises toggle/stop so cancel-then-start is atomic against concurrent taps.
@Singleton
class DictationController @Inject constructor(
    private val startDictation: StartDictationUseCase,
    private val permissionGate: RuntimePermissionGate,
) {
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Active(
        val surface: DictationSurfaceId,
        val sink: DictationSink,
        val job: Job,
    )

    private val mutex = Mutex()
    private var active: Active? = null

    private val states: Map<DictationSurfaceId, MutableStateFlow<DictationState>> =
        DictationSurfaceId.entries.associateWith { MutableStateFlow(DictationState.Idle) }

    // Surfaces currently mounted. A surface unmounted while suspended on the permission
    // dialog will not have dictation started on resume — guards against dead InputConnections.
    private val liveSurfaces: MutableSet<DictationSurfaceId> = mutableSetOf()

    fun stateFor(surface: DictationSurfaceId): StateFlow<DictationState> =
        states.getValue(surface).asStateFlow()

    fun registerSurface(surface: DictationSurfaceId) {
        liveSurfaces += surface
    }

    fun unregisterSurface(surface: DictationSurfaceId) {
        liveSurfaces -= surface
        // Sink (InputConnection) is about to disappear — stop now so recogniser doesn't write into nothing.
        if (active?.surface == surface) stop(surface)
    }

    fun toggle(
        surface: DictationSurfaceId,
        sink: DictationSink,
        editorInfo: EditorInfo? = null,
    ) {
        scope.launch {
            mutex.withLock {
                val current = active
                if (current?.surface == surface) {
                    current.job.cancel()
                    current.job.join()
                    finalizeStop(current)
                    return@withLock
                }
                if (current != null) {
                    current.job.cancel()
                    current.job.join()
                    finalizeStop(current)
                }
                startSurface(surface, sink, editorInfo)
            }
        }
    }

    fun stop(surface: DictationSurfaceId? = null) {
        scope.launch {
            mutex.withLock {
                val current = active ?: return@withLock
                if (surface != null && current.surface != surface) return@withLock
                current.job.cancel()
                current.job.join()
                finalizeStop(current)
            }
        }
    }

    private fun finalizeStop(stopped: Active) {
        stopped.sink.reset()
        if (active === stopped) active = null
        // Preserve any error message set by a failed start so callers can see it.
        states.getValue(stopped.surface).update {
            it.copy(isDictating = false)
        }
    }

    private suspend fun startSurface(
        surface: DictationSurfaceId,
        sink: DictationSink,
        editorInfo: EditorInfo?,
    ) {
        // Suspend on the gate from the controller's own scope so it survives an IME window-hide.
        val granted = if (permissionGate.isGranted(Manifest.permission.RECORD_AUDIO)) {
            true
        } else {
            states.getValue(surface).update {
                it.copy(errorMessage = null, permanentlyDenied = false)
            }
            val outcome = permissionGate.request(Manifest.permission.RECORD_AUDIO)
            if (!outcome.granted) {
                val permanent = outcome.permanentlyDenied
                states.getValue(surface).update {
                    it.copy(
                        isDictating = false,
                        errorMessage = if (permanent) {
                            "Microphone disabled. Grant it in Settings → Apps → Aide → Permissions."
                        } else {
                            "Microphone permission required."
                        },
                        permanentlyDenied = permanent,
                    )
                }
                return
            }
            true
        }
        if (!granted) return

        // Surface may have been torn down during the permission round-trip.
        if (surface !in liveSurfaces) {
            states.getValue(surface).value = DictationState.Idle
            return
        }

        states.getValue(surface).value = DictationState(isDictating = true)
        val started = startInternal(surface, sink, editorInfo)
        active = Active(surface, sink, started)
    }

    private fun startInternal(
        surface: DictationSurfaceId,
        sink: DictationSink,
        editorInfo: EditorInfo?,
    ): Job = scope.launch {
        try {
            startDictation.invoke(editorInfo).collect { event ->
                when (event) {
                    is StartDictationUseCase.Event.Partial ->
                        sink.applyDelta(event.text, isFinal = false)
                    is StartDictationUseCase.Event.Final ->
                        sink.applyDelta(event.text, isFinal = true)
                    is StartDictationUseCase.Event.Error -> {
                        states.getValue(surface).update {
                            it.copy(errorMessage = event.message)
                        }
                    }
                    StartDictationUseCase.Event.Done -> { }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "dictation failed on $surface", t)
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
