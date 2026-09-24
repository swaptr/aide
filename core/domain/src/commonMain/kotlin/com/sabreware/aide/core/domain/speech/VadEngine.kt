package com.sabreware.aide.core.domain.speech

import kotlinx.coroutines.flow.Flow

interface VadEngine {
    val loadedModelId: String?

    // Silero expects 512 samples @ 16 kHz mono per frame.
    val frameSize: Int

    suspend fun load(spec: SpeechAssetSpec)

    fun process(audio: Flow<FloatArray>): Flow<VadEvent>

    /**
     * Free the native handles. **Suspending on purpose**: the recogniser and its streams are not
     * thread-safe, so the release has to run on the same single-permit dispatcher and under the same
     * lock as a decode. A plain `close()` could only free them from whatever thread called it — which
     * is how a keepAlive expiry or a memory-trim could free a recogniser out from under a decode that
     * was still running on it.
     */
    suspend fun close()
}
