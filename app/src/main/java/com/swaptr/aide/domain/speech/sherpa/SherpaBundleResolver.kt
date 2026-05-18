package com.swaptr.aide.domain.speech.sherpa

import com.swaptr.aide.data.speech.SpeechAssetFamily
import java.io.File

internal object SherpaBundleResolver {

    data class SttFiles(
        val encoder: File? = null,
        val decoder: File? = null,
        val joiner: File? = null,
        val model: File? = null,
        val preprocessor: File? = null,
        val uncachedDecoder: File? = null,
        val cachedDecoder: File? = null,
        val tokens: File,
    )

    data class TtsFiles(
        val model: File,
        val tokens: File,
        val voices: File? = null,
        val vocoder: File? = null,
        val espeakDataDir: String,
        val dictDir: String,
        val lexicon: String,
    )

    class MissingBundleFile(message: String) : IllegalStateException(message)

    fun resolveStt(dir: File, family: SpeechAssetFamily): SttFiles {
        val tokens = requireFile(dir, "tokens.txt")
        return when (family) {
            SpeechAssetFamily.ZIPFORMER_STREAMING -> SttFiles(
                encoder = preferInt8(dir, startsWith = "encoder"),
                decoder = preferInt8(dir, startsWith = "decoder"),
                joiner = preferInt8(dir, startsWith = "joiner"),
                tokens = tokens,
            )
            SpeechAssetFamily.WHISPER -> SttFiles(
                // Whisper variants prefix filenames: "tiny.en-encoder.onnx".
                encoder = preferInt8(dir, endsWithLogical = "encoder"),
                decoder = preferInt8(dir, endsWithLogical = "decoder"),
                tokens = tokens,
            )
            SpeechAssetFamily.MOONSHINE -> SttFiles(
                preprocessor = preferInt8(dir, startsWith = "preprocess"),
                encoder = preferInt8(dir, startsWith = "encode", excludeStartsWith = "encoder"),
                uncachedDecoder = preferInt8(dir, startsWith = "uncached_decode"),
                cachedDecoder = preferInt8(dir, startsWith = "cached_decode"),
                tokens = tokens,
            )
            SpeechAssetFamily.SENSE_VOICE, SpeechAssetFamily.NEMO_CTC -> SttFiles(
                model = preferInt8(dir, startsWith = "model"),
                tokens = tokens,
            )
            SpeechAssetFamily.NEMO_TRANSDUCER -> SttFiles(
                encoder = preferInt8(dir, startsWith = "encoder"),
                decoder = preferInt8(dir, startsWith = "decoder"),
                joiner = preferInt8(dir, startsWith = "joiner"),
                tokens = tokens,
            )
            SpeechAssetFamily.CANARY -> SttFiles(
                encoder = preferInt8(dir, startsWith = "encoder"),
                decoder = preferInt8(dir, startsWith = "decoder"),
                tokens = tokens,
            )
            else -> throw MissingBundleFile("Family $family is not an STT family")
        }
    }

    fun resolveTts(dir: File, family: SpeechAssetFamily): TtsFiles {
        val tokens = requireFile(dir, "tokens.txt")
        val espeak = File(dir, "espeak-ng-data").takeIf { it.isDirectory }?.absolutePath.orEmpty()
        val dict = File(dir, "dict").takeIf { it.isDirectory }?.absolutePath.orEmpty()
        val lexiconList = dir.listFiles { f ->
            f.isFile && f.name.startsWith("lexicon") && f.name.endsWith(".txt")
        }?.joinToString(",") { it.absolutePath }.orEmpty()
        val singleLexicon = File(dir, "lexicon.txt").takeIf { it.isFile }?.absolutePath.orEmpty()
        return when (family) {
            SpeechAssetFamily.VITS -> TtsFiles(
                model = firstOnnx(dir) ?: throw MissingBundleFile("VITS bundle missing *.onnx in ${dir.absolutePath}"),
                tokens = tokens,
                espeakDataDir = espeak,
                dictDir = dict,
                lexicon = singleLexicon,
            )
            SpeechAssetFamily.MATCHA -> TtsFiles(
                model = preferInt8(dir, startsWith = "model"),
                vocoder = firstMatching(dir) {
                    (it.name.contains("vocos") || it.name.contains("hifigan") || it.name.contains("vocoder")) &&
                        it.name.endsWith(".onnx")
                } ?: throw MissingBundleFile("Matcha bundle missing vocoder *.onnx in ${dir.absolutePath}"),
                tokens = tokens,
                espeakDataDir = espeak,
                dictDir = dict,
                lexicon = singleLexicon.ifEmpty { lexiconList },
            )
            SpeechAssetFamily.KOKORO -> TtsFiles(
                model = preferInt8(dir, startsWith = "model"),
                voices = requireFile(dir, "voices.bin"),
                tokens = tokens,
                espeakDataDir = espeak,
                dictDir = dict,
                lexicon = lexiconList,
            )
            SpeechAssetFamily.KITTEN -> TtsFiles(
                model = preferInt8(dir, startsWith = "model"),
                voices = requireFile(dir, "voices.bin"),
                tokens = tokens,
                espeakDataDir = espeak,
                dictDir = dict,
                lexicon = lexiconList,
            )
            else -> throw MissingBundleFile("Family $family is not a TTS family")
        }
    }

    // endsWithLogical strips .int8/.fp16/.onnx; excludeStartsWith stops Moonshine's
    // encode.onnx colliding with encoder.onnx.
    private fun preferInt8(
        dir: File,
        startsWith: String? = null,
        endsWithLogical: String? = null,
        excludeStartsWith: String? = null,
    ): File {
        val all = dir.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.endsWith(".onnx") }
            .filter { f ->
                if (excludeStartsWith != null && f.name.startsWith(excludeStartsWith)) return@filter false
                if (startsWith != null) f.name.startsWith(startsWith)
                else if (endsWithLogical != null) {
                    val base = f.name
                        .removeSuffix(".onnx")
                        .removeSuffix(".int8")
                        .removeSuffix(".fp16")
                    base == endsWithLogical || base.endsWith("-$endsWithLogical")
                } else true
            }
            .toList()
        val pick = all.firstOrNull { it.name.contains(".int8.") } ?: all.firstOrNull()
        return pick ?: throw MissingBundleFile(
            "Bundle missing onnx (startsWith=$startsWith, endsWithLogical=$endsWithLogical) in ${dir.absolutePath}",
        )
    }

    private fun firstOnnx(dir: File): File? = dir.listFiles { f ->
        f.isFile && f.name.endsWith(".onnx")
    }?.firstOrNull()

    private fun firstMatching(dir: File, predicate: (File) -> Boolean): File? =
        dir.listFiles { f -> predicate(f) }?.firstOrNull()

    private fun requireFile(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.isFile) throw MissingBundleFile("Bundle missing $name at ${f.absolutePath}")
        return f
    }
}
