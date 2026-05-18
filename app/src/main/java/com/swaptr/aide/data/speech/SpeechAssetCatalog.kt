package com.swaptr.aide.data.speech

import com.swaptr.aide.domain.speech.SpeechProviderId

// Named accessors (zipformerEnStt etc.) are the engine's canonical lazy-load fallbacks.
object SpeechAssetCatalog {

    private const val ASR_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private const val TTS_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    private const val SHERPA_LICENSE = "Apache-2.0"
    private const val SHERPA_LICENSE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE"
    private const val MIT = "MIT"
    private const val PIPER_LICENSE_URL =
        "https://github.com/rhasspy/piper/blob/master/LICENSE.md"

    val zipformerEnStt = stt(
        id = "sherpa-stt-zipformer-en",
        displayName = "Zipformer English (streaming)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        sizeBytes = 105_000_000L,
        locale = "en-US",
        sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
    )

    val zipformerEnKrokoStt = stt(
        id = "sherpa-stt-zipformer-en-kroko-2025",
        displayName = "Zipformer English Kroko (streaming, small)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2",
        sizeBytes = 56_000_000L,
        locale = "en-US",
    )

    val zipformerEnMobile20MStt = stt(
        id = "sherpa-stt-zipformer-en-20m-mobile",
        displayName = "Zipformer English 20M (mobile)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile.tar.bz2",
        sizeBytes = 107_000_000L,
        locale = "en-US",
    )

    val zipformerZhEnStt = stt(
        id = "sherpa-stt-zipformer-zh-en-mobile",
        displayName = "Zipformer Chinese + English (mobile)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile.tar.bz2",
        sizeBytes = 346_000_000L,
        locale = "zh,en",
    )

    val zipformerDeKrokoStt = stt(
        id = "sherpa-stt-zipformer-de-kroko-2025",
        displayName = "Zipformer German Kroko (streaming)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06.tar.bz2",
        sizeBytes = 57_000_000L,
        locale = "de-DE",
    )

    val zipformerFrKrokoStt = stt(
        id = "sherpa-stt-zipformer-fr-kroko-2025",
        displayName = "Zipformer French Kroko (streaming)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06.tar.bz2",
        sizeBytes = 57_000_000L,
        locale = "fr-FR",
    )

    val zipformerEsKrokoStt = stt(
        id = "sherpa-stt-zipformer-es-kroko-2025",
        displayName = "Zipformer Spanish Kroko (streaming)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06.tar.bz2",
        sizeBytes = 124_000_000L,
        locale = "es-ES",
    )

    val zipformerRuStt = stt(
        id = "sherpa-stt-zipformer-ru-vosk-2025",
        displayName = "Zipformer Russian Vosk (streaming, small)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-small-ru-vosk-2025-08-16.tar.bz2",
        sizeBytes = 89_000_000L,
        locale = "ru-RU",
    )

    val zipformerKoStt = stt(
        id = "sherpa-stt-zipformer-ko-mobile",
        displayName = "Zipformer Korean (mobile)",
        family = SpeechAssetFamily.ZIPFORMER_STREAMING,
        archive = "sherpa-onnx-streaming-zipformer-korean-2024-06-16-mobile.tar.bz2",
        sizeBytes = 377_000_000L,
        locale = "ko-KR",
    )

    val whisperTinyEnStt = stt(
        id = "sherpa-stt-whisper-tiny-en",
        displayName = "Whisper Tiny English",
        family = SpeechAssetFamily.WHISPER,
        archive = "sherpa-onnx-whisper-tiny.en.tar.bz2",
        sizeBytes = 117_000_000L,
        locale = "en",
        license = MIT,
        licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE",
        sourceUrl = "https://github.com/openai/whisper",
    )

    val whisperTinyMultiStt = stt(
        id = "sherpa-stt-whisper-tiny-multi",
        displayName = "Whisper Tiny (multilingual)",
        family = SpeechAssetFamily.WHISPER,
        archive = "sherpa-onnx-whisper-tiny.tar.bz2",
        sizeBytes = 116_000_000L,
        locale = "multi",
        license = MIT,
        licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE",
        sourceUrl = "https://github.com/openai/whisper",
    )

    val whisperBaseEnStt = stt(
        id = "sherpa-stt-whisper-base-en",
        displayName = "Whisper Base English",
        family = SpeechAssetFamily.WHISPER,
        archive = "sherpa-onnx-whisper-base.en.tar.bz2",
        sizeBytes = 208_000_000L,
        locale = "en",
        license = MIT,
        licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE",
        sourceUrl = "https://github.com/openai/whisper",
    )

    val moonshineTinyStt = stt(
        id = "sherpa-stt-moonshine-tiny-en",
        displayName = "Moonshine Tiny English (int8)",
        family = SpeechAssetFamily.MOONSHINE,
        archive = "sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
        sizeBytes = 107_000_000L,
        locale = "en",
        license = MIT,
        licenseUrl = "https://github.com/usefulsensors/moonshine/blob/main/LICENSE",
        sourceUrl = "https://github.com/usefulsensors/moonshine",
    )

    val moonshineBaseStt = stt(
        id = "sherpa-stt-moonshine-base-en",
        displayName = "Moonshine Base English (int8)",
        family = SpeechAssetFamily.MOONSHINE,
        archive = "sherpa-onnx-moonshine-base-en-int8.tar.bz2",
        sizeBytes = 251_000_000L,
        locale = "en",
        license = MIT,
        licenseUrl = "https://github.com/usefulsensors/moonshine/blob/main/LICENSE",
        sourceUrl = "https://github.com/usefulsensors/moonshine",
    )

    val senseVoiceStt = stt(
        id = "sherpa-stt-sense-voice-int8-2025",
        displayName = "SenseVoice (zh + en + ja + ko + yue)",
        family = SpeechAssetFamily.SENSE_VOICE,
        archive = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
        sizeBytes = 166_000_000L,
        locale = "zh,en,ja,ko,yue",
        sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
    )

    val parakeetTdtV2Stt = stt(
        id = "sherpa-stt-nemo-parakeet-tdt-0.6b-v2",
        displayName = "NVIDIA Parakeet TDT 0.6B v2 (int8, English)",
        family = SpeechAssetFamily.NEMO_TRANSDUCER,
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        sizeBytes = 483_000_000L,
        locale = "en",
        license = "CC-BY-4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        sourceUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2",
    )

    val parakeetTdtV3Stt = stt(
        id = "sherpa-stt-nemo-parakeet-tdt-0.6b-v3",
        displayName = "NVIDIA Parakeet TDT 0.6B v3 (int8)",
        family = SpeechAssetFamily.NEMO_TRANSDUCER,
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        sizeBytes = 487_000_000L,
        locale = "multi",
        license = "CC-BY-4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        sourceUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
    )

    val canary180mStt = stt(
        id = "sherpa-stt-nemo-canary-180m-flash",
        displayName = "NVIDIA Canary 180M Flash (int8, en + es + de + fr)",
        family = SpeechAssetFamily.CANARY,
        archive = "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2",
        sizeBytes = 153_000_000L,
        locale = "en,es,de,fr",
        license = "CC-BY-4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        sourceUrl = "https://huggingface.co/nvidia/canary-180m-flash",
    )

    val gigaAmRussianCtcStt = stt(
        id = "sherpa-stt-nemo-ctc-gigaam-v2-russian",
        displayName = "GigaAM v2 Russian (CTC)",
        family = SpeechAssetFamily.NEMO_CTC,
        archive = "sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19.tar.bz2",
        sizeBytes = 167_000_000L,
        locale = "ru-RU",
        license = "MIT (non-commercial)",
        licenseUrl = "https://github.com/salute-developers/GigaAM/blob/main/LICENSE",
        sourceUrl = "https://github.com/salute-developers/GigaAM",
    )

    val gigaAmRussianRntStt = stt(
        id = "sherpa-stt-nemo-rnnt-gigaam-v2-russian",
        displayName = "GigaAM v2 Russian (Transducer)",
        family = SpeechAssetFamily.NEMO_TRANSDUCER,
        archive = "sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19.tar.bz2",
        sizeBytes = 172_000_000L,
        locale = "ru-RU",
        license = "MIT (non-commercial)",
        licenseUrl = "https://github.com/salute-developers/GigaAM/blob/main/LICENSE",
        sourceUrl = "https://github.com/salute-developers/GigaAM",
    )

    val sttAssets: List<SpeechAssetSpec> = listOf(
        zipformerEnStt,
        zipformerEnKrokoStt,
        zipformerEnMobile20MStt,
        zipformerZhEnStt,
        zipformerDeKrokoStt,
        zipformerFrKrokoStt,
        zipformerEsKrokoStt,
        zipformerRuStt,
        zipformerKoStt,
        whisperTinyEnStt,
        whisperTinyMultiStt,
        whisperBaseEnStt,
        moonshineTinyStt,
        moonshineBaseStt,
        senseVoiceStt,
        parakeetTdtV2Stt,
        parakeetTdtV3Stt,
        canary180mStt,
        gigaAmRussianCtcStt,
        gigaAmRussianRntStt,
    )

    val piperEnAmyTts = ttsVits(
        id = "sherpa-tts-piper-en-amy",
        displayName = "Piper · Amy (en-US, low)",
        archive = "vits-piper-en_US-amy-low.tar.bz2",
        sizeBytes = 66_000_000L,
        locale = "en-US",
    )

    val piperEnAmyMediumTts = ttsVits(
        id = "sherpa-tts-piper-en-amy-medium",
        displayName = "Piper · Amy (en-US, medium)",
        archive = "vits-piper-en_US-amy-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "en-US",
    )

    val piperEnLessacMediumTts = ttsVits(
        id = "sherpa-tts-piper-en-lessac-medium",
        displayName = "Piper · Lessac (en-US, medium)",
        archive = "vits-piper-en_US-lessac-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "en-US",
    )

    val piperEnLessacHighTts = ttsVits(
        id = "sherpa-tts-piper-en-lessac-high",
        displayName = "Piper · Lessac (en-US, high)",
        archive = "vits-piper-en_US-lessac-high.tar.bz2",
        sizeBytes = 115_000_000L,
        locale = "en-US",
    )

    val piperEnRyanHighTts = ttsVits(
        id = "sherpa-tts-piper-en-ryan-high",
        displayName = "Piper · Ryan (en-US male, high)",
        archive = "vits-piper-en_US-ryan-high.tar.bz2",
        sizeBytes = 115_000_000L,
        locale = "en-US",
    )

    val piperEnLibrittsTts = ttsVits(
        id = "sherpa-tts-piper-en-libritts-r",
        displayName = "Piper · LibriTTS-R (en-US, 904 voices)",
        archive = "vits-piper-en_US-libritts_r-medium.tar.bz2",
        sizeBytes = 82_000_000L,
        locale = "en-US",
    )

    val piperGbAlanTts = ttsVits(
        id = "sherpa-tts-piper-gb-alan",
        displayName = "Piper · Alan (en-GB male, medium)",
        archive = "vits-piper-en_GB-alan-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "en-GB",
    )

    val piperGbJennyTts = ttsVits(
        id = "sherpa-tts-piper-gb-jenny",
        displayName = "Piper · Jenny (en-GB, medium)",
        archive = "vits-piper-en_GB-jenny_dioco-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "en-GB",
    )

    val piperDeThorstenTts = ttsVits(
        id = "sherpa-tts-piper-de-thorsten",
        displayName = "Piper · Thorsten (de-DE male, medium)",
        archive = "vits-piper-de_DE-thorsten-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "de-DE",
    )

    val piperEsDavefxTts = ttsVits(
        id = "sherpa-tts-piper-es-davefx",
        displayName = "Piper · DaveFX (es-ES male, medium)",
        archive = "vits-piper-es_ES-davefx-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "es-ES",
    )

    val piperFrSiwisTts = ttsVits(
        id = "sherpa-tts-piper-fr-siwis",
        displayName = "Piper · Siwis (fr-FR, medium)",
        archive = "vits-piper-fr_FR-siwis-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "fr-FR",
    )

    val piperItPaolaTts = ttsVits(
        id = "sherpa-tts-piper-it-paola",
        displayName = "Piper · Paola (it-IT, medium)",
        archive = "vits-piper-it_IT-paola-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "it-IT",
    )

    val piperHiPriyamvadaTts = ttsVits(
        id = "sherpa-tts-piper-hi-priyamvada",
        displayName = "Piper · Priyamvada (hi-IN, medium)",
        archive = "vits-piper-hi_IN-priyamvada-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "hi-IN",
    )

    val piperZhHuayanTts = ttsVits(
        id = "sherpa-tts-piper-zh-huayan",
        displayName = "Piper · Huayan (zh-CN, medium)",
        archive = "vits-piper-zh_CN-huayan-medium.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "zh-CN",
    )

    val mmsEngTts = ttsVits(
        id = "sherpa-tts-mms-eng",
        displayName = "Meta MMS English (VITS)",
        archive = "vits-mms-eng.tar.bz2",
        sizeBytes = 107_000_000L,
        locale = "en",
        license = "CC-BY-NC-4.0",
        licenseUrl = "https://github.com/facebookresearch/fairseq/blob/main/examples/mms/LICENSE",
        sourceUrl = "https://github.com/facebookresearch/fairseq/tree/main/examples/mms",
    )

    val meloEnTts = ttsVits(
        id = "sherpa-tts-melo-en",
        displayName = "MeloTTS English (VITS)",
        archive = "vits-melo-tts-en.tar.bz2",
        sizeBytes = 162_000_000L,
        locale = "en",
        license = MIT,
        licenseUrl = "https://github.com/myshell-ai/MeloTTS/blob/main/LICENSE",
        sourceUrl = "https://github.com/myshell-ai/MeloTTS",
    )

    val matchaEnLjspeechTts = ttsMatcha(
        id = "sherpa-tts-matcha-en-ljspeech",
        displayName = "Matcha · LJSpeech (en-US)",
        archive = "matcha-icefall-en_US-ljspeech.tar.bz2",
        sizeBytes = 76_000_000L,
        locale = "en-US",
    )

    val matchaZhEnTts = ttsMatcha(
        id = "sherpa-tts-matcha-zh-en",
        displayName = "Matcha · Bilingual (zh + en)",
        archive = "matcha-icefall-zh-en.tar.bz2",
        sizeBytes = 79_000_000L,
        locale = "zh,en",
    )

    val kokoroEnInt8Tts = ttsKokoro(
        id = "sherpa-tts-kokoro-en-int8",
        displayName = "Kokoro English v0.19 (int8)",
        archive = "kokoro-int8-en-v0_19.tar.bz2",
        sizeBytes = 102_000_000L,
        locale = "en",
        lang = "en-us",
    )

    val kokoroMultiInt8Tts = ttsKokoro(
        id = "sherpa-tts-kokoro-multi-int8",
        displayName = "Kokoro Multilingual v1.1 (int8)",
        archive = "kokoro-int8-multi-lang-v1_1.tar.bz2",
        sizeBytes = 147_000_000L,
        locale = "multi",
        lang = "en-us",
    )

    val kittenNanoFp32Tts = ttsKitten(
        id = "sherpa-tts-kitten-nano-en-fp32",
        displayName = "KittenTTS Nano English v0.8 (fp32)",
        archive = "kitten-nano-en-v0_8-fp32.tar.bz2",
        sizeBytes = 63_000_000L,
        locale = "en",
    )

    val kittenNanoInt8Tts = ttsKitten(
        id = "sherpa-tts-kitten-nano-en-int8",
        displayName = "KittenTTS Nano English v0.8 (int8)",
        archive = "kitten-nano-en-v0_8-int8.tar.bz2",
        sizeBytes = 31_000_000L,
        locale = "en",
    )

    val kittenMiniTts = ttsKitten(
        id = "sherpa-tts-kitten-mini-en",
        displayName = "KittenTTS Mini English v0.8",
        archive = "kitten-mini-en-v0_8.tar.bz2",
        sizeBytes = 67_000_000L,
        locale = "en",
    )

    val ttsAssets: List<SpeechAssetSpec> = listOf(
        piperEnAmyTts,
        piperEnAmyMediumTts,
        piperEnLessacMediumTts,
        piperEnLessacHighTts,
        piperEnRyanHighTts,
        piperEnLibrittsTts,
        piperGbAlanTts,
        piperGbJennyTts,
        piperDeThorstenTts,
        piperEsDavefxTts,
        piperFrSiwisTts,
        piperItPaolaTts,
        piperHiPriyamvadaTts,
        piperZhHuayanTts,
        mmsEngTts,
        meloEnTts,
        matchaEnLjspeechTts,
        matchaZhEnTts,
        kokoroEnInt8Tts,
        kokoroMultiInt8Tts,
        kittenNanoFp32Tts,
        kittenNanoInt8Tts,
        kittenMiniTts,
    )

    val sileroVad = SpeechAssetSpec(
        id = "sherpa-vad-silero-v5",
        displayName = "Silero VAD v5",
        kind = SpeechAssetKind.VAD,
        family = SpeechAssetFamily.SILERO_VAD,
        provider = SpeechProviderId.SHERPA_ONNX,
        // Raw .onnx — no tar wrapper, so the extractor short-circuits and the file
        // is used in place by SherpaVadEngine.
        downloadUrl = "$ASR_BASE/silero_vad.onnx",
        fileName = "silero_vad.onnx",
        extractedDirName = "silero_vad",
        sizeBytes = 2_200_000L,
        locale = "*",
        sampleRate = 16_000,
        licenseName = MIT,
        licenseUrl = "https://github.com/snakers4/silero-vad/blob/master/LICENSE",
        sourceUrl = "https://github.com/snakers4/silero-vad",
    )

    val vadAssets: List<SpeechAssetSpec> = listOf(sileroVad)

    val all: List<SpeechAssetSpec> = sttAssets + ttsAssets + vadAssets

    fun findById(id: String): SpeechAssetSpec? = all.firstOrNull { it.id == id }

    fun ofKind(kind: SpeechAssetKind): List<SpeechAssetSpec> = when (kind) {
        SpeechAssetKind.STT -> sttAssets
        SpeechAssetKind.TTS -> ttsAssets
        SpeechAssetKind.VAD -> vadAssets
    }

    private fun stt(
        id: String,
        displayName: String,
        family: SpeechAssetFamily,
        archive: String,
        sizeBytes: Long,
        locale: String,
        license: String = SHERPA_LICENSE,
        licenseUrl: String = SHERPA_LICENSE_URL,
        sourceUrl: String = "https://github.com/k2-fsa/sherpa-onnx",
    ) = SpeechAssetSpec(
        id = id,
        displayName = displayName,
        kind = SpeechAssetKind.STT,
        family = family,
        provider = SpeechProviderId.SHERPA_ONNX,
        downloadUrl = "$ASR_BASE/$archive",
        fileName = archive,
        extractedDirName = archive.removeSuffix(".tar.bz2"),
        sizeBytes = sizeBytes,
        locale = locale,
        sampleRate = 16_000,
        licenseName = license,
        licenseUrl = licenseUrl,
        sourceUrl = sourceUrl,
    )

    private fun ttsVits(
        id: String,
        displayName: String,
        archive: String,
        sizeBytes: Long,
        locale: String,
        license: String = MIT,
        licenseUrl: String = PIPER_LICENSE_URL,
        sourceUrl: String = "https://github.com/rhasspy/piper",
    ) = SpeechAssetSpec(
        id = id,
        displayName = displayName,
        kind = SpeechAssetKind.TTS,
        family = SpeechAssetFamily.VITS,
        provider = SpeechProviderId.SHERPA_ONNX,
        downloadUrl = "$TTS_BASE/$archive",
        fileName = archive,
        extractedDirName = archive.removeSuffix(".tar.bz2"),
        sizeBytes = sizeBytes,
        locale = locale,
        sampleRate = 22_050,
        licenseName = license,
        licenseUrl = licenseUrl,
        sourceUrl = sourceUrl,
    )

    private fun ttsMatcha(
        id: String,
        displayName: String,
        archive: String,
        sizeBytes: Long,
        locale: String,
    ) = SpeechAssetSpec(
        id = id,
        displayName = displayName,
        kind = SpeechAssetKind.TTS,
        family = SpeechAssetFamily.MATCHA,
        provider = SpeechProviderId.SHERPA_ONNX,
        downloadUrl = "$TTS_BASE/$archive",
        fileName = archive,
        extractedDirName = archive.removeSuffix(".tar.bz2"),
        sizeBytes = sizeBytes,
        locale = locale,
        sampleRate = 22_050,
        licenseName = MIT,
        licenseUrl = "https://github.com/shivammehta25/Matcha-TTS/blob/main/LICENSE",
        sourceUrl = "https://github.com/shivammehta25/Matcha-TTS",
    )

    private fun ttsKokoro(
        id: String,
        displayName: String,
        archive: String,
        sizeBytes: Long,
        locale: String,
        lang: String,
    ) = SpeechAssetSpec(
        id = id,
        displayName = displayName,
        kind = SpeechAssetKind.TTS,
        family = SpeechAssetFamily.KOKORO,
        provider = SpeechProviderId.SHERPA_ONNX,
        downloadUrl = "$TTS_BASE/$archive",
        fileName = archive,
        extractedDirName = archive.removeSuffix(".tar.bz2"),
        sizeBytes = sizeBytes,
        locale = locale,
        sampleRate = 24_000,
        licenseName = "Apache-2.0",
        licenseUrl = "https://huggingface.co/hexgrad/Kokoro-82M/blob/main/README.md",
        sourceUrl = "https://huggingface.co/hexgrad/Kokoro-82M",
    )

    private fun ttsKitten(
        id: String,
        displayName: String,
        archive: String,
        sizeBytes: Long,
        locale: String,
    ) = SpeechAssetSpec(
        id = id,
        displayName = displayName,
        kind = SpeechAssetKind.TTS,
        family = SpeechAssetFamily.KITTEN,
        provider = SpeechProviderId.SHERPA_ONNX,
        downloadUrl = "$TTS_BASE/$archive",
        fileName = archive,
        extractedDirName = archive.removeSuffix(".tar.bz2"),
        sizeBytes = sizeBytes,
        locale = locale,
        sampleRate = 24_000,
        licenseName = "Apache-2.0",
        licenseUrl = "https://github.com/KittenML/KittenTTS",
        sourceUrl = "https://github.com/KittenML/KittenTTS",
    )
}
