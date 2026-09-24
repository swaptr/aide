package com.sabreware.aide.app.speech.system

import com.sabreware.aide.core.domain.speech.SpeechAssetSpec
import com.sabreware.aide.core.domain.speech.VadEngine
import com.sabreware.aide.core.domain.speech.VadEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class NoopVadEngine : VadEngine {
    override val loadedModelId: String? = null
    override val frameSize: Int = 512
    override suspend fun load(spec: SpeechAssetSpec) = Unit
    override fun process(audio: Flow<FloatArray>): Flow<VadEvent> =
        flowOf(VadEvent.SilenceOnly)
    override suspend fun close() = Unit
}
