package com.sabreware.aide.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------------------------------
// Video
//
// The one modality whose contract is shaped by how long the work takes rather than by what it produces.
// A video generation runs for minutes, so a `doGenerate` that hides a polling loop inside itself ties
// the result to one process staying alive: kill the app mid-render and the job is unreachable, because
// the only handle to it was a local variable.
//
// So the contract is split. `doStart` returns an OPAQUE, JSON-serializable operation reference that a
// caller can persist; `doStatus` takes it back. Polling moves out of the provider and into whoever owns
// the lifetime — which is the only layer that knows whether it may block, sleep, or survive a restart.
// A provider whose API really is synchronous may implement `doGenerate` instead.
// ---------------------------------------------------------------------------------------------------

/** A video or image input: a frame to animate from, or a clip to edit. */
public sealed interface VideoFile {

    /** Media type, e.g. `video/mp4`. Nullable on [Url] because a bare link may not reveal one. */
    public val mediaType: String?

    /** Provider-namespaced options for this one file — see [ProviderOptions]. */
    public val providerOptions: ProviderOptions?

    /** Media bytes we hold, for vendors that take an upload. */
    public data class Data(
        val data: BinaryData,
        override val mediaType: String,
        override val providerOptions: ProviderOptions? = null,
    ) : VideoFile

    /** Media the vendor fetches for itself, for vendors that only take a link. */
    public data class Url(
        val url: String,
        override val mediaType: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : VideoFile
}

/** Which end of the generated clip an input image pins. */
public enum class VideoFrameType { FirstFrame, LastFrame }

/** One role-tagged input frame: [frameType] says which end of the clip [image] pins. */
public data class VideoFrameImage(val image: VideoFile, val frameType: VideoFrameType)

/**
 * Everything one video generation needs, whether it goes through [VideoModel.doGenerate] or starts an
 * operation via [VideoModel.doStart].
 *
 * As on [CallOptions], null means OMIT THE FIELD — vendors clamp or reject explicitly-sent values they
 * would have defaulted, so an unset knob must stay off the wire.
 */
public data class VideoCallOptions(
    /** What to generate. Nullable because an image-to-video call may carry only [image]. */
    val prompt: String? = null,
    /** Most video models serve one at a time; the cost per clip is why. */
    val n: Int = 1,
    /** `W:H`, or `adaptive` to inherit the ratio of the input media. */
    val aspectRatio: String? = null,
    /** `WIDTHxHEIGHT`, e.g. `1280x720`. */
    val resolution: String? = null,
    /** Requested clip length. Vendors clamp to their own menu and say so with a [Warning]. */
    val durationInSeconds: Double? = null,
    /** Requested frame rate. */
    val fps: Int? = null,
    /** Deterministic sampling, where the vendor honours one — same seed, same clip. */
    val seed: Int? = null,
    /** A starting frame, for image-to-video. */
    val image: VideoFile? = null,
    /** Role-tagged frames, for first-and-last-frame generation. */
    val frameImages: List<VideoFrameImage>? = null,
    /** Style or subject references the provider routes by media type. */
    val references: List<VideoFile>? = null,
    /** Whether the model should generate an audio track alongside the video. */
    val generateAudio: Boolean? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
)

/**
 * A generated clip.
 *
 * URL is listed first because it is what almost every vendor actually returns: a finished video is tens
 * of megabytes, and inlining that as base64 in a JSON response is not something a serious API does.
 */
public sealed interface VideoData {

    /** The clip's media type, e.g. `video/mp4`. */
    public val mediaType: String

    /** A link to the finished clip — usually short-lived, so download before it expires. */
    public data class Url(val url: String, override val mediaType: String) : VideoData

    /** The clip inlined as base64, for the rare vendor that embeds it. */
    public data class Base64(val data: String, override val mediaType: String) : VideoData

    /** The clip as raw bytes, for a provider that downloads on the caller's behalf. */
    public data class Bytes(val data: ByteArray, override val mediaType: String) : VideoData {

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Bytes && data.contentEquals(other.data) && mediaType == other.mediaType)

        override fun hashCode(): Int = 31 * data.contentHashCode() + mediaType.hashCode()
    }
}

/**
 * The result of [VideoModel.doGenerate].
 *
 * Only the synchronous path produces one; a started operation delivers its clips through
 * [VideoStatusResult.Completed] instead.
 */
public data class VideoResult(
    /** The generated clips, in the vendor's order. */
    val videos: List<VideoData>,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, as far as the provider chose to reveal it. */
    val response: ModalityResponse = ModalityResponse(),
    /** @see SpeechResult.request — the reference omits this here; a debuggable call does not. */
    val request: RequestInfo? = null,
)

/**
 * What [VideoModel.doStart] hands back.
 *
 * [operation] is deliberately a bare [JsonElement]: the caller persists it, hands it back later, and
 * never looks inside. A typed handle would force this specification to know what a task id looks like on
 * every vendor, which is exactly the knowledge it exists not to need.
 */
public data class VideoStartResult(
    val operation: JsonElement,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity of the start call, as far as the provider chose to reveal it. */
    val response: ModalityResponse = ModalityResponse(),
    /** @see SpeechResult.request — the reference omits this here; a debuggable call does not. */
    val request: RequestInfo? = null,
)

/**
 * Where an operation has got to.
 *
 * [Failed] is separate from an exception because a refused job is an answer, not a transport problem: a
 * retry policy must be able to tell "the model declined this prompt" from "the status call did not go
 * through", and collapsing them means retrying something that will be refused every time.
 */
public sealed interface VideoStatusResult {

    /**
     * Declared on the interface so a refusal carries them too: a job the provider clamped and then
     * failed is exactly the one whose warning explains why, and a [Failed] with nowhere to put it
     * drops the explanation on the floor. The reference's `error` variant has no `warnings` field.
     */
    public val warnings: List<Warning>

    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    public val providerMetadata: ProviderMetadata?

    /** Response identity of THIS poll, not of the original start call. */
    public val response: ModalityResponse

    /** Not every status poll has a body, but some vendors post one; a poll nobody can see is a poll
     * nobody can debug. */
    public val request: RequestInfo?

    /** Still rendering. Poll again — the interval is the caller's policy, not the provider's. */
    public data class Pending(
        override val warnings: List<Warning> = emptyList(),
        override val providerMetadata: ProviderMetadata? = null,
        override val response: ModalityResponse = ModalityResponse(),
        override val request: RequestInfo? = null,
    ) : VideoStatusResult

    /** Done — [videos] holds the finished clips, usually as short-lived URLs. */
    public data class Completed(
        val videos: List<VideoData>,
        override val warnings: List<Warning> = emptyList(),
        override val providerMetadata: ProviderMetadata? = null,
        override val response: ModalityResponse = ModalityResponse(),
        override val request: RequestInfo? = null,
    ) : VideoStatusResult

    /** The vendor refused or abandoned the job; [error] is its own explanation, verbatim. */
    public data class Failed(
        val error: String,
        override val warnings: List<Warning> = emptyList(),
        override val providerMetadata: ProviderMetadata? = null,
        override val response: ModalityResponse = ModalityResponse(),
        override val request: RequestInfo? = null,
    ) : VideoStatusResult
}

/**
 * What the vendor posted to the caller's webhook when a started operation reached a terminal state.
 *
 * Headers and body verbatim, because this specification cannot know what either means: which header
 * carries the signature and what the body says about the job differ per vendor, and only the caller's
 * listener — the code that registered the URL — knows which vendor it is hearing from. A runtime treats
 * the delivery purely as the signal that it is time to call [VideoModel.doStatus]; the clips come from
 * that status call, never from the body, so a forged or malformed notification can at worst trigger
 * one authenticated poll.
 *
 * The reference makes the body generic so a provider can narrow it. Here it is a [JsonElement]: a
 * narrowed body is the listener's decode, not the contract's.
 */
public data class VideoWebhookDelivery(
    /** The request headers as received — where a vendor puts its signature, if it signs. */
    val headers: Map<String, String>,
    /** The request body, parsed as JSON and otherwise untouched. */
    val body: JsonElement,
)

/**
 * A video generation model.
 *
 * A provider implements EITHER [doGenerate] (its API is synchronous) or the [doStart]/[doStatus] pair
 * (it is not). Both default to null so neither is a stub that throws.
 */
public interface VideoModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /** How many clips one call may request. Most vendors serve exactly one. */
    public suspend fun maxVideosPerCall(): Int? = 1

    /** Synchronous generation, for the rare vendor whose endpoint blocks until the clip is ready. */
    public suspend fun doGenerate(options: VideoCallOptions): VideoResult? = null

    /**
     * Begin a generation. [webhookUrl], where the vendor supports one, is where it should notify.
     *
     * @return null if this model has no asynchronous flow.
     */
    public suspend fun doStart(options: VideoCallOptions, webhookUrl: String? = null): VideoStartResult? =
        null

    /** @return null if this model has no asynchronous flow. */
    public suspend fun doStatus(
        operation: JsonElement,
        headers: Map<String, String>? = null,
    ): VideoStatusResult? = null

    /**
     * Whether this vendor's API natively notifies a webhook.
     *
     * A capability flag rather than an attempt-and-see, because a caller must decide whether to stand up
     * a real HTTP endpoint BEFORE calling [doStart]. Creating one for a vendor that silently ignores the
     * URL leaves a listener nothing will ever call. The reference signals the same thing by the presence
     * of a `handleWebhookOption` method; a boolean says it without a method whose only body is
     * "call the factory". True means a runtime may hand [doStart] a URL and wait for the
     * [VideoWebhookDelivery] instead of polling [doStatus].
     */
    public val supportsWebhooks: Boolean get() = false
}

// ---------------------------------------------------------------------------------------------------
// Speech translation (live audio in, translated audio and text out)
// ---------------------------------------------------------------------------------------------------

/**
 * Everything one live speech-translation session needs.
 *
 * The session contract matches [TranscriptionStreamOptions] — the provider consumes [audio], and the
 * flow's completion is the end-of-input signal; this adds only the language pair and the output format.
 */
public data class SpeechTranslationStreamOptions(
    /** Audio chunks, pushed as captured — bytes only, see [TranscriptionStreamOptions.audio]. */
    val audio: Flow<ByteArray>,
    /** The shape of the raw audio in [audio] — see [AudioFormat] for why it must be declared. */
    val inputAudioFormat: AudioFormat,
    /** The language to translate INTO. The one required knob: without it the session has no job. */
    val targetLanguage: String,
    /** The language being spoken. Null lets a vendor that can detect it do so. */
    val sourceLanguage: String? = null,
    /** The shape of audio wanted back. Null takes the vendor's default. */
    val outputAudioFormat: AudioFormat? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
    /** Emit untouched provider events as [SpeechTranslationStreamPart.Raw] parts. Debugging aid. */
    val includeRawChunks: Boolean = false,
)

/**
 * What a translation cost, in whichever unit the vendor bills.
 *
 * Audio and text tokens are counted separately rather than folded into one pair, because they are
 * priced separately: a single `inputTokens` number cannot be turned back into a cost without knowing
 * how much of it was audio, and the split is not recoverable once it has been added up.
 */
public data class SpeechTranslationUsage(
    /** Audio consumed, as a duration — for vendors that bill by the second, not by the token. */
    val inputAudioSeconds: Double? = null,
    /** Tokens the input audio cost, where the vendor tokenizes it. */
    val inputAudioTokens: Long? = null,
    /** Tokens the generated audio cost. */
    val outputAudioTokens: Long? = null,
    /** Tokens the text side of the input cost. */
    val inputTextTokens: Long? = null,
    /** Tokens the generated text cost. */
    val outputTextTokens: Long? = null,
)

/**
 * One event from a live translation.
 *
 * Two text channels, not one: [SourceTranscriptFinal] is what was said, [OutputTextFinal] is what it
 * means. A UI showing subtitles needs both, and a contract with a single "text" channel forces the
 * provider to pick one and throw the other away.
 */
public sealed interface SpeechTranslationStreamPart {

    /** Opens the stream, carrying anything the provider had to ignore about the call. */
    public data class StreamStart(
        val warnings: List<Warning> = emptyList(),
    ) : SpeechTranslationStreamPart

    /** A chunk of translated speech, in the requested output format. Chunks concatenate. */
    public data class Audio(
        val audio: ByteArray,
        val id: String? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart {

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Audio && audio.contentEquals(other.audio) && id == other.id &&
                providerMetadata == other.providerMetadata)

        override fun hashCode(): Int {
            var result = audio.contentHashCode()
            result = 31 * result + (id?.hashCode() ?: 0)
            return 31 * result + (providerMetadata?.hashCode() ?: 0)
        }
    }

    /** Append-only translated text. */
    public data class OutputTextDelta(
        val delta: String,
        val id: String? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart

    /** Settled translated text for one provider-defined segment or utterance. */
    public data class OutputTextFinal(
        val text: String,
        val id: String? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart

    /** Append-only source-language text — the transcript of what was actually said. */
    public data class SourceTranscriptDelta(
        val delta: String,
        val id: String? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart

    /** Source-language text that a later part may revise — see [TranscriptionStreamPart]. */
    public data class SourceTranscriptPartial(
        val text: String,
        val id: String? = null,
        val startSecond: Double? = null,
        val endSecond: Double? = null,
        val channelIndex: Int? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart

    /** Settled source-language text for one provider-defined segment or utterance. */
    public data class SourceTranscriptFinal(
        val text: String,
        val id: String? = null,
        val startSecond: Double? = null,
        val endSecond: Double? = null,
        val channelIndex: Int? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart

    /** @see TranscriptionStreamPart.ResponseMetadataPart for why the headers ride on the part. */
    public data class ResponseMetadataPart(
        val metadata: ResponseMetadata,
        val headers: Map<String, String>? = null,
        val body: String? = null,
    ) : SpeechTranslationStreamPart

    /** Terminal part: both settled texts and the session's cost. Exactly one per successful session. */
    public data class Finish(
        val sourceText: String,
        val outputText: String,
        val durationInSeconds: Double? = null,
        val usage: SpeechTranslationUsage? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : SpeechTranslationStreamPart

    /** The untouched provider payload, when the caller asked for it. */
    public data class Raw(val rawValue: JsonElement) : SpeechTranslationStreamPart

    /** A failure that does not end the session — see [TranscriptionStreamPart.Error]. */
    public data class Error(val error: Throwable) : SpeechTranslationStreamPart
}

/**
 * The result of [SpeechTranslationModel.doStream].
 *
 * [stream] is cold: collecting it opens the session, and cancelling the collector ends it.
 */
public data class SpeechTranslationStreamResult(
    /** One [SpeechTranslationStreamPart] per provider event: audio and both text channels, interleaved. */
    val stream: Flow<SpeechTranslationStreamPart>,
    /** The request as sent — the first thing a wire-level bug report needs. */
    val request: RequestInfo? = null,
    /**
     * Response identity known at open time; identity that arrives later comes as a
     * [SpeechTranslationStreamPart.ResponseMetadataPart].
     */
    val response: ResponseInfo? = null,
)

/**
 * A live speech-translation model: audio in one language, audio and text out in another.
 *
 * Like [LanguageModel], an implementation is pure translation of the protocol — options to a vendor
 * session, its events back to [SpeechTranslationStreamPart] — with no session state beyond the stream
 * it returns. Implementations are expected to be cheap to construct and safe to share.
 *
 * Streaming only, because the modality is live by nature: the input is an open microphone, not a file.
 */
public interface SpeechTranslationModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /**
     * Open a live translation session.
     *
     * The `do` prefix, as on [LanguageModel.doGenerate], signals that callers are expected to go
     * through a runtime rather than call a provider directly.
     */
    public suspend fun doStream(options: SpeechTranslationStreamOptions): SpeechTranslationStreamResult
}
