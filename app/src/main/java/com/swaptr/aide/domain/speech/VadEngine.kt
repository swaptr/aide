package com.swaptr.aide.domain.speech

import com.swaptr.aide.data.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow

interface VadEngine : AutoCloseable {
    val loadedModelId: String?

    // Silero expects 512 samples @ 16 kHz mono per frame.
    val frameSize: Int

    suspend fun load(spec: SpeechAssetSpec)

    fun process(audio: Flow<FloatArray>): Flow<VadEvent>

    override fun close()
}
