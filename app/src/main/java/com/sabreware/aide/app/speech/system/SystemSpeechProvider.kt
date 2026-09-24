package com.sabreware.aide.app.speech.system

import android.content.Context
import android.speech.SpeechRecognizer
import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ProviderId
import com.sabreware.aide.core.domain.speech.BuiltInSpeechModel
import com.sabreware.aide.core.domain.speech.SpeechAvailability
import com.sabreware.aide.core.domain.speech.SpeechProvider
import com.sabreware.aide.core.domain.speech.SpeechRecognizerEngine
import com.sabreware.aide.core.domain.speech.SpeechSynthesizerEngine
import com.sabreware.aide.core.domain.speech.VadEngine

class SystemSpeechProvider(
    private val appContext: Context,
    private val sttImpl: SystemSttEngine,
    private val ttsImpl: SystemTtsEngine,
) : SpeechProvider {
    override val id = ProviderId.ANDROID_SYSTEM
    override val stt: SpeechRecognizerEngine = sttImpl
    override val tts: SpeechSynthesizerEngine = ttsImpl
    override val vad: VadEngine = NoopVadEngine()

    // Recognition depends on a recognizer service being installed; the TTS engine always exists.
    override val builtInModels: List<BuiltInSpeechModel> by lazy {
        buildList {
            if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                add(
                    BuiltInSpeechModel(
                        id = "$KEY:asr",
                        displayName = "Android speech recognition",
                        modality = Modality.Asr,
                        provider = id,
                        blurb = "The recognizer built into this phone.",
                    ),
                )
            }
            add(
                BuiltInSpeechModel(
                    id = "$KEY:tts",
                    displayName = "Android text to speech",
                    modality = Modality.Tts,
                    provider = id,
                    blurb = "The voice set in this phone's settings.",
                ),
            )
        }
    }

    private companion object {
        const val KEY = ProviderId.ANDROID_SYSTEM_KEY
    }

    override suspend fun availability(): SpeechAvailability {
        val sttOk = SpeechRecognizer.isRecognitionAvailable(appContext)
        return SpeechAvailability(canStt = sttOk, canTts = true, canVad = false)
    }
}
