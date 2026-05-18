package com.swaptr.aide.domain.speech.system

import com.swaptr.aide.data.speech.SpeechAssetSpec
import com.swaptr.aide.domain.speech.VadEngine
import com.swaptr.aide.domain.speech.VadEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class NoopVadEngine : VadEngine {
    override val loadedModelId: String? = null
    override val frameSize: Int = 512
    override suspend fun load(spec: SpeechAssetSpec) = Unit
    override fun process(audio: Flow<FloatArray>): Flow<VadEvent> =
        flowOf(VadEvent.SilenceOnly)
    override fun close() = Unit
}
