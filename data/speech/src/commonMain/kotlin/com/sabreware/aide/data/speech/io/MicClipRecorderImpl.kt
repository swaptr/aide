package com.sabreware.aide.data.speech.io

import com.sabreware.aide.core.common.storage.PlatformPaths
import com.sabreware.aide.core.domain.io.MicClipRecorder
import com.sabreware.aide.core.domain.speech.AudioCapturer
import com.sabreware.aide.core.domain.speech.AudioSession
import com.sabreware.aide.core.domain.util.AideLog
import com.sabreware.aide.data.speech.audio.WavWriter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem

/**
 * Buffers up to [MAX_CLIP_MS] of 16 kHz mono mic audio, then writes it to a WAV.
 *
 * commonMain: it only needs the [AudioCapturer] port (already implemented per platform for dictation),
 * [PlatformPaths] and okio — so every target that can dictate can also record a clip, with no per-platform
 * recorder. Writes are serialized so a stop can't race a start (one mic per process).
 */
class MicClipRecorderImpl(
    private val capturer: AudioCapturer,
    private val paths: PlatformPaths,
    private val fileSystem: FileSystem,
    private val scope: CoroutineScope,
) : MicClipRecorder {

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val mutex = Mutex()
    private val frames = mutableListOf<FloatArray>()
    private var job: Job? = null
    private var session: AudioSession? = null

    override fun start() {
        // Under the same lock as [stop] and [cancel]. Without it, a start racing a stop could open a second
        // mic session while the first was still being torn down — and there is only one microphone.
        scope.launch {
            mutex.withLock {
                // `isActive`, not `!= null`. A job that FAILED — the mic was busy — stayed non-null forever,
                // and this guard then short-circuited every later attempt: recording was silently dead until
                // the app restarted, while the UI showed "not recording" as if nothing had been tried.
                if (job?.isActive == true) return@withLock
                frames.clear()
                _error.value = null
                _isRecording.value = true
                job = scope.launch {
                    try {
                        val s = capturer.openSession(this).also { session = it }
                        withTimeoutOrNull(MAX_CLIP_MS) {
                            s.frames.collect { frame -> frames.add(frame.copyOf()) }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        AideLog.w(TAG, "clip capture failed", t)
                        _error.value = t.message ?: "Could not start recording"
                        _isRecording.value = false
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun stop(): String? = mutex.withLock {
        val capture = job
        job = null
        runCatching { session?.stop() }
        session = null
        _isRecording.value = false
        if (capture == null) {
            frames.clear()
            return null
        }
        runCatching { capture.cancelAndJoin() }

        val total = frames.sumOf { it.size }
        if (total == 0) {
            frames.clear()
            return null
        }
        val samples = FloatArray(total)
        var offset = 0
        for (frame in frames) {
            frame.copyInto(samples, offset)
            offset += frame.size
        }
        frames.clear()

        return runCatching {
            // filesDir, NOT cacheDir — the clip is referenced by path from the persisted message, so it has
            // to outlive cache eviction to stay playable (and re-sendable) later. Shares the dir with image
            // attachments.
            val dir = paths.filesDir / "attachments"
            fileSystem.createDirectories(dir)
            val file = dir / "aide-clip-${Uuid.random()}.wav"
            WavWriter.write(fileSystem, file, samples, SAMPLE_RATE)
            file.toString()
        }.onFailure { AideLog.w(TAG, "failed to write clip wav", it) }.getOrNull()
    }

    override fun cancel() {
        job?.cancel()
        job = null
        runCatching { session?.stop() }
        session = null
        frames.clear()
        _isRecording.value = false
        _error.value = null
    }

    private companion object {
        private const val TAG = "MicClipRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_CLIP_MS = 30_000L
    }
}
