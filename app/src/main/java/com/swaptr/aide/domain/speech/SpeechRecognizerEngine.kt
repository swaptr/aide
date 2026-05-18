package com.swaptr.aide.domain.speech

import com.swaptr.aide.data.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow

// Audio contract: 16 kHz mono float in [-1, 1]. Sherpa OnlineRecognizer is NOT thread-safe;
// implementations must serialise concurrent calls themselves.
interface SpeechRecognizerEngine : AutoCloseable {
    val loadedModelId: String?
    val isStreaming: Boolean

    // True for engines that own the mic; callers MUST NOT open AudioCapturer (else ERROR_NO_MATCH).
    val ownsAudioInput: Boolean get() = false

    suspend fun load(spec: SpeechAssetSpec)

    suspend fun warmUp() = Unit

    fun recognize(audio: Flow<FloatArray>, options: SttOptions = SttOptions()): Flow<SttStreamEvent>

    override fun close()
}
