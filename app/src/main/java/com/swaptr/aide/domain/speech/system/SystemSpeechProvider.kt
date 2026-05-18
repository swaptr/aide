package com.swaptr.aide.domain.speech.system

import android.content.Context
import android.speech.SpeechRecognizer
import com.swaptr.aide.domain.speech.SpeechAvailability
import com.swaptr.aide.domain.speech.SpeechProvider
import com.swaptr.aide.domain.speech.SpeechProviderId
import com.swaptr.aide.domain.speech.SpeechRecognizerEngine
import com.swaptr.aide.domain.speech.SpeechSynthesizerEngine
import com.swaptr.aide.domain.speech.VadEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemSpeechProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sttImpl: SystemSttEngine,
    private val ttsImpl: SystemTtsEngine,
) : SpeechProvider {
    override val id = SpeechProviderId.ANDROID_SYSTEM
    override val stt: SpeechRecognizerEngine = sttImpl
    override val tts: SpeechSynthesizerEngine = ttsImpl
    override val vad: VadEngine = NoopVadEngine()

    override suspend fun availability(): SpeechAvailability {
        val sttOk = SpeechRecognizer.isRecognitionAvailable(appContext)
        return SpeechAvailability(canStt = sttOk, canTts = true, canVad = false)
    }
}
