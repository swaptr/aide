package com.swaptr.aide.domain.speech

import com.swaptr.aide.data.speech.SpeechAssetSpec
import kotlinx.coroutines.flow.Flow

interface SpeechSynthesizerEngine : AutoCloseable {
    val loadedModelId: String?
    val supportsStreamingPcm: Boolean

    suspend fun load(spec: SpeechAssetSpec)

    fun synthesize(text: String, options: TtsOptions = TtsOptions()): Flow<TtsStreamEvent>

    override fun close()
}
