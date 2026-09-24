package com.sabreware.aide.aisdk

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Binary payload that a vendor returns either as raw bytes or as base64, depending on the endpoint.
 *
 * Kept as a union rather than normalized to one form on the way in. Converting base64 to bytes costs a
 * copy of every image in a response, and a caller writing straight to disk or to an `<img>` source may
 * want the encoded form untouched. The reference makes the same choice, for the same reason.
 */
public sealed interface BinaryData {

    /** The vendor's base64 string, exactly as received — decode only when something needs the bytes. */
    public data class Base64(val value: String) : BinaryData

    /** Raw bytes, for the payloads that never were base64 — a capture buffer, a downloaded file. */
    public data class Bytes(val value: ByteArray) : BinaryData {

        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }
}

/**
 * Response identity shared by the non-chat modalities.
 *
 * Every field is nullable because a provider that has not read its response headers has nothing honest
 * to put here. A non-null [modelId] forced every call site to fabricate one and fill in nothing else,
 * which made the type look populated while carrying no information at all.
 */
public data class ModalityResponse(
    /** The model the vendor says actually served the call — not always the one that was asked for. */
    val modelId: String? = null,
    /** Epoch milliseconds — see [ResponseMetadata] for why this is not a date type. */
    val timestamp: Long? = null,
    /** The vendor's response id, the handle a support ticket asks for. */
    val id: String? = null,
    /** Response headers — where rate-limit state and request ids live. */
    val headers: Map<String, String>? = null,
    /** The raw response body, for the field the mapping missed. */
    val body: String? = null,
)

// ---------------------------------------------------------------------------------------------------
// Embedding
// ---------------------------------------------------------------------------------------------------

/** One embedding vector. */
public typealias Embedding = List<Double>

/**
 * Everything one call to an [EmbeddingModel] needs.
 *
 * There is no per-value option: a vendor embeds the whole batch under one configuration, so anything
 * call-wide — dimensions, task type — rides in [providerOptions].
 */
public data class EmbeddingCallOptions(
    /** The texts to embed, in an order [EmbeddingResult.embeddings] must preserve. */
    val values: List<String>,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
)

/**
 * The result of [EmbeddingModel.doEmbed].
 *
 * [embeddings] is index-aligned with the submitted values — a caller zips the two lists, so a provider
 * that reorders them corrupts every pairing without anything failing.
 */
public data class EmbeddingResult(
    /** One vector per submitted value, in submission order. */
    val embeddings: List<Embedding>,
    /** Token count, when the vendor reports one. */
    val usage: Int? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, as far as the provider chose to reveal it. */
    val response: ModalityResponse? = null,
    /** @see SpeechResult.request — the reference omits this here; a debuggable call does not. */
    val request: RequestInfo? = null,
)

/**
 * A text-embedding model.
 *
 * Like [LanguageModel], an implementation is pure translation — [EmbeddingCallOptions] to a vendor
 * request, the response back to [EmbeddingResult] — with no state and no batching policy of its own;
 * splitting an oversized input belongs to the runtime above. Implementations are expected to be cheap
 * to construct and safe to share.
 *
 * [maxEmbeddingsPerCall] is a `suspend` read rather than a constant because some providers only learn it
 * after a capability lookup; a caller that exceeds it should be given
 * [TooManyEmbeddingValuesForCallError] rather than a vendor 400.
 */
public interface EmbeddingModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /** The most values one call may carry. Null means no known ceiling. */
    public suspend fun maxEmbeddingsPerCall(): Int? = null

    /**
     * The most UTF-8 input bytes one call may carry, summed across its values. Null means no known
     * budget.
     *
     * A second ceiling, not a restatement of the first: several vendors cap the request by size as well
     * as by count, so a batch of three long documents can be under [maxEmbeddingsPerCall] and still be
     * refused. A runtime that chunks by count alone turns that into a 400 the model could have priced in
     * advance.
     *
     * A first-class member where the reference carries it as an experimental `Symbol.for` side channel
     * outside its versioned specification — the side channel exists because adding a member there is a
     * breaking change, a constraint a default-null Kotlin member does not have.
     */
    public suspend fun maxInputBytesPerCall(): Int? = null

    /** Whether several calls to this model may run concurrently against the same credentials. */
    public suspend fun supportsParallelCalls(): Boolean = true

    /**
     * Embed every value in one vendor call.
     *
     * The `do` prefix, as on [LanguageModel.doGenerate], signals that callers are expected to go
     * through a runtime rather than call a provider directly.
     */
    public suspend fun doEmbed(options: EmbeddingCallOptions): EmbeddingResult
}

// ---------------------------------------------------------------------------------------------------
// Image
// ---------------------------------------------------------------------------------------------------

/**
 * Everything one call to an [ImageModel] needs.
 *
 * As on [CallOptions], null means OMIT THE FIELD — several vendors reject an explicitly-sent value
 * they would have defaulted, so an unset knob must stay off the wire.
 */
public data class ImageCallOptions(
    /** What to draw. Nullable because an edit or variation call may carry only [files]. */
    val prompt: String? = null,
    /** How many images to generate. */
    val n: Int = 1,
    /** `WIDTHxHEIGHT`, e.g. `1024x1024`. Mutually exclusive with [aspectRatio] on most vendors. */
    val size: String? = null,
    /** `W:H`, e.g. `16:9`. */
    val aspectRatio: String? = null,
    /** Deterministic sampling, where the vendor honours one — same seed, same image. */
    val seed: Int? = null,
    /** Input images, for edit and variation endpoints. */
    val files: List<ImageFile>? = null,
    /** Which region of [files] to edit. */
    val mask: ImageFile? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
)

/**
 * An input image for an edit, variation or mask.
 *
 * A union rather than a data holder because vendors split on this: OpenAI takes bytes, Luma throws on
 * anything but a URL. Collapsing to bytes makes Luma image editing inexpressible; collapsing to a URL
 * makes an edit from a local file inexpressible.
 */
public sealed interface ImageFile {

    /** Media type, e.g. `image/png`. Nullable on [Url] because a bare link may not reveal one. */
    public val mediaType: String?

    /** Provider-namespaced options for this one file — see [ProviderOptions]. */
    public val providerOptions: ProviderOptions?

    /** Image bytes we hold, for vendors that take an upload. */
    public data class Data(
        val data: BinaryData,
        override val mediaType: String,
        override val providerOptions: ProviderOptions? = null,
    ) : ImageFile

    /** An image the vendor fetches for itself, for vendors that only take a link. */
    public data class Url(
        val url: String,
        override val mediaType: String? = null,
        override val providerOptions: ProviderOptions? = null,
    ) : ImageFile
}

/**
 * [providerMetadata] is the plain provider-keyed map here, where the reference narrows the modality's
 * own type to require an `images` array under each provider id. The narrowing is a TypeScript
 * convenience — a provider that fills it in under that key interoperates either way — and requiring it
 * would force every image provider that has nothing per-image to say to emit an empty array.
 */
public data class ImageResult(
    /** The generated images, in the vendor's order. */
    val images: List<BinaryData>,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** What the call cost — see [ImageUsage] for why the total is carried rather than derived. */
    val usage: ImageUsage? = null,
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, as far as the provider chose to reveal it. */
    val response: ModalityResponse,
    /** @see SpeechResult.request — the reference omits this here; a debuggable call does not. */
    val request: RequestInfo? = null,
    /** Whether an unsuccessful result, such as an empty image result, can be retried. Null = unclassified. */
    val isRetryable: Boolean? = null,
)

/**
 * [totalTokens] is carried rather than derived: several vendors bill a third number that is not the sum
 * — image tokens priced separately, a minimum charge — and computing it here would report the wrong
 * cost while looking authoritative.
 */
public data class ImageUsage(
    /** Prompt-side tokens, when the vendor reports them. */
    val inputTokens: Int? = null,
    /** Generation-side tokens, when the vendor reports them. */
    val outputTokens: Int? = null,
    /** The vendor's own usage block, for a counter this specification does not model. */
    val raw: JsonObject? = null,
    val totalTokens: Int? = null,
)

/**
 * An image-generation model.
 *
 * Like [LanguageModel], an implementation is pure translation — [ImageCallOptions] to a vendor request,
 * the response back to [ImageResult] — owning no state and deciding no batching: a caller wanting more
 * than [maxImagesPerCall] images issues more calls. Implementations are expected to be cheap to
 * construct and safe to share.
 */
public interface ImageModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /** Null where the vendor documents no ceiling. */
    public suspend fun maxImagesPerCall(): Int? = null

    /**
     * Generate images.
     *
     * The `do` prefix, as on [LanguageModel.doGenerate], signals that callers are expected to go
     * through a runtime rather than call a provider directly.
     */
    public suspend fun doGenerate(options: ImageCallOptions): ImageResult
}

// ---------------------------------------------------------------------------------------------------
// Speech (text to audio)
// ---------------------------------------------------------------------------------------------------

/**
 * Everything one call to a [SpeechModel] needs.
 *
 * As on [CallOptions], null means OMIT THE FIELD — the vendor's default is chosen by not asking, never
 * by sending a guessed value.
 */
public data class SpeechCallOptions(
    /** The text to speak. */
    val text: String,
    /** The vendor's voice id. Null takes whatever the vendor defaults to. */
    val voice: String? = null,
    /** Container/codec the vendor should return, e.g. `mp3`. */
    val outputFormat: String? = null,
    /** Free-text delivery direction, where the vendor accepts one. */
    val instructions: String? = null,
    /** Playback-rate multiplier; `1.0` is the voice's natural pace. */
    val speed: Double? = null,
    /** Language of [text], for vendors that do not detect it. */
    val language: String? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
)

/** The result of [SpeechModel.doGenerate]. */
public data class SpeechResult(
    /** The synthesized audio, in the requested — or failing that, the vendor's default — format. */
    val audio: BinaryData,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** The request as sent — the first thing a wire-level bug report needs. */
    val request: RequestInfo? = null,
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, as far as the provider chose to reveal it. */
    val response: ModalityResponse,
)

/**
 * A text-to-speech model.
 *
 * Like [LanguageModel], an implementation is pure translation — [SpeechCallOptions] to a vendor
 * request, the response back to [SpeechResult] — with no state of its own. Implementations are
 * expected to be cheap to construct and safe to share.
 */
public interface SpeechModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /**
     * Synthesize speech.
     *
     * The `do` prefix, as on [LanguageModel.doGenerate], signals that callers are expected to go
     * through a runtime rather than call a provider directly.
     */
    public suspend fun doGenerate(options: SpeechCallOptions): SpeechResult
}

// ---------------------------------------------------------------------------------------------------
// Transcription (audio to text)
// ---------------------------------------------------------------------------------------------------

/**
 * Everything one batch call to a [TranscriptionModel] needs.
 *
 * [mediaType] is required rather than sniffed: the audio may be a naked payload with no container to
 * inspect, and a vendor handed the wrong type transcribes noise instead of failing.
 */
public data class TranscriptionCallOptions(
    /** The complete recording to transcribe. Live audio goes to [TranscriptionModel.doStream]. */
    val audio: BinaryData,
    /** The audio's media type, e.g. `audio/mpeg`. */
    val mediaType: String,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
)

/** The result of [TranscriptionModel.doGenerate]. */
public data class TranscriptionResult(
    /** The full transcript. */
    val text: String,
    /** Timed slices of [text], where the vendor offers them. Empty means "not offered", not "silent". */
    val segments: List<Segment> = emptyList(),
    /** The language the vendor detected or was told, in its own code. */
    val language: String? = null,
    /** Length of the source audio, when the vendor reports it. */
    val durationInSeconds: Double? = null,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** The request as sent — the first thing a wire-level bug report needs. */
    val request: RequestInfo? = null,
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, as far as the provider chose to reveal it. */
    val response: ModalityResponse,
) {

    /** One timed slice of the transcript: [text] spans [startSecond] to [endSecond] in the audio. */
    public data class Segment(
        val text: String,
        val startSecond: Double,
        val endSecond: Double,
    )
}

/**
 * The shape of raw audio a streaming endpoint is being fed.
 *
 * Needed separately from a media type because live audio has no container to sniff: the caller is
 * pushing naked PCM frames, and the endpoint has to be told the rate rather than discovering it.
 */
public data class AudioFormat(
    /** e.g. `audio/pcm`, `audio/pcmu`, `audio/pcma`. */
    val type: String,
    /** Sample rate in Hz, where the format needs one. */
    val rate: Int? = null,
)

/**
 * Everything one live-transcription session needs.
 *
 * The session lives as long as [audio] does: the provider consumes the flow, and its completion is the
 * end-of-input signal.
 */
public data class TranscriptionStreamOptions(
    /**
     * Audio chunks, pushed as they are captured. Cancelling the collector ends the session.
     *
     * Bytes only, where the reference also accepts base64 chunks. A capture source produces bytes, so
     * the encoded form only ever appears because someone encoded it — and paying that cost per frame on
     * a live stream, to decode it again inside the provider, is a round trip with no destination.
     */
    val audio: Flow<ByteArray>,
    /** The shape of the raw audio in [audio] — see [AudioFormat] for why it must be declared. */
    val inputAudioFormat: AudioFormat,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
    /** Emit untouched provider events as [TranscriptionStreamPart.Raw] parts. Debugging aid. */
    val includeRawChunks: Boolean = false,
)

/**
 * One event from a live transcription.
 *
 * The partial/final distinction is the point of the whole contract: a live transcriber revises what it
 * said a moment ago, and a consumer that treats every emission as final renders text that jumps around.
 * [Partial] may be superseded; [Final] may not.
 */
public sealed interface TranscriptionStreamPart {

    /** Opens the stream, carrying anything the provider had to ignore about the call. */
    public data class StreamStart(val warnings: List<Warning> = emptyList()) : TranscriptionStreamPart

    /** Append-only text. */
    public data class TranscriptDelta(
        val delta: String,
        val id: String? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : TranscriptionStreamPart

    /** Text that a later part may revise. */
    public data class TranscriptPartial(
        val text: String,
        val id: String? = null,
        val startSecond: Double? = null,
        val durationInSeconds: Double? = null,
        val channelIndex: Int? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : TranscriptionStreamPart

    /** Settled text for one provider-defined segment or utterance. */
    public data class TranscriptFinal(
        val text: String,
        val id: String? = null,
        val startSecond: Double? = null,
        val endSecond: Double? = null,
        val channelIndex: Int? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : TranscriptionStreamPart

    /**
     * Response identity, emitted once the provider has it.
     *
     * [headers] and [body] ride along because a live transcription's identity arrives mid-stream: the
     * session is already open by the time the provider knows what it is talking to, so a caller that
     * wants the response headers has no other event to read them from.
     */
    public data class ResponseMetadataPart(
        val metadata: ResponseMetadata,
        val headers: Map<String, String>? = null,
        val body: String? = null,
    ) : TranscriptionStreamPart

    /** Terminal part: the settled transcript and its metadata. Exactly one per successful session. */
    public data class Finish(
        val text: String,
        val segments: List<TranscriptionResult.Segment> = emptyList(),
        val language: String? = null,
        val durationInSeconds: Double? = null,
        val providerMetadata: ProviderMetadata? = null,
    ) : TranscriptionStreamPart

    /** The untouched provider payload, when the caller asked for it. */
    public data class Raw(val rawValue: JsonElement) : TranscriptionStreamPart

    /**
     * A failure that does not end the session.
     *
     * Streamed rather than thrown so one bad frame does not discard the transcript already produced —
     * the same reason [StreamPart.Error] exists on the language-model side.
     */
    public data class Error(val error: Throwable) : TranscriptionStreamPart
}

/**
 * The result of [TranscriptionModel.doStream].
 *
 * [stream] is cold: collecting it opens the session, and cancelling the collector ends it.
 */
public data class TranscriptionStreamResult(
    /** One [TranscriptionStreamPart] per provider event, partials and finals interleaved. */
    val stream: Flow<TranscriptionStreamPart>,
    /** The request as sent — the first thing a wire-level bug report needs. */
    val request: RequestInfo? = null,
    /**
     * Response identity known at open time; identity that arrives later comes as a
     * [TranscriptionStreamPart.ResponseMetadataPart].
     */
    val response: ResponseInfo? = null,
)

/**
 * A speech-to-text model, batch and — where the vendor offers it — live.
 *
 * Like [LanguageModel], an implementation is pure translation — options to a vendor request, the
 * response back to results and stream parts — with no session state beyond the stream it returns.
 * Implementations are expected to be cheap to construct and safe to share.
 */
public interface TranscriptionModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /**
     * Transcribe a complete recording.
     *
     * The `do` prefix, as on [LanguageModel.doGenerate], signals that callers are expected to go
     * through a runtime rather than call a provider directly.
     */
    public suspend fun doGenerate(options: TranscriptionCallOptions): TranscriptionResult

    /**
     * Transcribe live audio. Null where the vendor offers no streaming endpoint.
     *
     * Nullable rather than a throwing default: "does this model do live transcription" has to be
     * answerable without opening a session, which is the same rule [Provider] follows for modalities.
     */
    public suspend fun doStream(options: TranscriptionStreamOptions): TranscriptionStreamResult? = null
}

// ---------------------------------------------------------------------------------------------------
// Reranking
// ---------------------------------------------------------------------------------------------------

/**
 * Everything one call to a [RerankingModel] needs.
 *
 * The model scores [documents] against [query] and retrieves nothing — fetching candidates is the
 * caller's job, which is what keeps a reranker swappable under any retrieval stack.
 */
public data class RerankingCallOptions(
    /** The candidates to score. */
    val documents: Documents,
    /** What the documents are scored against. */
    val query: String,
    /** Return only the top N results. Null returns the vendor's default. */
    val topN: Int? = null,
    /** Provider-namespaced options, passed through verbatim — see [ProviderOptions]. */
    val providerOptions: ProviderOptions? = null,
    /** Extra HTTP headers, for providers that speak HTTP. */
    val headers: Map<String, String>? = null,
) {

    /** Documents are either plain text or structured objects; vendors accept one form or the other. */
    public sealed interface Documents {
        /** Plain-text candidates. */
        public data class Text(val values: List<String>) : Documents

        /** Structured candidates, for vendors that rank over fields. */
        public data class Objects(val values: List<JsonObject>) : Documents
    }
}

/**
 * The result of [RerankingModel.doRerank].
 *
 * [ranking] points back into the submitted list by index rather than echoing the documents, so the
 * caller keeps its own objects and nothing round-trips through the vendor's copy of them.
 */
public data class RerankingResult(
    /** Indices into the submitted documents, with scores. Order is the ranking. */
    val ranking: List<Rank>,
    /** Anything the provider had to ignore about the call — see [Warning]. */
    val warnings: List<Warning> = emptyList(),
    /** Provider-namespaced output, carried verbatim — see [ProviderMetadata]. */
    val providerMetadata: ProviderMetadata? = null,
    /** Response identity, as far as the provider chose to reveal it. */
    val response: ModalityResponse? = null,
    /** @see SpeechResult.request — the reference omits this here; a debuggable call does not. */
    val request: RequestInfo? = null,
) {

    /** One ranked document: [index] into the submitted list, plus the vendor's [relevanceScore]. */
    public data class Rank(val index: Int, val relevanceScore: Double)
}

/**
 * A reranking model.
 *
 * Like [LanguageModel], an implementation is pure translation — [RerankingCallOptions] to a vendor
 * request, the response back to [RerankingResult] — with no state of its own. Implementations are
 * expected to be cheap to construct and safe to share.
 */
public interface RerankingModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /**
     * Score the documents against the query.
     *
     * The `do` prefix, as on [LanguageModel.doGenerate], signals that callers are expected to go
     * through a runtime rather than call a provider directly.
     */
    public suspend fun doRerank(options: RerankingCallOptions): RerankingResult
}
