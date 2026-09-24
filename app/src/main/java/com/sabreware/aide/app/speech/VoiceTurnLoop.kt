package com.sabreware.aide.app.speech

import com.sabreware.aide.core.domain.chat.ChatTranscript
import com.sabreware.aide.core.domain.usecase.SendChatMessageUseCase
import kotlinx.coroutines.flow.Flow

/**
 * The assistant's voice turn loop (Layer D composition): listen → reason → speak, repeated under a
 * [VoiceTurnPolicy]. Domain port so the assistant surface drives the loop without importing the data
 * implementation ([com.sabreware.aide.app.speech.io.VoiceTurnLoopImpl]).
 */
interface VoiceTurnLoop {

    /** A fresh voice session; the caller swaps [VoiceSession.transcript] once a chat row is minted. */
    fun newSession(): VoiceSession

    fun run(voiceSession: VoiceSession, policy: VoiceTurnPolicy): Flow<VoiceLoopState>

    suspend fun resolveActiveModelId(): String?

    /** Mutable transcript so the controller can swap in-memory for persistent once a chat row exists. */
    interface VoiceSession : SendChatMessageUseCase.SessionHolder {
        var transcript: ChatTranscript
    }
}
