package com.sabreware.aide.core.domain.speech

import kotlinx.coroutines.flow.Flow

// Audio contract: 16 kHz mono float in [-1, 1]. Sherpa OnlineRecognizer is NOT thread-safe;
// implementations must serialise concurrent calls themselves.
interface SpeechRecognizerEngine {
    val loadedModelId: String?
    val isStreaming: Boolean

    // True for engines that own the mic; callers MUST NOT open AudioCapturer (else ERROR_NO_MATCH).
    val ownsAudioInput: Boolean get() = false

    suspend fun load(spec: SpeechAssetSpec)

    suspend fun warmUp() = Unit

    fun recognize(audio: Flow<FloatArray>, options: SttOptions = SttOptions()): Flow<SttStreamEvent>

    /**
     * Free the native handles. **Suspending on purpose**: the recogniser and its streams are not
     * thread-safe, so the release has to run on the same single-permit dispatcher and under the same
     * lock as a decode. A plain `close()` could only free them from whatever thread called it — which
     * is how a keepAlive expiry or a memory-trim could free a recogniser out from under a decode that
     * was still running on it.
     */
    suspend fun close()
}
