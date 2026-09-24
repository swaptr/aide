package com.sabreware.aide.desktop.speech

import com.sabreware.aide.core.common.speech.DictationController
import com.sabreware.aide.core.common.speech.DictationSink
import com.sabreware.aide.core.common.speech.DictationState
import com.sabreware.aide.core.common.speech.DictationSurfaceId
import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.io.InputEvent
import com.sabreware.aide.core.domain.io.InputOptions
import com.sabreware.aide.core.domain.io.VoiceInputChannel
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

private const val TAG = "DesktopDictation"

/** A voice channel's Final carries content-IR parts; dictation only wants the recognized text. */
private fun List<AidePart>.textContent(): String =
    filterIsInstance<AidePart.Text>().joinToString(separator = "") { it.text }

/**
 * Desktop [DictationController] — the JVM peer of `:app`'s DictationControllerImpl. Same single-active
 * invariant + mutex serialisation (one capture line per process), capturing through the SHARED
 * [VoiceInputChannel] (→ StartDictationUseCase → DesktopSpeechEngineRepository + DesktopAudioCapturer). The
 * Android permission/IME-surface handshake is dropped: desktop has no runtime mic permission and no
 * IME-hosted fields, so a toggle starts capture straight away.
 */
class DesktopDictationController(
    private val voiceInput: VoiceInputChannel,
    mainDispatcher: CoroutineDispatcher,
) : DictationController {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private data class Active(
        val surface: DictationSurfaceId,
        val sink: DictationSink,
        val job: Job,
    )

    private val mutex = Mutex()
    private var active: Active? = null

    private val states: Map<DictationSurfaceId, MutableStateFlow<DictationState>> =
        DictationSurfaceId.entries.associateWith { MutableStateFlow(DictationState.Idle) }

    private val liveSurfaces: MutableSet<DictationSurfaceId> = mutableSetOf()

    override fun stateFor(surface: DictationSurfaceId): StateFlow<DictationState> =
        states.getValue(surface).asStateFlow()

    override fun registerSurface(surface: DictationSurfaceId) {
        liveSurfaces += surface
    }

    override fun unregisterSurface(surface: DictationSurfaceId) {
        liveSurfaces -= surface
        if (active?.surface == surface) stop(surface)
    }

    override fun toggle(surface: DictationSurfaceId, sink: DictationSink, micAllowed: Boolean) {
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
                startSurface(surface, sink, micAllowed)
            }
        }
    }

    override fun stop(surface: DictationSurfaceId?) {
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
        states.getValue(stopped.surface).update { it.copy(isDictating = false) }
    }

    private fun startSurface(surface: DictationSurfaceId, sink: DictationSink, micAllowed: Boolean) {
        // Surface may have been torn down before this ran.
        if (surface !in liveSurfaces) {
            states.getValue(surface).value = DictationState.Idle
            return
        }
        states.getValue(surface).value = DictationState(isDictating = true)
        active = Active(surface, sink, startInternal(surface, sink, micAllowed))
    }

    private fun startInternal(
        surface: DictationSurfaceId,
        sink: DictationSink,
        micAllowed: Boolean,
    ): Job = scope.launch {
        try {
            voiceInput.capture(InputOptions(micAllowed = micAllowed)).collect { event ->
                when (event) {
                    is InputEvent.Partial -> sink.applyDelta(event.parts.textContent(), isFinal = false)
                    is InputEvent.Final -> sink.applyDelta(event.parts.textContent(), isFinal = true)
                    is InputEvent.Error ->
                        states.getValue(surface).update { it.copy(errorMessage = event.message) }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AideLog.w(TAG, "dictation failed on $surface", t)
            states.getValue(surface).update { it.copy(errorMessage = t.message ?: "Dictation failed") }
        } finally {
            sink.reset()
            states.getValue(surface).update { it.copy(isDictating = false) }
            if (active?.surface == surface) active = null
        }
    }
}
