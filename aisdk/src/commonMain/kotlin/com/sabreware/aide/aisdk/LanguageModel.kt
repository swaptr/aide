package com.sabreware.aide.aisdk

/** The specification version this module implements, mirroring the reference implementation's `v4`. */
public const val SPECIFICATION_VERSION: String = "v4"

/**
 * What every provider implements, and the only thing a consumer needs to know about one.
 *
 * A provider's whole job is translation: [CallOptions] to a vendor request, and the vendor's response
 * back to [Content] / [StreamPart]. It owns no conversation state, runs no tools and decides no control
 * flow — a multi-round tool loop belongs to a runtime above this interface, which is what lets one loop
 * serve every vendor.
 *
 * Implementations are expected to be cheap to construct and safe to share.
 */
public interface LanguageModel {

    /** Always [SPECIFICATION_VERSION]; lets a consumer reject a model built against an older spec. */
    public val specificationVersion: String get() = SPECIFICATION_VERSION

    /** The provider's id, e.g. `anthropic`. This is the key its [ProviderMetadata] is filed under. */
    public val provider: String

    /** The vendor's own model identifier, sent on the wire. */
    public val modelId: String

    /**
     * URL patterns this model fetches for itself, keyed by media type: either a full type such as
     * `application/pdf`, or a wildcard — the type, a slash, then a star — with a bare star matching
     * everything. Values are patterns matched against the lower-cased URL.
     *
     * (Spelled out rather than shown, because Kotlin block comments nest: a literal slash-star inside
     * KDoc opens a comment the closing delimiter then fails to balance.)
     *
     * A matching URL is passed through as a [FileData.Url] instead of being downloaded and re-uploaded —
     * which for a large PDF is the difference between one request and two plus the bytes in memory.
     */
    public suspend fun supportedUrls(): Map<String, List<Regex>> = emptyMap()

    /**
     * Generate without streaming.
     *
     * Named with a `do` prefix, as in the reference implementation, to signal that callers are expected
     * to go through a runtime rather than call a provider directly.
     */
    public suspend fun doGenerate(options: CallOptions): GenerateResult

    /**
     * Generate, streaming.
     *
     * The returned [StreamResult.stream] is cold: nothing is sent until it is collected, and cancelling
     * the collector cancels the request.
     */
    public suspend fun doStream(options: CallOptions): StreamResult
}
