package com.sabreware.aide.data.llm.aisdk

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.EmbeddingCallOptions
import com.sabreware.aide.aisdk.ImageCallOptions
import com.sabreware.aide.aisdk.Provider
import com.sabreware.aide.aisdk.RerankingCallOptions
import com.sabreware.aide.aisdk.SpeechCallOptions
import com.sabreware.aide.aisdk.TranscriptionCallOptions
import com.sabreware.aide.aisdk.TranscriptionResult
import com.sabreware.aide.aisdk.VideoCallOptions
import com.sabreware.aide.aisdk.VideoData
import com.sabreware.aide.aisdk.VideoStatusResult
import com.sabreware.aide.aisdk.runtime.embedMany
import com.sabreware.aide.aisdk.runtime.generateImage
import com.sabreware.aide.aisdk.runtime.generateSpeech
import com.sabreware.aide.aisdk.runtime.rerank as rerankWith
import com.sabreware.aide.aisdk.runtime.startVideo
import com.sabreware.aide.aisdk.runtime.transcribe as transcribeWith
import com.sabreware.aide.aisdk.runtime.videoStatus
import com.sabreware.aide.core.domain.audio.AudioSynthesisEngine
import com.sabreware.aide.core.domain.audio.AudioSynthesisOptions
import com.sabreware.aide.core.domain.audio.AudioTranscriptionEngine
import com.sabreware.aide.core.domain.audio.SynthesizedAudio
import com.sabreware.aide.core.domain.audio.Transcript
import com.sabreware.aide.core.domain.audio.TranscriptionOptions
import com.sabreware.aide.core.domain.embedding.Embedding
import com.sabreware.aide.core.domain.embedding.EmbeddingEngine
import com.sabreware.aide.core.domain.embedding.EmbeddingOptions
import com.sabreware.aide.core.domain.image.ImageEngine
import com.sabreware.aide.core.domain.image.ImageOptions
import com.sabreware.aide.core.domain.image.ImageResult
import com.sabreware.aide.core.domain.rerank.RerankEngine
import com.sabreware.aide.core.domain.rerank.Ranked
import com.sabreware.aide.core.domain.video.GeneratedVideo
import com.sabreware.aide.core.domain.video.VideoEngine
import com.sabreware.aide.core.domain.video.VideoJob
import com.sabreware.aide.core.domain.video.VideoOptions
import com.sabreware.aide.core.domain.video.VideoStatus
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// ---------------------------------------------------------------------------------------------------
// The non-chat modalities, each a thin translation between AIDE's port and the `:aisdk` runtime
// wrapper for that modality.
//
// They go through the wrapper rather than the model's own `doGenerate` for what the wrapper adds and
// every hand-written call site forgets: batching against the vendor's per-request ceiling, a bounded
// fan-out, and the check that something was actually produced. `embedMany` splits a batch the caller
// did not have to measure; `generateImage` turns `count = 4` into four calls against the models that
// serve one image per request.
//
// Every one takes a `providerFactory` lambda rather than a `Provider`, for the same reason
// [AiSdkLlmEngine] does: credentials change while the app runs, and a key pasted into Settings has to
// take effect on the next call rather than the next launch.
// ---------------------------------------------------------------------------------------------------

/** Image generation over `:aisdk`. */
public class AiSdkImageEngine(
    private val providerFactory: () -> Provider?,
    private val vendor: String,
) : ImageEngine {

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun generate(
        modelName: String,
        prompt: String,
        options: ImageOptions,
    ): List<ImageResult> {
        val provider = requireProvider(vendor, providerFactory)
        val model = provider.imageModel(modelName) ?: noModality(provider, "image")
        val generated = generateImage(
            model = model,
            options = ImageCallOptions(prompt = prompt, n = options.count, size = options.size),
        )
        // Always bytes: every `:aisdk` image model asks the vendor for base64 and fetches the bytes
        // itself where the vendor answers with a URL, because a generated-image URL expires and a
        // caller that stored one finds a broken image later rather than an error now.
        return generated.images.map { image ->
            ImageResult.Bytes(image.bytes(), options.outputFormat)
        }
    }
}

/** Text embeddings over `:aisdk`. */
public class AiSdkEmbeddingEngine(
    private val providerFactory: () -> Provider?,
    private val vendor: String,
) : EmbeddingEngine {

    override suspend fun embed(
        modelName: String,
        values: List<String>,
        options: EmbeddingOptions,
    ): List<Embedding> {
        val provider = requireProvider(vendor, providerFactory)
        val model = provider.embeddingModel(modelName) ?: noModality(provider, "embedding")
        // The whole batch, unchunked: `embedMany` reads the model's own ceiling and splits against it,
        // which is the one number a caller cannot know and would otherwise hard-code until it went stale.
        return embedMany(model, EmbeddingCallOptions(values = values)).embeddings
    }
}

/** Text to speech over `:aisdk`. */
public class AiSdkSpeechEngine(
    private val providerFactory: () -> Provider?,
    private val vendor: String,
) : AudioSynthesisEngine {

    override suspend fun synthesize(
        modelName: String,
        text: String,
        options: AudioSynthesisOptions,
    ): SynthesizedAudio {
        val provider = requireProvider(vendor, providerFactory)
        val model = provider.speechModel(modelName) ?: noModality(provider, "speech")
        val result = generateSpeech(
            model = model,
            options = SpeechCallOptions(
                text = text,
                voice = options.voice,
                outputFormat = options.outputFormat,
                instructions = options.instructions,
                speed = options.speed,
                language = options.language,
            ),
        )
        return SynthesizedAudio(
            bytes = result.audio.bytes(),
            // `SpeechResult` carries no media type, so it is recovered from the response headers and
            // only then guessed from what was asked for. A player handed the wrong type produces noise
            // rather than an error, so the vendor's own answer wins over our inference.
            mediaType = result.response.headers.contentType() ?: audioMediaType(options.outputFormat),
        )
    }
}

/** Speech to text over `:aisdk`. */
public class AiSdkTranscriptionEngine(
    private val providerFactory: () -> Provider?,
    private val vendor: String,
) : AudioTranscriptionEngine {

    override suspend fun transcribe(
        modelName: String,
        audio: ByteArray,
        mediaType: String,
        options: TranscriptionOptions,
    ): Transcript {
        val provider = requireProvider(vendor, providerFactory)
        val model = provider.transcriptionModel(modelName) ?: noModality(provider, "transcription")
        val result = transcribeWith(
            model = model,
            options = TranscriptionCallOptions(
                audio = BinaryData.Bytes(audio),
                mediaType = mediaType,
                providerOptions = options.language?.let { languageHint(model.provider, it) },
            ),
        )
        return Transcript(
            text = result.text,
            segments = result.segments.map(TranscriptionResult.Segment::toDomain),
            language = result.language,
            durationInSeconds = result.durationInSeconds,
        )
    }
}

/** Reranking over `:aisdk`. */
public class AiSdkRerankEngine(
    private val providerFactory: () -> Provider?,
    private val vendor: String,
) : RerankEngine {

    override suspend fun rerank(
        modelName: String,
        query: String,
        documents: List<String>,
        topN: Int?,
    ): List<Ranked> {
        val provider = requireProvider(vendor, providerFactory)
        val model = provider.rerankingModel(modelName) ?: noModality(provider, "reranking")
        val result = rerankWith(
            model = model,
            options = RerankingCallOptions(
                documents = RerankingCallOptions.Documents.Text(documents),
                query = query,
                topN = topN,
            ),
        )
        return result.ranking.map { Ranked(index = it.index, relevanceScore = it.relevanceScore) }
    }
}

/**
 * Video generation over `:aisdk`, submitted and checked as two separate calls.
 *
 * **What the handle carries, and why it is more than the vendor's.** `:aisdk` hands back an opaque
 * `JsonElement` operation, but resolving a model to check it needs a model id — and [VideoJob] has
 * nowhere to put one. Storing only the operation would make [status] unanswerable for a job read back
 * off disk, which is the exact case the split exists to serve. So the handle is our own envelope
 * around both, serialized to text: a job persisted before a restart resolves the same model afterwards.
 */
public class AiSdkVideoEngine(
    private val providerFactory: () -> Provider?,
    private val providerId: String,
    private val vendor: String,
) : VideoEngine {

    override suspend fun start(
        modelName: String,
        prompt: String,
        options: VideoOptions,
    ): VideoJob {
        val model = videoModel(modelName)
        val started = startVideo(
            model = model,
            options = VideoCallOptions(
                prompt = prompt,
                aspectRatio = options.aspectRatio,
                durationInSeconds = options.durationInSeconds,
                seed = options.seed,
            ),
        )
        val handle = buildJsonObject {
            put(HANDLE_MODEL, modelName)
            put(HANDLE_OPERATION, started.operation)
        }
        return VideoJob(providerId = providerId, handle = Json.encodeToString(JsonObject.serializer(), handle))
    }

    override suspend fun status(job: VideoJob): VideoStatus {
        val handle = runCatching { Json.parseToJsonElement(job.handle).jsonObject }.getOrNull()
        val modelName = handle?.get(HANDLE_MODEL)?.jsonPrimitive?.content
        val operation = handle?.get(HANDLE_OPERATION)
        if (modelName == null || operation == null) {
            // A handle this engine did not write — a job stored by another provider, or one whose row
            // outlived the format. Failing loudly beats polling a vendor with a reference it never issued.
            throw IllegalStateException("Video job handle is not a $vendor job")
        }
        return when (val status = videoStatus(videoModel(modelName), operation)) {
            is VideoStatusResult.Pending -> VideoStatus.Pending
            is VideoStatusResult.Completed -> VideoStatus.Ready(status.videos.map(VideoData::toDomain))
            is VideoStatusResult.Failed -> VideoStatus.Failed(status.error)
        }
    }

    private fun videoModel(modelName: String) = requireProvider(vendor, providerFactory).let { provider ->
        provider.videoModel(modelName) ?: noModality(provider, "video")
    }

    private companion object {
        const val HANDLE_MODEL = "model"
        const val HANDLE_OPERATION = "operation"
    }
}

/**
 * The vendor as configured right now, or the sentence that says what to do about it.
 *
 * The message is the user's, not a developer's: an unconfigured provider reaches the model as a tool
 * failure, and "add an API key in Settings" is something it can relay.
 */
private fun requireProvider(vendor: String, factory: () -> Provider?): Provider =
    factory() ?: throw IllegalStateException("$vendor isn't set up. Add an API key in Settings.")

/**
 * A provider that serves no models of a kind at all — distinct from a mistyped model id, which the
 * provider itself throws `NoSuchModelError` for.
 */
private fun noModality(provider: Provider, modality: String): Nothing =
    throw IllegalStateException("${provider.providerId} serves no $modality models")

@OptIn(ExperimentalEncodingApi::class)
private fun BinaryData.bytes(): ByteArray = when (this) {
    is BinaryData.Bytes -> value
    is BinaryData.Base64 -> Base64.decode(value)
}

/** Headers are matched case-insensitively: HTTP does not promise the casing a vendor sent. */
private fun Map<String, String>?.contentType(): String? = this
    ?.entries
    ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
    ?.value
    ?.substringBefore(';')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/**
 * The media type a container name implies.
 *
 * Vendors name the container, sometimes with a rate and bitrate attached (`mp3_44100_128`), so the
 * prefix is what carries the codec. An unrecognised name falls back to MP3 because that is what every
 * wired speech vendor returns when asked for nothing.
 */
private fun audioMediaType(outputFormat: String?): String = when (outputFormat?.substringBefore('_')) {
    null, "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "opus" -> "audio/opus"
    "flac" -> "audio/flac"
    "aac" -> "audio/aac"
    "pcm" -> "audio/pcm"
    "ulaw" -> "audio/basic"
    else -> "audio/mpeg"
}

private fun TranscriptionResult.Segment.toDomain(): Transcript.Segment =
    Transcript.Segment(text = text, startSecond = startSecond, endSecond = endSecond)

/**
 * The language hint in the shape the vendor reads it.
 *
 * The contract deliberately carries no `language` field — each vendor spells it differently, and a
 * neutral field would have to be re-spelled inside every provider. So it rides `providerOptions` under
 * the vendor's own key: ElevenLabs reads `languageCode`, Google a `languageCodes` array, and every
 * OpenAI-compatible server a multipart `language` field (the compat model forwards each primitive option
 * as a form field). Google takes the full BCP-47 tag (`en-US`, as its own fixtures do); the others take
 * ISO-639-1, so the tag is cut to its primary subtag for them. Skipping the hint costs the vendor a
 * detection pass most of them charge for.
 */
private fun languageHint(providerId: String, language: String): Map<String, JsonObject> = mapOf(
    providerId to buildJsonObject {
        when (providerId) {
            ELEVENLABS_ID -> put("languageCode", language.primarySubtag())
            GOOGLE_ID -> putJsonArray("languageCodes") { add(JsonPrimitive(language)) }
            else -> put("language", language.primarySubtag())
        }
    },
)

/** `en-US` → `en`; `zh_Hans` → `zh`. */
private fun String.primarySubtag(): String = substringBefore('-').substringBefore('_').lowercase()

private const val ELEVENLABS_ID = "elevenlabs"
private const val GOOGLE_ID = "google"

@OptIn(ExperimentalEncodingApi::class)
private fun VideoData.toDomain(): GeneratedVideo = when (this) {
    is VideoData.Url -> GeneratedVideo.Url(url, mediaType)
    is VideoData.Bytes -> GeneratedVideo.Bytes(data, mediaType)
    is VideoData.Base64 -> GeneratedVideo.Bytes(Base64.decode(data), mediaType)
}
