package com.sabreware.aide.core.domain.io

import com.sabreware.aide.core.domain.chat.AidePart
import com.sabreware.aide.core.domain.speech.SttOptions
import com.sabreware.aide.core.domain.usecase.StartDictationUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * Voice input (Layer D). Consolidates the app's two former recognition paths — the assistant loop's
 * `VoicePipeline.listenForUtterance` and the dictation core — onto one channel: both the assistant
 * turn loop and `DictationController` now capture through here.
 *
 * Reuses [StartDictationUseCase] (the engine-owns-audio mic branch + the ALL-CAPS → sentence-case
 * normalization, both locked by `StartDictationUseCaseTest`) and wraps the recognized text in
 * content-IR [AidePart.Text]. ASR-decode options live here (the voice concern), not on the
 * modality-agnostic [InputOptions]; the live mic level for the UI is read by the voice surface from
 * `MicActivityMonitor`, not carried on [InputEvent.Partial] (B3). (Audio-as-model-input — a
 * raw clip with no transcription — is a stateful tap-to-stop concern handled by `MicClipRecorder`.)
 */
class VoiceInputChannel(
    private val startDictation: StartDictationUseCase,
) : InputChannel {

    override fun capture(options: InputOptions): Flow<InputEvent> =
        startDictation.invoke(micAllowed = options.micAllowed, options = SttOptions()).transform { ev ->
            when (ev) {
                is StartDictationUseCase.Event.Partial ->
                    emit(InputEvent.Partial(listOf(AidePart.Text(ev.text))))
                is StartDictationUseCase.Event.Final ->
                    emit(InputEvent.Final(listOf(AidePart.Text(ev.text))))
                is StartDictationUseCase.Event.Error ->
                    emit(InputEvent.Error(ev.message))
                StartDictationUseCase.Event.Done -> Unit // terminal — the flow simply completes
            }
        }
}
