package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.domain.model.Modality
import com.sabreware.aide.core.domain.model.ModelDescriptor
import com.sabreware.aide.core.domain.model.ProviderId

// .tar.bz2 archives extract to extractedDirName; raw .onnx (VAD) ignores it (storage
// just looks for fileName on disk). modality groups UI; family drives engine config.
data class SpeechAssetSpec(
    override val id: String,
    override val displayName: String,
    override val modality: Modality,
    val family: SpeechAssetFamily,
    override val provider: ProviderId,
    override val downloadUrl: String?,
    override val fileName: String?,
    val extractedDirName: String,
    override val sizeBytes: Long?,
    val locale: String,
    val sampleRate: Int,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
) : ModelDescriptor

// Family drives both Sherpa engine config branch and the row label.
enum class SpeechAssetFamily(val displayName: String, val streaming: Boolean) {
    ZIPFORMER_STREAMING("Zipformer", streaming = true),
    WHISPER("Whisper", streaming = false),
    MOONSHINE("Moonshine", streaming = false),
    SENSE_VOICE("SenseVoice", streaming = false),
    NEMO_CTC("NeMo CTC", streaming = false),
    NEMO_TRANSDUCER("NeMo Transducer", streaming = false),
    CANARY("Canary", streaming = false),

    VITS("VITS / Piper", streaming = false),
    MATCHA("Matcha", streaming = false),
    KOKORO("Kokoro", streaming = false),
    KITTEN("KittenTTS", streaming = false),

    SILERO_VAD("Silero VAD", streaming = true),
}
